#!/usr/bin/env python3
"""Mock A1 camera: TLS on 6000, read the 80-byte auth packet, then stream
length-prefixed JPEG frames at ~2 fps, the same framing the real printer uses."""
import socket, ssl, struct, sys, time, pathlib

CRT = pathlib.Path(__file__).with_name("mock.crt")
KEY = pathlib.Path(__file__).with_name("mock.key")
frames = sorted(pathlib.Path(__file__).with_name("frames").glob("*.jpg"))
if not frames:
    sys.exit("no frames in ./frames")

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CRT, KEY)

srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", 6000))
srv.listen(4)
print("mockcam listening on :6000", flush=True)

while True:
    raw, addr = srv.accept()
    try:
        s = ctx.wrap_socket(raw, server_side=True)
        auth = b""
        while len(auth) < 80:
            chunk = s.recv(80 - len(auth))
            if not chunk:
                raise ConnectionError("short auth")
            auth += chunk
        ulen = struct.unpack(">I", auth[4:8])[0]
        user = auth[8:8 + ulen].decode("ascii", "replace")
        clen = struct.unpack(">I", auth[12:16])[0]
        code = auth[16:16 + clen]
        print(f"  auth from {addr[0]}: user={user!r} code_len={clen}", flush=True)
        i = 0
        while True:
            data = frames[i % len(frames)].read_bytes()
            # the real printer's header: size LITTLE-endian, 0, 1, 0.
            # the old mock wrote a big-endian size with word8=0, which agreed with the
            # app's own endian bug — a fake that cannot catch the mistake it imitates.
            header = struct.pack("<I", len(data)) + struct.pack("<I", 0) \
                + struct.pack("<I", 1) + struct.pack("<I", 0)
            s.sendall(header + data)
            i += 1
            time.sleep(0.4)
    except Exception as e:
        print(f"  client gone: {type(e).__name__}: {e}", flush=True)
    finally:
        try: raw.close()
        except Exception: pass
