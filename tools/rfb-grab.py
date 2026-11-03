#!/usr/bin/env python3
"""Save one framebuffer from an RFB (VNC) server — no client library, no password support.

Built to check a headless display from another machine: if this returns an image, the
X server, the compositor and the VNC server are all alive and serving, whatever any log says.

    python3 tools/rfb-grab.py <host> <port> out.png

The two protocol details that cost an hour:
  * FramebufferUpdate is type(1) + padding(1) + count(2) — miss the pad and every later
    rectangle is misaligned (the symptom is an unexplained message type).
  * SetPixelFormat is type(1) + padding(3) + a 16-byte PIXEL_FORMAT.
  * Servers may send an empty update first; keep reading rather than re-requesting.
"""
import socket
import struct
import sys
import zlib


def png(path, w, h, rgb):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    stride = w * 3
    body = b"".join(b"\x00" + bytes(rgb[y * stride:(y + 1) * stride]) for y in range(h))
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n"
                           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(body, 6))
                           + chunk(b"IEND", b""))


def grab(host, port, out):
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    s = socket.socket(family, socket.SOCK_STREAM)
    s.settimeout(20)
    s.connect((host, int(port)))

    def readn(n):
        buf = b""
        while len(buf) < n:
            part = s.recv(n - len(buf))
            if not part:
                raise SystemExit("connection closed after %d of %d bytes" % (len(buf), n))
            buf += part
        return buf

    readn(12)                                   # "RFB 003.008\n"
    s.sendall(b"RFB 003.008\n")
    count = readn(1)[0]
    kinds = list(readn(count))
    if 1 not in kinds:
        raise SystemExit("server wants authentication (%s); this tool only does None" % kinds)
    s.sendall(b"\x01")                          # security type: None
    readn(4)                                    # security result
    s.sendall(b"\x01")                          # ClientInit, shared
    w, h = struct.unpack(">HH", readn(4))
    readn(16)                                   # server's pixel format
    readn(struct.unpack(">I", readn(4))[0])     # desktop name

    # ask for 32bpp true colour, little endian, shifts 16/8/0 — BGRA in memory
    s.sendall(b"\x00\x00\x00\x00"
              + struct.pack(">BBBBHHHBBB3x", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0))
    s.sendall(b"\x02\x00\x00\x01\x00\x00\x00\x00")           # SetEncodings: Raw only
    s.sendall(struct.pack(">BBHHHH", 3, 0, 0, 0, w, h))      # full update request

    px = bytearray(w * h * 4)
    while True:
        kind = readn(1)[0]
        if kind == 0:
            readn(1)                                         # header padding
            rects = struct.unpack(">H", readn(2))[0]
            if not rects:
                continue
            for _ in range(rects):
                x, y, rw, rh, enc = struct.unpack(">HHHHi", readn(12))
                if enc != 0:
                    raise SystemExit("got encoding %d, wanted raw" % enc)
                data = readn(rw * rh * 4)
                for row in range(rh):
                    off = ((y + row) * w + x) * 4
                    px[off:off + rw * 4] = data[row * rw * 4:(row + 1) * rw * 4]
            break
        if kind == 2:                                        # bell
            continue
        if kind == 3:                                        # server cut text
            readn(3); readn(struct.unpack(">I", readn(4))[0])
            continue
        raise SystemExit("unexpected message type %d" % kind)

    rgb = bytearray(w * h * 3)
    for i in range(w * h):
        rgb[i * 3] = px[i * 4 + 2]
        rgb[i * 3 + 1] = px[i * 4 + 1]
        rgb[i * 3 + 2] = px[i * 4]
    png(out, w, h, rgb)
    print("%s: %dx%d, %d bytes" % (out, w, h, len(open(out, "rb").read())))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    grab(sys.argv[1], sys.argv[2], sys.argv[3])
