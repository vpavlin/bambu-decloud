"""Publish the active FreeCAD document to the LAN WebDAV drop, optionally slicing it.

Two halves on purpose: publish_file() touches no FreeCAD API and can be tested from
plain Python; publish_document() is the part that needs a document open.

Config: ~/.config/freecad-publish.json
    { "webdav": "http://files.pi5.office.mesh:5000",
      "collection": "projects/models",
      "slicer": "http://slicer.proteus.office.mesh",
      "format": "3mf" }
"""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

CONFIG_PATH = os.path.expanduser("~/.config/freecad-publish.json")
DEFAULTS = {
    "webdav": "http://files.pi5.office.mesh:5000",
    "collection": "projects/models",
    "slicer": "http://slicer.proteus.office.mesh",
    "format": "3mf",
}


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    except FileNotFoundError:
        pass
    except Exception as e:                     # a broken config must not block publishing
        print("publish: config unreadable (%s), using defaults" % e)
    return cfg


def slug(name):
    name = re.sub(r"[^A-Za-z0-9._-]+", "-", name).strip("-")
    return name or "model"


def _request(url, method="GET", data=None, timeout=120):
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/octet-stream")
    return urllib.request.urlopen(req, timeout=timeout)


def ensure_collection(url):
    """WebDAV MKCOL, walking up so a nested collection can be created in one go."""
    parts = urllib.parse.urlsplit(url)
    path = parts.path.rstrip("/")
    segs = [s for s in path.split("/") if s]
    cur = "%s://%s" % (parts.scheme, parts.netloc)
    for s in segs:
        cur += "/" + s
        try:
            _request(cur + "/", "MKCOL", timeout=20)
        except urllib.error.HTTPError as e:
            if e.code not in (301, 405):        # 405 = already exists, the normal case
                raise


def publish_file(path, collection=None, name=None):
    """PUT a local file into the WebDAV collection. Returns (url, size)."""
    cfg = load_config()
    coll = (collection or cfg["collection"]).strip("/")
    base = cfg["webdav"].rstrip("/")
    fname = slug(name or os.path.basename(path))
    url = "%s/%s/%s" % (base, coll, urllib.parse.quote(fname))
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        data = f.read()
    for attempt in (1, 2):
        try:
            r = _request(url, "PUT", data=data, timeout=600)
            if r.status not in (200, 201, 204):
                raise RuntimeError("PUT %s -> HTTP %s" % (url, r.status))
            break
        except urllib.error.HTTPError as e:
            if attempt == 1 and e.code in (404, 409):
                ensure_collection("%s/%s/" % (base, coll))   # first publish into a new folder
                continue
            raise
    # verify against what the server serves, not against what we think we sent
    with _request(url, "HEAD", timeout=30) as h:
        served = int(h.headers.get("Content-Length") or -1)
    if served != size:
        raise RuntimeError("served size %s != local %s (%s)" % (served, size, url))
    return url, size


def create_slice_job(url):
    """Ask the slicer to fetch that URL and slice it. Returns the job id."""
    cfg = load_config()
    slicer = cfg["slicer"].rstrip("/")
    q = urllib.parse.quote(url, safe="")
    with _request("%s/jobs?url=%s" % (slicer, q), "POST", data=b"", timeout=120) as r:
        body = json.loads(r.read().decode() or "{}")
    return body.get("job") or body.get("id")


def printable_objects(doc):
    """The solids worth exporting: bodies and standalone shapes.

    Passing doc.Objects blindly hands Import the Origin, axes and planes, whose shapes are
    null — Import logs 'has null shape' and can write nothing at all (found 2026-11-03).
    """
    out = []
    for o in doc.Objects:
        if o.TypeId == "PartDesign::Body":
            out.append(o)
            continue
        shape = getattr(o, "Shape", None)
        if shape is None or shape.isNull():
            continue
        if o.InList:                      # consumed by a Body/feature above it
            continue
        out.append(o)
    return out


def geometry_note(objects):
    """Say something when the model is suspiciously small — metres instead of millimetres."""
    box = None
    for o in objects:
        b = getattr(getattr(o, "Shape", None), "BoundBox", None)
        if b is not None:
            box = b if box is None else box.united(b)
    if box is None:
        return ""
    dims = (box.XLength, box.YLength, box.ZLength)
    biggest = max(dims)
    if biggest < 5:
        return ("this model is %.1f mm at its largest — a thousand times smaller than usual. "
                "If it was exported in metres, scale it by 1000 before printing." % biggest)
    if biggest > 300:
        return ("this model is %.0f mm at its largest, larger than a 256 mm plate — "
                "scale it down and reslice." % biggest)
    return "%.1f x %.1f x %.1f mm" % dims


def publish_document(fmt=None, create_job=False):
    """Export the active document and publish it. Returns (url, size, job_id_or_None)."""
    import FreeCAD
    import Import                                   # FreeCAD's own import/export module
    cfg = load_config()
    fmt = (fmt or cfg["format"]).lstrip(".").lower()
    doc = FreeCAD.ActiveDocument
    if doc is None:
        raise RuntimeError("no document open")
    out = os.path.join(FreeCAD.getUserCachePath(), "publish")
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, "%s.%s" % (slug(doc.Name), fmt))
    objs = printable_objects(doc)
    if not objs:
        raise RuntimeError("nothing to export: no solid shapes in this document")
    note = geometry_note(objs)

    # Mesh.export writes 3MF and STL from shapes; Import.export has no 3MF writer at all
    # (it returns quietly and writes nothing), so STEP is the only format that needs it.
    if fmt == "step":
        Import.export(objs, path)
    else:
        import Mesh
        Mesh.export(objs, path)
        if not (os.path.exists(path) and os.path.getsize(path) > 0):
            # 3MF unsupported in this build: STL is the format that always works
            print("publish: %s produced no file, falling back to binary STL" % fmt)
            path = os.path.join(out, "%s.stl" % slug(doc.Name))
            Mesh.export(objs, path)

    # A silent non-write is the failure mode this whole function has to survive.
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError("export wrote no file (%s) — nothing published" % path)

    url, size = publish_file(path)
    job = create_slice_job(url) if create_job else None
    print("publish: %s (%d bytes) %s%s" % (url, size, note, (" job " + str(job)) if job else ""))
    return url, size, job
