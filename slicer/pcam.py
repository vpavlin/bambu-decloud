"""One JPEG frame from a printer's camera, on demand.

Same protocol the phone uses (and the same one camera-probe.py settles): TLS to 6000, an
80-byte auth packet with the access code, then frames prefixed with a 16-byte
little-endian header whose first word is the payload size.

The slicer serves this so a client can show a printer it has no credentials for.
"""

import socket
import ssl
import struct


def auth_packet(code):
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


def frame(host, code, port=6000, timeout=8.0):
    """Returns JPEG bytes, or raises. Never returns a partial image."""
    raw = socket.create_connection((host, port), timeout=6)
    try:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        s = ctx.wrap_socket(raw, server_hostname=host)
        s.settimeout(timeout)
        s.sendall(auth_packet(code))
        buf = b""
        deadline = timeout
        while True:
            while len(buf) < 16:
                chunk = s.recv(4096)
                if not chunk:
                    raise IOError("camera closed before a frame")
                buf += chunk
            size, w4, w8, w12 = struct.unpack("<IIII", buf[:16])
            if not (1000 < size < 4_000_000):
                # the 0.8 bug: reading the size big-endian gives a nonsense number
                raise IOError("implausible frame size %d — wrong endianness or auth refused" % size)
            while len(buf) < 16 + size:
                chunk = s.recv(65536)
                if not chunk:
                    raise IOError("camera closed mid-frame")
                buf += chunk
            payload = buf[16:16 + size]
            if payload[:2] != b"\xff\xd8":
                raise IOError("frame is not a JPEG (magic %r)" % payload[:2])
            del deadline
            return payload
    finally:
        try:
            s.close()
        except Exception:
            pass
