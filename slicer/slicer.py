#!/usr/bin/env python3
"""slicer — slice on the mesh, look at it, then decide to send it.

Three steps, because the third one puts a file on a printer and that should be a
decision, not a side effect:

  1. POST /jobs           upload a model + the settings
  2. GET  /jobs/<id>      slice happens; you get a preview, time, grams, layers
                          (POST /jobs/<id>/reslice to change copies/rotation/scale)
  3. POST /jobs/<id>/upload   only now does the .gcode.3mf go to the card

Nothing here starts a print. Bambu Watch sends `project_file` when a human taps.
"""

import glob
import json
import math
import os
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import render

HOME = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HOME, "app", "squashfs-root")
BIN = os.path.join(APP, "bin", "bambu-studio")
PROFILES = os.path.join(APP, "resources", "profiles", "BBL")
JOBS = os.path.join(HOME, "jobs")
CONFIG_PATH = os.path.join(HOME, "config.json")

LIBS = [
    os.path.join(APP, "usr", "lib"),
    "/nix/store/qzmhqkcmb3ji57f2nf70wqnivnxxck5b-webkitgtk-2.50.0+abi=4.1/lib",
    "/nix/store/lx2q2i8rs3rkw3xks6fapmz3qxjvlx68-ffmpeg-headless-7.1.1-lib/lib",
]

DEFAULTS = {
    "host": "0.0.0.0",
    "port": 8799,
    "machine": "Bambu Lab A1 0.4 nozzle.json",
    "process": "0.20mm Standard @BBL A1.json",
    "filament": "Bambu PLA Basic @BBL A1.json",
    "printer": "192.168.1.50",
    "ftps_port": 990,
    "code_file": "/home/vpavlin/.bambu-code",
    "card_dir": "",        # the ROOT of the card: this is where the printer prints from
    "max_upload_mb": 200,
    "token_file": "",
    "bed_mm": [256.0, 256.0],
}

PARAMS = ("process", "filament", "copies", "rz", "rx", "ry", "scale", "orient", "tray")

LABELS = {
    "process": "quality", "filament": "filament", "copies": "copies",
    "rz": "turn (Z)", "rx": "tilt (X)", "ry": "tilt (Y)",
    "scale": "scale", "orient": "auto-orient",
}


def config():
    cfg = dict(DEFAULTS)
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    return cfg


def presets():
    out = {"machine": [], "process": [], "filament": []}
    for kind in ("machine", "process", "filament"):
        d = os.path.join(PROFILES, kind)
        if not os.path.isdir(d):
            continue
        for n in sorted(os.listdir(d)):
            if not n.endswith(".json") or "template" in n:
                continue
            if kind == "machine" and ("A1 0.4" not in n and n != "Bambu Lab A1.json"):
                continue
            if kind == "process" and ("@BBL A1" not in n or "nozzle" in n or "A1M" in n):
                continue
            if kind == "filament" and ("A1" not in n or "0.2 nozzle" in n):
                continue
            out[kind].append(n)
    return out


def _resolve(name, kind):
    """Bambu presets are an `inherits` chain and the leaf alone is incomplete:
    density lives in @base, so weight came out 0.00 g and the print time was
    fantasy until the chain was merged by hand."""
    cache = {}

    def find(parent):
        if parent not in cache:
            for sub in (kind, "filament", "process", "machine"):
                f = os.path.join(PROFILES, sub, parent + ".json")
                if os.path.exists(f):
                    cache[parent] = f
                    break
        return cache.get(parent)

    def load(path):
        with open(path) as f:
            node = json.load(f)
        base = {}
        if node.get("inherits"):
            pf = find(node["inherits"])
            if pf:
                base = load(pf)
        merged = dict(base)
        for k, v in node.items():
            if k == "inherits":
                continue
            merged[k] = v
        # Bambu keeps a machine's real start/layer/end gcode in "<machine> template
        # machine_start_gcode.json" and similar, marked `instantiation`. The leaf profile
        # only inherits, and the generic parent carries a 22-line stub with NO AMS load —
        # which is why prints never pulled filament from the AMS (2026-11-03). Instantiate
        # them here; the machine's own definitions still win.
        stem = os.path.basename(path)[:-5]
        for tf in sorted(glob.glob(os.path.join(os.path.dirname(path),
                                                glob.escape(stem) + " template *.json"))):
            try:
                with open(tf) as f:
                    tpl = json.load(f)
            except Exception:
                continue
            for k, v in tpl.items():
                if k in ("name", "instantiation", "inherits"):
                    continue
                if k not in node:
                    merged[k] = v
        merged["from"] = "system"        # the CLI rejects an unknown origin
        merged["instantiation"] = "true"
        return merged

    return load(os.path.join(PROFILES, kind, name))


# A Z turn was tried here as a geometry rewrite (rotate the triangles, write a
# binary STL, slice that).  It does not work: this slicer normalises the imported
# object's bounding box, so the turn is undone before slicing.  The code is gone
# rather than left in place looking useful — see `_slice` for what replaced it.


# ---------------------------------------------------------------- card transfer

class Ftps:
    """The printer's FTPS answers USER with a bare 331, which stock clients call
    malformed; six commands by hand. The data connection is retried against the
    control connection's own address — over a mesh name the advertised passive
    port is not routable."""

    def __init__(self, host, port, code):
        self.host, self.port, self.code = host, port, code
        self.ctx = ssl.create_default_context()
        self.ctx.check_hostname = False
        self.ctx.verify_mode = ssl.CERT_NONE

    def _rd(self):
        buf = b""
        while b"\r\n" not in buf:
            c = self.sock.recv(1)
            if not c:
                break
            buf += c
        return buf.decode(errors="replace").strip()

    def _cmd(self, line):
        self.sock.sendall((line + "\r\n").encode())
        return self._rd()

    def _pasv(self):
        r = self._cmd("PASV")
        m = re.search(r"(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)", r)
        if not m:
            raise IOError("PASV refused: %s" % r)
        h = ".".join(m.groups()[:4])
        p = int(m.group(5)) * 256 + int(m.group(6))
        last = None
        for addr in (h, self.peer):
            try:
                return self.ctx.wrap_socket(
                    socket.create_connection((addr, p), timeout=15), server_hostname=self.host)
            except Exception as e:
                last = e
        raise IOError("data connection failed: %s" % last)

    def __enter__(self):
        raw = socket.create_connection((self.host, self.port), timeout=15)
        self.sock = self.ctx.wrap_socket(raw, server_hostname=self.host)
        self.peer = self.sock.getpeername()[0]
        self._rd()
        for c in ("USER bblp", "PASS " + self.code, "PBSZ 0", "PROT P", "TYPE I"):
            r = self._cmd(c)
            if c.startswith("PASS"):
                continue
            if r[:3] not in ("200", "331", "230"):
                raise IOError("%s -> %s" % (c.split()[0], r))
        return self

    def __exit__(self, *a):
        try:
            self.sock.close()
        except Exception:
            pass

    def upload(self, local, remote):
        d = self._pasv()
        r = self._cmd("STOR " + remote)
        if not r.startswith("150"):
            raise IOError("STOR refused: %s" % r)
        sent = 0
        with open(local, "rb") as f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                d.sendall(chunk)
                sent += len(chunk)
        d.close()
        done = self._rd()
        if not done.startswith("226"):
            raise IOError("transfer did not finish: %s" % done)
        return sent

    def listing(self, path):
        d = self._pasv()
        self._cmd("LIST " + path)
        buf = b""
        while True:
            b = d.recv(65536)
            if not b:
                break
            buf += b
        d.close()
        self._rd()
        return buf.decode(errors="replace").splitlines()

    def delete(self, remote):
        return self._cmd("DELE " + remote)


# ---------------------------------------------------------------- jobs

def _path(job_id):
    return os.path.join(JOBS, job_id, "job.json")


def _read_job(job_id):
    p = _path(job_id)
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return json.load(f)


def _write_job(job):
    job["updated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    p = _path(job["id"])
    tmp = p + ".tmp"
    with open(tmp, "w") as f:
        json.dump(job, f, indent=1)
    os.replace(tmp, p)


def _log(job, line):
    job["log"].append(line)
    job["log"] = job["log"][-200:]
    print("[%s] %s" % (job["id"][:8], line), flush=True)
    _write_job(job)


def _stats(gcode):
    """Time, filament and layers — from the gcode header, which is what the
    printer will actually report, rather than from a result file."""
    out = {}
    try:
        with open(gcode, "rb") as f:
            head = f.read(200 * 1024).decode(errors="replace")
    except OSError:
        return out
    m = re.search(r"total estimated time:\s*(?:(\d+)h\s*)?(?:(\d+)m\s*)?(?:(\d+)s)?", head)
    if m and any(m.groups()):
        h, mi, se = (int(x) if x else 0 for x in m.groups())
        out["seconds"] = h * 3600 + mi * 60 + se
    m = re.search(r"total filament weight \[g\]\s*:\s*([\d.]+)", head)
    if m:
        out["grams"] = round(float(m.group(1)), 2)
    m = re.search(r"total filament length \[mm\]\s*:\s*([\d.]+)", head)
    if m:
        out["metres"] = round(float(m.group(1)) / 1000, 2)
    m = re.search(r"total layer number:\s*(\d+)", head)
    if m:
        out["layers"] = int(m.group(1))
    return out


def _slice(job):
    params = job["params"]
    resolved = {}
    for kind, name in (("machine", job["machine"]),
                       ("process", params.get("process") or config()["process"]),
                       ("filament", params.get("filament") or config()["filament"])):
        src = os.path.join(PROFILES, kind, name)
        if not os.path.exists(src):
            raise IOError("preset missing: %s" % name)
        dst = os.path.join(job["dir"], "resolved_%s.json" % kind)
        with open(dst, "w") as f:
            json.dump(_resolve(name, kind), f)
        resolved[kind] = dst

    p = params
    rx, ry, sc = (float(p.get("rx") or 0), float(p.get("ry") or 0),
                  float(p.get("scale") or 1))
    copies = max(1, int(p.get("copies") or 1))
    rz = float(p.get("rz") or 0)
    if rz:
        # Not a limitation to be papered over: measured, twice.
        #   - baked into the STL: the importer normalises the bounding box, so
        #     a 30 degree turn comes back as the original rectangle.
        #   - `--rotate 30`: the angle lands in the project's <item transform>
        #     but the toolpath is byte-identical to no rotation.
        raise IOError("a Z turn is not applied by this slicer build (measured: "
                      "project transform changes, toolpath does not). Use a "
                      "sideways tilt instead, or turn it in your CAD.")
    pre = []
    if rx:
        pre += ["--rotate-x", str(rx)]
    if ry:
        pre += ["--rotate-y", str(ry)]

    out = os.path.join(job["dir"], "out")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    env = dict(os.environ)
    env["PATH"] = os.path.join(HOME, "bin") + ":" + env.get("PATH", "/usr/bin:/bin")
    env["LD_LIBRARY_PATH"] = ":".join(LIBS + [env.get("LD_LIBRARY_PATH", "")]).strip(":")

    name = job["name"] + ".gcode.3mf"
    cmd = [ "xvfb-run", "-a", BIN,
        "--datadir", os.path.join(job["dir"], "datadir"),
        "--load-settings", "%s;%s" % (resolved["machine"], resolved["process"]),
        "--load-filaments", resolved["filament"],
        "--slice", "0", "--export-3mf", name, "--outputdir", out,
        "--ensure-on-bed",          # without this a rotated model is silently cut
    ]
    if copies > 1:
        cmd += ["--clone-objects", str(copies)]
    if str(p.get("orient", "")).lower() in ("1", "true", "on"):
        cmd += ["--orient", "1"]
    if sc and sc != 1:
        cmd += ["--scale", str(sc)]
    cmd += pre
    cmd.append(job["input"])

    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=2400, env=env, cwd=job["dir"])
    took = round(time.time() - t0, 1)
    three = os.path.join(out, name)
    noise = [l for l in (r.stdout + r.stderr).splitlines()
             if re.search(r"error|invalid|fail|unable", l, re.I)
             and not re.search(r"wayland|glfw", l, re.I)]
    for l in noise[:3]:
        _log(job, "slicer: " + l.strip()[:150])
    if not os.path.exists(three):
        raise IOError("no 3mf produced (exit %s, %.1fs)" % (r.returncode, took))
    if config().get("strip_vibration", True):
        try:
            n = strip_vibration(three)
            _log(job, f"removed {n} resonance commands from the start sequence")
        except Exception as e:
            _log(job, f"could not strip the vibration commands: {e}")
    with zipfile.ZipFile(three) as z:
        if "Metadata/plate_1.gcode" not in z.namelist():
            raise IOError("3mf has no Metadata/plate_1.gcode — not a sliced project")
    job["three_mf"] = three
    job["estimate"] = _stats(os.path.join(out, "plate_1.gcode"))
    job["preview"] = None
    png = os.path.join(job["dir"], "preview.png")
    try:
        info = render.render_gcode(os.path.join(out, "plate_1.gcode"), png,
                                   bed=tuple(config().get("bed_mm") or (256.0, 256.0)))
        if info.get("segments"):
            job["preview"] = png
            job["drawn"] = {"segments": info["segments"], "layers": info["layers"]}
    except Exception as e:
        _log(job, "preview failed: %s" % str(e)[:120])
    _log(job, "sliced in %.1fs — %s / %s%s" % (
        took, p["process"], p["filament"],
        ", %d copies" % copies if copies > 1 else ""))


def _ams_mapping(tray):
    """'' -> [] for the AMS slot choice.

    Empty mapping means "printer, you decide", which with a mixed AMS is a lottery: PETG in
    slot 0 and PLA in 1-3 means a PLA job can end up printing on the PETG spool.
    """
    tray = (tray or "").strip()
    if tray in ("", "auto"):
        return ""
    try:
        n = int(tray)
    except ValueError:
        raise IOError("tray must be a slot number, got %r" % tray)
    if not 0 <= n <= 3:
        raise IOError("tray %d is not an AMS slot (0-3)" % n)
    return [n]


def build_payload(job, base, tray=None):
    """The project_file command. Pure: no network, so it can be inspected before sending.

    use_ams=false is NOT "the printer decides" — it tells the printer the filament is already
    at the nozzle, and then nothing is loaded (2026-11-03). A slot must be chosen.
    """
    tray = (tray if tray is not None else (job["params"].get("tray") or "")).strip()
    mapping = _ams_mapping(tray)
    if not mapping:
        _log(job, "WARNING: no AMS slot chosen — the printer is told use_ams=false, so it will "
                  "NOT load filament. Pick a slot if the toolhead is empty.")
    return {"print": {
        "sequence_id": "1", "command": "project_file",
        "param": "Metadata/plate_1.gcode", "subtask_name": base,
        "url": "ftp:///" + base, "file": "",
        "project_id": "0", "profile_id": "0", "task_id": "0", "subtask_id": "0",
        "bed_type": "auto", "bed_levelling": True, "flow_cali": True,
        "vibration_cali": False,   # never: he does not want the resonance sweep
        "ams_mapping": mapping,
        "use_ams": bool(mapping),
    }}


def _upload(job):
    cfg = config()
    base_name = os.path.basename(job["three_mf"])
    remote = "%s/%s" % (cfg.get("card_dir") or "", base_name)   # "" -> "/name", the card root
    code = open(cfg["code_file"]).read().strip()
    with Ftps(cfg["printer"], cfg["ftps_port"], code) as ftp:
        sent = ftp.upload(job["three_mf"], remote)
        lines = ftp.listing(cfg["card_dir"])
    base = os.path.basename(remote)
    if not any(base in l for l in lines):
        raise IOError("uploaded %d bytes but %s is not in the listing" % (sent, base))
    job["card_path"] = remote
    job["payload"] = build_payload(job, base)
    _log(job, "on the card: %s (%d bytes)%s" % (
        remote, sent, (", AMS slot %s" % tray) if mapping != "" else ", AMS: printer's choice"))


QUEUE = []
QLOCK = threading.Lock()
CURRENT = {"id": None, "action": None}


def _work(job, action):
    try:
        if action == "slice":
            job["state"] = "slicing"
            _write_job(job)
            _slice(job)
            job["state"] = "sliced"
        elif action == "upload":
            job["state"] = "uploading"
            _write_job(job)
            _upload(job)
            job["state"] = "ready"
    except Exception as e:
        job["state"] = "failed"
        job["error"] = "%s: %s" % (type(e).__name__, str(e)[:300])
        _log(job, job["error"])
    _write_job(job)


def worker():
    while True:
        with QLOCK:
            item = QUEUE.pop(0) if QUEUE else None
        if not item:
            time.sleep(0.4)
            continue
        job_id, action = item
        job = _read_job(job_id)
        if not job:
            continue
        CURRENT["id"], CURRENT["action"] = job_id, action
        try:
            _work(job, action)
        finally:
            CURRENT["id"] = CURRENT["action"] = None


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "slicer/2"

    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % a))

    def _json(self, obj, code=200):
        body = json.dumps(obj, indent=1).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, body, code=200):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _allowed(self):
        tf = config().get("token_file") or ""
        if not tf or not os.path.exists(tf):
            return True
        want = open(tf).read().strip()
        got = (self.headers.get("X-Slicer-Token") or "").strip()
        if not got:
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            got = (q.get("token") or [""])[0]
        return got == want

    # -- GET
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        q = {k: v for k, v in urllib.parse.parse_qs(u.query).items()}
        parts = [x for x in path.split("/") if x]
        if path == "/":
            return self._html(PAGE.replace("__PRESETS__", json.dumps(presets()))
                                  .replace("__DEFAULTS__", json.dumps(
                                      {k: config()[k] for k in ("process", "filament")})))
        if parts == ["presets"]:
            return self._json({"defaults": {k: config()[k] for k in
                                            ("machine", "process", "filament")},
                               "available": presets()})
        if parts == ["jobs"]:
            out = []
            for j in sorted(os.listdir(JOBS), reverse=True)[:50]:
                job = _read_job(j)
                if job:
                    out.append(_view(job, full=False))
            return self._json({"jobs": out, "current": CURRENT})
        if len(parts) == 2 and parts[0] == "jobs":
            job = _read_job(parts[1])
            if not job:
                return self._json({"error": "no such job"}, 404)
            return self._json(_view(job))
        if len(parts) == 3 and parts[0] == "jobs":
            job = _read_job(parts[1])
            if not job:
                return self._json({"error": "no such job"}, 404)
            what = parts[2]
            if what == "preview.png":
                p = job.get("preview")
                if not p or not os.path.exists(p):
                    return self._json({"error": "no preview"}, 404)
                data = open(p, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if what == "payload":
                # Read-only: build the print command for a given slot and show it. Nothing is
                # uploaded and nothing is printed — this is how you check before pressing send.
                base = os.path.basename(job.get("three_mf") or "x.3mf")
                tray = q.get("tray", [None])[0]
                return self._json({"would_send": build_payload(job, base, tray),
                                   "tray": tray if tray is not None else job["params"].get("tray", "")})
            if what == "toolpath.bin":
                gcode = os.path.join(job["dir"], "out", "plate_1.gcode")
                if not os.path.exists(gcode):
                    return self._json({"error": "not sliced"}, 404)
                import gzip
                layers, feats, total = render.parse_layers(gcode)
                data, stride, kept = render.toolpath_bin(
                    layers, feats, tuple(config().get("bed_mm") or (256.0, 256.0)),
                    int(q.get("max", ["160000"])[0]))
                body = gzip.compress(data, 1)
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Encoding", "gzip")
                self.send_header("X-Toolpath-Moves", str(total))
                self.send_header("X-Toolpath-Stride", str(stride))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if what == "download":
                p = job.get("three_mf")
                if not p or not os.path.exists(p):
                    return self._json({"error": "not sliced"}, 404)
                data = open(p, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition",
                                 'attachment; filename="%s"' % os.path.basename(p))
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
        return self._json({"error": "not found"}, 404)

    # -- POST
    def do_POST(self):
        if not self._allowed():
            return self._json({"error": "bad or missing token"}, 401)
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        parts = [x for x in u.path.split("/") if x]

        if parts == ["jobs"]:
            return self._create(q)
        if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "reslice":
            job = _read_job(parts[1])
            if not job:
                return self._json({"error": "no such job"}, 404)
            for k in PARAMS:
                if k in q:
                    job["params"][k] = q[k]
            job["state"] = "queued"
            job["error"] = None
            _write_job(job)
            with QLOCK:
                QUEUE.append((job["id"], "slice"))
            return self._json({"job": job["id"], "state": "queued",
                               "params": job["params"]}, 202)
        if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "upload":
            job = _read_job(parts[1])
            if not job:
                return self._json({"error": "no such job"}, 404)
            if not job.get("three_mf") or not os.path.exists(job["three_mf"]):
                return self._json({"error": "not sliced yet"}, 409)
            # The AMS slot is a field of the print command, not of the gcode, so a slot
            # chosen after slicing must be honoured here — otherwise the job keeps the
            # "" it was sliced with and the printer never loads filament (2026-11-03).
            if "tray" in q:
                job["params"]["tray"] = (q.get("tray") or [""])[0]
                _write_job(job)
            job["state"] = "queued"
            job["error"] = None
            _write_job(job)
            with QLOCK:
                QUEUE.append((job["id"], "upload"))
            return self._json({"job": job["id"], "state": "queued"}, 202)
        return self._json({"error": "not found"}, 404)

    def _create(self, q):
        cfg = config()
        job_id = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
        d = os.path.join(JOBS, job_id)
        os.makedirs(d, exist_ok=True)
        params = {k: (q.get(k) if q.get(k) is not None else cfg.get(k))
                  for k in ("process", "filament")}
        for k in ("copies", "rz", "rx", "ry", "scale", "orient", "tray"):
            params[k] = q.get(k, "" if k != "copies" else "1")
        job = {"id": job_id, "dir": d, "state": "queued", "log": [],
               "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "machine": cfg["machine"], "params": params}
        ctype = self.headers.get("Content-Type", "")
        try:
            if ctype.startswith("multipart/form-data"):
                body, orig = self._read_multipart(d)
                job["name"] = _clean(q.get("name") or os.path.splitext(orig)[0])
                job["input"] = body
                job["source"] = orig
            elif q.get("url"):
                job["source_url"] = q["url"]
                job["name"] = _clean(q.get("name") or "model")
            else:
                shutil.rmtree(d, ignore_errors=True)
                return self._json({"error": "send a file (multipart) or ?url="}, 400)
        except Exception as e:
            shutil.rmtree(d, ignore_errors=True)
            return self._json({"error": "could not accept the upload: %s" % e}, 400)
        if job.get("source_url"):
            try:
                self._fetch(job)
            except Exception as e:
                shutil.rmtree(d, ignore_errors=True)
                return self._json({"error": "could not fetch %s: %s" % (job["source_url"], e)}, 400)
        _write_job(job)
        with QLOCK:
            QUEUE.append((job_id, "slice"))
        return self._json({"job": job_id, "state": "queued"}, 202)

    def _fetch(self, job):
        req = urllib.request.Request(job["source_url"], headers={
            "User-Agent": "Mozilla/5.0 (slicer on proteus)"})
        with urllib.request.urlopen(req, timeout=120) as r:
            name = os.path.basename(urllib.parse.urlparse(r.geturl()).path) or "model"
            ext = os.path.splitext(name)[1].lower()
            if ext not in (".stl", ".3mf", ".obj", ".step", ".stp"):
                ext = ".stl"
            dst = os.path.join(job["dir"], "input" + ext)
            n = 0
            limit = config()["max_upload_mb"] * 1024 * 1024
            with open(dst, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    n += len(chunk)
                    if n > limit:
                        raise IOError("larger than %d MB" % config()["max_upload_mb"])
                    f.write(chunk)
        fixed, kind = _fix_extension(dst)
        job["input"] = fixed
        job["source"] = name
        job["detected"] = kind

    def do_DELETE(self):
        if not self._allowed():
            return self._json({"error": "bad or missing token"}, 401)
        parts = [x for x in urllib.parse.urlparse(self.path).path.split("/") if x]
        if len(parts) != 2 or parts[0] != "jobs":
            return self._json({"error": "not found"}, 404)
        job = _read_job(parts[1])
        if not job:
            return self._json({"error": "no such job"}, 404)
        removed = None
        if job.get("card_path"):
            try:
                cfg = config()
                code = open(cfg["code_file"]).read().strip()
                with Ftps(cfg["printer"], cfg["ftps_port"], code) as ftp:
                    removed = ftp.delete(job["card_path"])
            except Exception as e:
                removed = "delete failed: %s" % e
        shutil.rmtree(job["dir"], ignore_errors=True)
        return self._json({"deleted": job["id"], "card": removed})

    # -- multipart: one file field, streamed to disk
    def _read_multipart(self, d):
        boundary = None
        for part in self.headers.get("Content-Type", "").split(";"):
            part = part.strip()
            if part.startswith("boundary="):
                boundary = part.split("=", 1)[1].strip('"')
        if not boundary:
            raise IOError("no multipart boundary")
        lim = b"--" + boundary.encode()
        remaining = int(self.headers.get("Content-Length") or 0)
        if remaining <= 0:
            raise IOError("empty body")
        maxb = config()["max_upload_mb"] * 1024 * 1024
        buf = b""
        while lim not in buf:
            chunk = self.rfile.read(min(8192, remaining))
            if not chunk:
                raise IOError("malformed multipart")
            remaining -= len(chunk)
            buf += chunk
        buf = buf.split(lim, 1)[1]
        while b"\r\n\r\n" not in buf:
            chunk = self.rfile.read(min(8192, remaining))
            if not chunk:
                raise IOError("malformed part headers")
            remaining -= len(chunk)
            buf += chunk
        head, buf = buf.split(b"\r\n\r\n", 1)
        name = "model.stl"
        m = re.search(rb'filename="?([^"\r\n;]+)', head)
        if m:
            name = os.path.basename(m.group(1).decode(errors="replace"))
        ext = os.path.splitext(name)[1].lower()
        if ext not in (".stl", ".3mf", ".obj", ".step", ".stp"):
            ext = ".stl"
        dst = os.path.join(d, "input" + ext)
        written = 0
        trailer = b"\r\n--" + boundary.encode()
        with open(dst, "wb") as f:
            while True:
                i = buf.find(trailer)
                if i >= 0:
                    f.write(buf[:i])
                    written += i
                    break
                keep = len(trailer) - 1
                if len(buf) > keep:
                    f.write(buf[:-keep])
                    written += len(buf) - keep
                    buf = buf[-keep:]
                if remaining <= 0:
                    raise IOError("upload ended before the boundary")
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    raise IOError("upload ended before the boundary")
                remaining -= len(chunk)
                buf += chunk
                if written > maxb:
                    raise IOError("file larger than %d MB" % config()["max_upload_mb"])
        if written == 0:
            raise IOError("no file bytes")
        fixed, kind = _fix_extension(dst)
        return fixed, name


# --- start-sequence surgery -------------------------------------------------
# The A1 executes six M970/M974 commands at the start of every print, which sweep
# the axes to measure belt tension and resonance. It costs minutes per print and
# Václav does not want it (2026-11-03). Removing them skips the sweep; the stored
# calibration from the touchscreen menu still applies, and he can re-run the sweep
# by hand whenever he wants the belt check. The 3MF carries the gcode's md5, so it
# has to be recomputed or the printer refuses the file.
VIBRATION_CMDS = re.compile(r"^M97[04]\b.*$", re.M)


def strip_vibration(three):
    """Rewrite the plate gcode inside a 3MF without the resonance commands."""
    import hashlib
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp(prefix="strip-")
    out = os.path.join(tmp, os.path.basename(three))
    dropped = 0
    entries = []
    with zipfile.ZipFile(three) as zin:              # read everything first: a zip cannot be
        for item in zin.infolist():                  # read while another handle writes it
            entries.append((item, zin.read(item.filename)))

    kept, checksums = [], []
    for item, data in entries:
        name = item.filename
        if name.endswith(".gcode"):
            text = data.decode("utf-8", "replace")
            text, n = VIBRATION_CMDS.subn("", text)
            dropped += n
            data = text.encode("utf-8")
            checksums.append((name + ".md5", hashlib.md5(data).hexdigest().upper().encode()))
        elif name.endswith(".gcode.md5"):
            continue                                  # stale once the gcode changes
        kept.append((item, data))

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item, data in kept:
            zout.writestr(item, data)
        for name, digest in checksums:
            zout.writestr(name, digest)

    with zipfile.ZipFile(out) as check:               # prove it before it replaces the original
        names = check.namelist()
        if "Metadata/plate_1.gcode" not in names:
            raise IOError("rewritten 3mf lost its gcode")
    shutil.move(out, three)
    shutil.rmtree(tmp, ignore_errors=True)
    return dropped


def _sniff(path):
    """What this file actually is. A browser sends `part.3mf`; Android's document picker
    can send a bare number, and guessing `.stl` from that fed a zip to the STL reader."""
    try:
        with open(path, "rb") as f:
            head = f.read(2048)
            f.seek(0)
            size = os.path.getsize(path)
    except OSError:
        return None
    if head[:2] == b"PK":
        return ".3mf"                      # 3MF is a zip
    if head[:5] == b"solid" and b"facet" in head:
        return ".stl"                      # ascii STL
    if head[:31].lstrip().startswith(b"ISO-10303-21"):
        return ".step"
    if head[:2] in (b"v ", b"# ") or b"\nv " in head[:2048]:
        return ".obj"
    # binary STL: 80-byte header, uint32 count, then 50 bytes per triangle
    if size > 84:
        n = struct.unpack("<I", head[80:84])[0]
        if 0 < n and size == 84 + 50 * n:
            return ".stl"
    return None


def _fix_extension(path):
    """Rename to what the bytes say, and report what was decided."""
    want = _sniff(path)
    have = os.path.splitext(path)[1].lower()
    if want and want != have:
        new = os.path.splitext(path)[0] + want
        os.replace(path, new)
        return new, want
    return path, have or "?"


def _clean(s):
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", (s or "model")).strip("._-") or "model"
    return stem[:60]


def _view(job, full=True):
    v = {k: job.get(k) for k in
         ("id", "name", "state", "created", "params", "estimate", "card_path",
          "error", "source")}
    v["preview"] = bool(job.get("preview"))
    if full:
        v["log"] = job.get("log", [])
        v["payload"] = job.get("payload")
        v["drawn"] = job.get("drawn")
        v["can"] = {
            "reslice": job["state"] in ("sliced", "ready", "failed"),
            "upload": job["state"] == "sliced",
        }
    return v


PAGE = open(os.path.join(HOME, "page.html")).read() if os.path.exists(
    os.path.join(HOME, "page.html")) else "<html><body><h2>slicer</h2><p>page.html missing</p></body></html>"


def main():
    os.makedirs(JOBS, exist_ok=True)
    cfg = config()
    for d in (APP, BIN, PROFILES):
        if not os.path.exists(d):
            sys.exit("slicer: missing %s" % d)
    threading.Thread(target=worker, daemon=True).start()
    srv = ThreadingHTTPServer((cfg["host"], cfg["port"]), Handler)
    print("slicer: %s:%d, %d presets, printer %s"
          % (cfg["host"], cfg["port"], sum(len(v) for v in presets().values()),
             cfg["printer"]), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
