#!/usr/bin/env python3
"""
Probe the printer's camera directly, following the reference implementation
(ha-bambulab) exactly, so the guesswork stops.

The printer is on this machine's LAN, so this can settle the protocol without a
phone, an APK or a test run by Václav.

    python3 camera-probe.py --host 192.168.1.50 --code-file /path/to/code-0600
    python3 camera-probe.py --host 192.168.1.50 --code-file ... --layout all

The access code is read from a file (mode 600) and is NEVER printed, logged or put
on the command line.

What it reports, in order, so a failure names its stage:
    tcp connect -> tls handshake (with the certificate CN) -> auth sent
    -> first 16-byte header (little-endian size, word8) -> jpeg size and magic
    -> so many frames in so many seconds

Frame header, per the reference:
    [0:4]   little-endian payload size for the jpeg (excludes the header)
    [4:8]   0
    [8:12]  1
    [12:16] 0
"""
import argparse, socket, ssl, struct, sys, time


def read_code(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            code = f.read().strip()
    except FileNotFoundError:
        raise SystemExit(f"no such file: {path}")
    if not code:
        raise SystemExit("access code file is empty")
    return code


def auth_packet(code, layout="standard"):
    """standard = the documented layout; legacy = what the app sent up to 0.6."""
    if layout == "standard":
        b = bytearray()
        b += struct.pack("<I", 0x40)
        b += struct.pack("<I", 0x3000)
        b += struct.pack("<I", 0)
        b += struct.pack("<I", 0)
        u = b"bblp"
        b += u + b"\x00" * (32 - len(u))
        c = code.encode("ascii", "ignore")[:32]
        b += c + b"\x00" * (32 - len(c))
        return bytes(b)
    # legacy: big-endian lengths, user at 8, code at 16 (known wrong, kept for comparison)
    b = bytearray(80)
    struct.pack_into(">I", b, 0, 80)
    struct.pack_into(">I", b, 4, 4)
    b[8:12] = b"bblp"
    struct.pack_into(">I", b, 12, len(code))
    c = code.encode("ascii", "ignore")[:64]
    b[16:16 + len(c)] = c
    return bytes(b)


def probe(host, port, code, layout, seconds=12.0, insecure=True):
    print(f"  --- layout={layout} host={host}:{port}")
    stage = "tcp connect"
    t0 = time.time()
    try:
        raw = socket.create_connection((host, port), 8)
        stage = "tls handshake"
        ctx = ssl.create_default_context()
        if insecure:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        s = ctx.wrap_socket(raw, server_hostname=host)
        s.settimeout(10)
        cert = s.getpeercert(binary_form=True)
        cn = ""
        try:
            # only for reporting; no verification is implied
            cn = s.getpeercert().get("subject", ((("", ""),),))[0][0][1]
        except Exception:
            pass
        print(f"      {stage}: ok in {int((time.time()-t0)*1000)}ms  cert_cn={cn!r}")

        stage = "auth sent"
        s.sendall(auth_packet(code, layout))
        print(f"      {stage}: 80 bytes sent")

        stage = "first header"
        buf = b""
        deadline = time.time() + seconds
        frames = 0
        first = True
        while time.time() < deadline:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                print("      recv: timeout (nothing arrived)")
                break
            if not chunk:
                print(f"      recv: peer closed after {len(buf)} buffered bytes"
                      f"{' — ' + buf[:32].hex() if buf else ''}")
                break
            buf += chunk
            if first:
                first = False
                if len(buf) >= 16:
                    size, w4, w8, w12 = struct.unpack("<IIII", buf[:16])
                    print(f"      header: size={size} (LE) word4={w4} word8={w8} word12={w12}")
                    print(f"              size if read BIG-endian would be "
                          f"{struct.unpack('>I', buf[:4])[0]}  <- this is the 0.8 bug")
            # marker based, exactly like the reference
            start = buf.find(b"\xff\xd8\xff")
            if start >= 0:
                end = buf.find(b"\xff\xd9", start)
                if end > start:
                    img = buf[start:end + 2]
                    frames += 1
                    print(f"      frame {frames}: {len(img)} bytes, jpeg magic ok, "
                          f"offset {start}")
                    buf = buf[end + 2:]
                    if frames == 1:
                        open("/var/tmp/probe-frame.jpg", "wb").write(img)
                        print("      first frame written to /var/tmp/probe-frame.jpg")
        print(f"      frames in window: {frames}")
        s.close()
        return frames
    except Exception as e:
        print(f"      FAILED at {stage} after {int((time.time()-t0)*1000)}ms: "
              f"{type(e).__name__}: {e}")
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=6000)
    ap.add_argument("--code-file", required=True, help="file holding the access code, mode 600")
    ap.add_argument("--layout", default="standard", choices=["standard", "legacy", "all"])
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--verify-tls", action="store_true",
                    help="verify the certificate instead of accepting it (it is self-signed, so this will fail)")
    a = ap.parse_args()

    code = read_code(a.code_file)
    print(f"  host={a.host}:{a.port}  code_read={len(code)} chars (not printed)")
    layouts = ["standard", "legacy"] if a.layout == "all" else [a.layout]
    for lay in layouts:
        if probe(a.host, a.port, code, lay, a.seconds, insecure=not a.verify_tls):
            print(f"  ==> layout {lay} WORKS")
            return 0
    print("  ==> no layout produced a frame")
    return 1


if __name__ == "__main__":
    sys.exit(main())
