#!/usr/bin/env python3
"""Draw a top-down picture of a sliced gcode file, with no dependencies.

Why not ask the slicer for its own preview: the AppImage is built for Wayland and
carries no GL, so `--export-png` dies with `glfwInit return error, code 65544`
under Xvfb.  The toolpath is the truer picture anyway: it is exactly what the
nozzle will do, not a rendering of the model that was handed in.
"""

import math
import zlib
import struct

# Chrome, in the same palette as the app
BG = (0x14, 0x17, 0x1c)
BED = (0x2f, 0x36, 0x40)
GRID = (0x22, 0x28, 0x30)

COLOURS = {
    "Outer wall": (0x6f, 0xb8, 0xff),
    "Overhang wall": (0xff, 0x9e, 0x64),
    "Inner wall": (0x8a, 0xa6, 0xc8),
    "Sparse infill": (0x45, 0x4f, 0x5d),
    "Internal solid infill": (0x59, 0x66, 0x77),
    "Top surface": (0xe0, 0xa0, 0x30),
    "Bottom surface": (0xb8, 0x84, 0x2a),
    "Bridge": (0xd4, 0x6a, 0x8a),
    "Gap infill": (0x5b, 0x63, 0x6f),
    "Skirt": (0x3a, 0x42, 0x4d),
    "Support": (0x50, 0x6b, 0x58),
    "Support interface": (0x6a, 0x8a, 0x72),
    "Custom": (0x4c, 0x8d, 0xff),
}
DEFAULT = (0x62, 0x6b, 0x78)


class Canvas:
    def __init__(self, w, h, bg=BG):
        self.w, self.h = w, h
        self.buf = bytearray(bytes(bg) * (w * h))

    def blend(self, x, y, colour, alpha):
        if x < 0 or y < 0 or x >= self.w or y >= self.h:
            return
        i = (y * self.w + x) * 3
        b = self.buf
        a = alpha
        na = 1.0 - a
        b[i] = int(b[i] * na + colour[0] * a)
        b[i + 1] = int(b[i + 1] * na + colour[1] * a)
        b[i + 2] = int(b[i + 2] * na + colour[2] * a)

    def line(self, x0, y0, x1, y1, colour, alpha=1.0, width=1):
        """Bresenham, with an optional fat width for a legible nozzle."""
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        r = width // 2
        while True:
            if width <= 1:
                self.blend(x0, y0, colour, alpha)
            else:
                for ox in range(-r, r + 1):
                    for oy in range(-r, r + 1):
                        self.blend(x0 + ox, y0 + oy, colour, alpha)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def rect(self, x0, y0, x1, y1, colour, alpha=1.0):
        for x in range(int(x0), int(x1) + 1):
            self.blend(x, int(y0), colour, alpha)
            self.blend(x, int(y1), colour, alpha)
        for y in range(int(y0), int(y1) + 1):
            self.blend(int(x0), y, colour, alpha)
            self.blend(int(x1), y, colour, alpha)

    def png(self, path):
        raw = bytearray()
        stride = self.w * 3
        for y in range(self.h):
            raw.append(0)  # filter: none
            raw += self.buf[y * stride:(y + 1) * stride]

        def chunk(tag, data):
            c = struct.pack(">I", len(data)) + tag + data
            return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

        out = b"\x89PNG\r\n\x1a\n"
        out += chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
        out += chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        out += chunk(b"IEND", b"")
        with open(path, "wb") as f:
            f.write(out)


FEATURE_ORDER = list(COLOURS.keys())


def parse_layers(path, max_segments=400000):
    """Extract the toolpath as layers of plain segments, for anything that wants
    to draw it (the flat PNG here, the 3D viewer in the browser).

    Returns (layers, features, zs) where a layer is (z, [(x0,y0,x1,y1,feat), ...]).
    """
    layers = []
    cur = []
    z = 0.0
    feat = "Custom"
    features = []
    x = y = 0.0
    total = 0
    with open(path, "r", errors="replace") as f:
        for line in f:
            if line.startswith(";"):
                if "FEATURE:" in line:
                    feat = line.split("FEATURE:", 1)[1].strip()
                    if feat not in features:
                        features.append(feat)
                elif "Z_HEIGHT:" in line:
                    try:
                        z = float(line.split("Z_HEIGHT:", 1)[1].strip())
                    except ValueError:
                        pass
                elif "CHANGE_LAYER" in line:
                    if cur:
                        layers.append((z, cur))
                    cur = []
                continue
            if not line.startswith("G1"):
                continue
            nx = ny = None
            e = 0.0
            for tok in line.split():
                if tok[0] == "X":
                    nx = float(tok[1:])
                elif tok[0] == "Y":
                    ny = float(tok[1:])
                elif tok[0] == "E":
                    e = float(tok[1:])
            if nx is None and ny is None:
                continue
            tx = x if nx is None else nx
            ty = y if ny is None else ny
            if e > 0 and (tx != x or ty != y):
                total += 1
                if total <= max_segments:
                    cur.append((x, y, tx, ty, feat))
            x, y = tx, ty
    if cur:
        layers.append((z, cur))
    return layers, features, total


def toolpath_bin(layers, features, bed, max_segments=200000):
    """A compact stream for the browser: float32 quad, one byte of feature id."""
    idx = {f: i for i, f in enumerate(features)}
    kept = []
    total = sum(len(s) for _, s in layers)
    stride = max(1, int(math.ceil(total / float(max_segments))))
    for z, segs in layers:
        chosen = segs[::stride] if stride > 1 else segs
        if chosen:
            kept.append((z, chosen))
    Q = 100.0  # 0.01 mm in uint16: 256 mm of bed needs 25600, well inside 65536
    def q(v):
        return max(0, min(65535, int(round(v * Q))))
    out = bytearray()
    out += b"SLT2"
    out += struct.pack("<I", len(kept))
    out += struct.pack("<ffff", bed[0], bed[1], max((z for z, _ in kept), default=0.0), Q)
    out += struct.pack("<I", len(features))
    for f in features:
        b = f.encode()[:31]
        out += struct.pack("<B", len(b)) + b.ljust(31, b"\0")
    for z, segs in kept:
        out += struct.pack("<fI", z, len(segs))
        for (x0, y0, x1, y1, feat) in segs:
            out += struct.pack("<HHHH", q(x0), q(y0), q(x1), q(y1))
        out += bytes(idx.get(f, 0) for (_, _, _, _, f) in segs)
    return bytes(out), stride, len(kept)


def render_gcode(path, out_png, bed=(256.0, 256.0), px=None, max_segments=1200000):
    """Returns a small dict of what was seen, so the caller can report it."""
    w_mm, h_mm = bed
    px = px or 640
    scale = px / max(w_mm, h_mm)
    W = int(w_mm * scale) + 1
    H = int(h_mm * scale) + 1
    c = Canvas(W, H)

    # bed outline, inset so it is not clipped, and a 20 mm grid for scale
    c.rect(1, 1, W - 2, H - 2, BED)
    for g in range(20, int(max(w_mm, h_mm)), 20):
        gx, gy = int(g * scale), int(g * scale)
        for x in range(1, W - 1):
            c.blend(x, gy, GRID, 0.8)
        for y in range(1, H - 1):
            c.blend(gx, y, GRID, 0.8)

    feature = "Custom"
    x = y = 0.0
    layer = 0
    layers_total = 0
    segments = 0
    drawn = 0
    used = set()
    layers = []          # (layer_index, [(x0,y0,x1,y1,feature)])
    cur = []

    with open(path, "r", errors="replace") as f:
        for line in f:
            if line.startswith(";"):
                if "FEATURE:" in line:
                    feature = line.split("FEATURE:", 1)[1].strip()
                    used.add(feature)
                elif line.startswith("; total layer number:"):
                    try:
                        layers_total = int(line.split(":", 1)[1].strip())
                    except ValueError:
                        pass
                elif "LAYER_CHANGE" in line or line.startswith("; CHANGE_LAYER"):
                    if cur:
                        layers.append(cur)
                    cur = []
                    layer += 1
                continue
            if not line.startswith("G1"):
                continue
            nx = ny = None
            e = 0.0
            for tok in line.split():
                if tok.startswith("X"):
                    nx = float(tok[1:])
                elif tok.startswith("Y"):
                    ny = float(tok[1:])
                elif tok.startswith("E"):
                    e = float(tok[1:])
                elif tok.startswith("F"):
                    pass
            if nx is None and ny is None:
                continue
            tx = x if nx is None else nx
            ty = y if ny is None else ny
            if e > 0:
                segments += 1
                if len(cur) < max_segments:
                    cur.append((x, y, tx, ty, feature))
            x, y = tx, ty
    if cur:
        layers.append(cur)

    if not layers:
        return {"segments": 0, "layers": 0, "features": []}

    # everything dim, then the last few layers bright: the shape reads at once
    for li, segs in enumerate(layers):
        is_top = li >= len(layers) - 2
        alpha = 0.85 if is_top else 0.13
        for (x0, y0, x1, y1, feat) in segs:
            col = COLOURS.get(feat, DEFAULT)
            c.line(x0 * scale, (h_mm - y0) * scale, x1 * scale, (h_mm - y1) * scale,
                   col, alpha, width=1)
            drawn += 1

    c.png(out_png)
    return {"segments": segments, "drawn": drawn, "layers": len(layers),
            "layers_reported": layers_total, "features": sorted(used),
            "px": [W, H], "mm": [w_mm, h_mm]}


if __name__ == "__main__":
    import sys
    info = render_gcode(sys.argv[1], sys.argv[2])
    print(info)
