"""Live per-printer status, held by the slicer.

The phone cannot keep an MQTT connection open to every printer — a dashboard that shows
three machines would need three connections that survive the screen going off. The slicer
is always on, already knows every printer's address and access code, and can hold one
connection per machine. So it does, and the phone reads a summary.

A summary is deliberately small and boring: what a card needs.
"""

import json
import socket
import ssl
import struct
import threading
import time

CONNECT, CONNACK, PUBLISH, SUBSCRIBE, SUBACK, PINGREQ, PINGRESP = 1, 2, 3, 8, 9, 12, 13


def _varint(n):
    out = b""
    while True:
        b = n % 128
        n //= 128
        out += bytes([b | 0x80]) if n else bytes([b])
        if not n:
            return out


def _string(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b


def _packet(ptype, flags, body):
    return bytes([(ptype << 4) | flags]) + _varint(len(body)) + body


class Client:
    def __init__(self, host, port=8883, timeout=90):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        raw = socket.create_connection((host, port), timeout=15)
        raw.settimeout(timeout)
        self.s = ctx.wrap_socket(raw)

    def send(self, data):
        self.s.sendall(data)

    def _fill(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return buf

    def read(self):
        head = self._fill(1)[0]
        ptype, flags = head >> 4, head & 0x0F
        n, shift = 0, 0
        while True:
            b = self._fill(1)[0]
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        return ptype, flags, self._fill(n) if n else b""

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def _connect(c, code, client_id):
    vh = _string("MQTT") + bytes([4, 0xC2]) + struct.pack(">H", 60)
    payload = _string(client_id) + _string("bblp") + _string(code)
    c.send(_packet(CONNECT, 0, vh + payload))
    ptype, _, body = c.read()
    if ptype != CONNACK or (len(body) > 1 and body[1] != 0):
        raise IOError("CONNACK refused: %r" % (body,))


def _subscribe(c, topic):
    c.send(_packet(SUBSCRIBE, 2, struct.pack(">H", 1) + _string(topic) + bytes([0])))
    ptype, _, body = c.read()
    if ptype != SUBACK or body[-1] == 0x80:
        raise IOError("SUBACK refused")


SUMMARY_KEYS = {
    "gcode_state": "state",
    "mc_percent": "percent",
    "layer_num": "layer",
    "total_layer_num": "layers",
    "mc_remaining_time": "remaining_min",
    "nozzle_temper": "nozzle",
    "bed_temper": "bed",
    "gcode_file": "file",
    "subtask_name": "name",
    "print_error": "error",
    "hw_switch_state": "filament_at_nozzle",
    "chamber_temper": "chamber",
}


def _summarise(report, printer_id):
    p = report.get("print") or {}
    out = {"printer": printer_id, "updated": int(time.time())}
    for src, dst in SUMMARY_KEYS.items():
        if src in p:
            out[dst] = p[src]
    ams = (p.get("ams") or {}).get("ams") or []
    slots = []
    for unit in ams:
        for t in (unit.get("tray") or []):
            if t.get("tray_type"):
                slots.append({
                    "slot": t.get("id"),
                    "type": t.get("tray_type"),
                    "color": ("#" + t["tray_color"][:6]) if t.get("tray_color") else None,
                    "remain": t.get("remain"),
                })
    if slots:
        out["slots"] = slots
        out["ams"] = True
    hms = p.get("hms") or []
    out["hms"] = ["%08X%08X" % (h.get("attr", 0), h.get("code", 0)) for h in hms]
    return out


class Watcher(threading.Thread):
    """One printer, one connection, reconnecting forever. Never raises."""

    def __init__(self, printer, store):
        super().__init__(daemon=True, name="watch-%s" % printer.get("id"))
        self.printer = printer
        self.store = store
        self.state = {"printer": printer.get("id"), "link": "connecting"}
        self.acc = {}

    def run(self):
        pid = self.printer.get("id")
        host = self.printer.get("host")
        while True:
            try:
                code = open(self.printer["code_file"]).read().strip()
                c = Client(host)
                _connect(c, code, "slicer%d%s" % (int(time.time()) % 100000, pid))
                _subscribe(c, "device/%s/report" % self.printer["serial"])
                # Without this the printer only ever pushes DELTAS, so gcode_state and the
                # job name never arrive and the dashboard card would show temperatures and
                # nothing else. Ask for the full state once per connection.
                c.send(_packet(PUBLISH, 0,
                              _string("device/%s/request" % self.printer["serial"]) +
                              b'{"pushing":{"sequence_id":"0","command":"pushall"}}'))
                self.state = {"printer": pid, "link": "ok"}
                self.store.set(pid, self.state)
                while True:
                    try:
                        c.send(_packet(PINGREQ, 0, b""))
                    except Exception:
                        raise
                    ptype, flags, body = c.read()
                    if ptype != PUBLISH:
                        continue
                    tlen = struct.unpack(">H", body[:2])[0]
                    rest = body[2 + tlen:]
                    if (flags >> 1) & 0x03:
                        rest = rest[2:]
                    try:
                        report = json.loads(rest.decode("utf-8", "replace"))
                    except Exception:
                        continue
                    # Bambu sends DELTAS between full reports: a message with only
                    # nozzle_temper must not erase gcode_state. Merge, never replace.
                    self.acc.update({k: v for k, v in _summarise(report, pid).items()
                                     if k not in ("updated",)})
                    self.acc["printer"] = pid
                    self.state = dict(self.acc)
                    self.state["link"] = "ok"
                    self.state["updated"] = int(time.time())
                    self.store.set(pid, self.state)
            except Exception as e:
                self.state = {"printer": pid, "link": "down", "why": "%s: %s" % (type(e).__name__, e),
                              "updated": int(time.time())}
                self.store.set(pid, self.state)
                time.sleep(10)


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.data = {}

    def set(self, pid, value):
        with self.lock:
            self.data[pid] = dict(value)

    def get(self, pid):
        with self.lock:
            v = self.data.get(pid)
            if not v:
                return None
            out = dict(v)
        if out.get("updated") and time.time() - out["updated"] > 120:
            out["stale"] = True
        return out

    def start(self, printers):
        started = []
        for p in printers:
            host = (p.get("host") or "").strip()
            if not host or host.endswith(".0") or not p.get("serial"):
                self.set(p.get("id"), {"printer": p.get("id"), "link": "unconfigured"})
                continue
            w = Watcher(p, self)
            w.start()
            started.append(p.get("id"))
        return started
