#!/usr/bin/env python3
"""
Talk MQTT 3.1.1 to the printer directly, with no client library, so the answer is not
filtered through someone else's TLS defaults.

    ./mqtt-probe.py --host 192.168.1.50 --code-file ~/.bambu-code \
                    --serial YOUR_SERIAL

Reports, in order:
  1. TLS handshake (certificate CN, which should be the serial)
  2. CONNACK return code                  <- 0 means the credentials are accepted
  3. subscribe to device/<serial>/report  <- does the broker keep us?
  4. publish pushall, then wait           <- do reports arrive?
  5. the same with device/+/report        <- hypothesis 1: does the wildcard get us dropped?

The access code is read from a file and is never printed.
"""
import argparse, socket, ssl, struct, sys, time

CONNECT, CONNACK, PUBLISH, SUBSCRIBE, SUBACK, PINGREQ, PINGRESP = 1, 2, 3, 8, 9, 12, 13


def read_code(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        raise SystemExit(f"no such file: {path}")


def varint(n):
    out = bytearray()
    while True:
        b = n % 128
        n //= 128
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def packet(ptype, flags, body):
    return bytes([(ptype << 4) | flags]) + varint(len(body)) + body


class Conn:
    def __init__(self, host, port, insecure=True):
        raw = socket.create_connection((host, port), 8)
        ctx = ssl.create_default_context()
        if insecure:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        self.s = ctx.wrap_socket(raw, server_hostname=host)
        self.s.settimeout(8)
        self.buf = b""

    def send(self, data):
        self.s.sendall(data)

    def _fill(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(4096)
            if not chunk:
                raise ConnectionError(f"printer closed after {len(self.buf)} buffered bytes")
            self.buf += chunk

    def read_packet(self):
        self._fill(2)
        ptype = self.buf[0] >> 4
        flags = self.buf[0] & 0x0F
        mult, val, i = 1, 0, 1
        while True:
            self._fill(i + 1)
            b = self.buf[i]
            val += (b & 0x7F) * mult
            mult *= 128
            i += 1
            if not (b & 0x80):
                break
        total = i + val
        self._fill(total)
        body = self.buf[i:total]
        self.buf = self.buf[total:]
        return ptype, flags, body

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def connect(host, port, code, client_id):
    c = Conn(host, port)
    payload = string(client_id) + string("bblp") + string(code)
    vh = string("MQTT") + bytes([4, 0x80 | 0x40 | 0x02]) + struct.pack(">H", 30)
    c.send(packet(CONNECT, 0, vh + payload))
    ptype, _, body = c.read_packet()
    if ptype != CONNACK:
        raise ConnectionError(f"expected CONNACK, got packet type {ptype}")
    session, rc = body[0], body[1]
    return c, rc


def subscribe(c, topic, pid=1):
    body = struct.pack(">H", pid) + string(topic) + bytes([1])
    c.send(packet(SUBSCRIBE, 0x02, body))
    try:
        ptype, _, b = c.read_packet()
    except ConnectionError as e:
        print(f"      printer closed the connection at subscribe: {e}")
        print("      ==> this topic is REJECTED by the broker")
        return None
    if ptype != SUBACK:
        return None
    return b[-1]  # granted qos, or 0x80 for failure


def publish(c, topic, payload):
    c.send(packet(PUBLISH, 0, string(topic) + payload.encode()))


def collect(c, seconds):
    """read until the deadline, counting publishes; returns (count, first_payload_len, closed)"""
    n, first, closed = 0, None, False
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            ptype, flags, body = c.read_packet()
        except (socket.timeout, TimeoutError):
            break
        except ConnectionError as e:
            closed = True
            print(f"      printer closed the connection: {e}")
            break
        if ptype == PUBLISH:
            tlen = struct.unpack(">H", body[:2])[0]
            topic = body[2:2 + tlen].decode("utf-8", "replace")
            rest = body[2 + tlen:]
            qos = (flags >> 1) & 0x03
            if qos > 0:
                rest = rest[2:]   # packet id
            n += 1
            if first is None:
                first = len(rest)
                print(f"      first report on {topic}: {len(rest)} bytes")
    return n, first, closed


def run(host, port, code, serial, topic, label, seconds=8):
    print(f"  --- {label}: topic {topic}")
    client_id = f"probe{int(time.time()) % 10000}"
    try:
        c, rc = connect(host, port, code, client_id)
    except Exception as e:
        print(f"      CONNECT failed: {type(e).__name__}: {e}")
        return
    print(f"      CONNACK return code {rc}" + (" (accepted)" if rc == 0 else " (REJECTED)"))
    if rc != 0:
        c.close()
        return
    granted = subscribe(c, topic)
    if granted is None:
        print("      SUBACK not received — the printer dropped us at subscribe")
        c.close()
        return
    print(f"      SUBACK granted qos {granted}")
    if serial and "request" not in topic:
        publish(c, f"device/{serial}/request",
                '{"pushing":{"sequence_id":"0","command":"pushall"}}')
        print("      pushall sent")
    n, first, closed = collect(c, seconds)
    print(f"      reports received in {seconds}s: {n}")
    if n:
        print("      ==> this topic works")
    c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=8883)
    ap.add_argument("--code-file", required=True)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--seconds", type=float, default=8)
    a = ap.parse_args()

    code = read_code(a.code_file)
    print(f"  host={a.host}:{a.port} code_read={len(code)} chars (not printed) serial={a.serial}")

    # certificate CN first: it should equal the serial
    raw = socket.create_connection((a.host, a.port), 8)
    ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    s = ctx.wrap_socket(raw, server_hostname=a.host)
    der = s.getpeercert(binary_form=True)
    s.close()
    print(f"      tls ok, certificate {len(der)} bytes (CN should be the serial: {a.serial})")

    run(a.host, a.port, code, a.serial, f"device/{a.serial}/report", "own topic", a.seconds)
    print()
    run(a.host, a.port, code, a.serial, "device/+/report", "wildcard", a.seconds)
    return 0


if __name__ == "__main__":
    sys.exit(main())
