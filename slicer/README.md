# slicer

Slices a model for the Bambu A1 on the mesh and puts the result on the
printer's own card, so printing does not need a computer in the room.

    meter.stl  ──►  bambu-studio (headless)  ──►  *.gcode.3mf  ──►  FTPS /cache/

It stops there. **Nothing here starts a print**: a job reaches `ready` with the
file on the card and the MQTT payload the printer needs. Bambu Watch sends
`project_file` when a human taps.

Address: **`http://slicer.proteus.office.mesh`** — no port. Served by
`slicer.service` in the user session, enabled and lingering, so it comes back
after a reboot.

**Use the portless name, not `:8799`.** The daemon forwards the service name from
its port-80 listener to this process, and port 80 is on the host's firewalld
allowlist. Port 8799 is *not*: the daemon does open a forwarder on the mesh
address for it (visible as `[fd00::1]:8799 LISTEN by 'shrooms'`), but the host
firewall drops the SYN, so from any other machine `:8799` gives a 5-second
timeout rather than a refusal. A 200 from `curl` on proteus itself proves
nothing — it never crosses the firewall. If the bare port is ever wanted, add a
rich rule for it; the portless name is the supported path.

## The three steps

    POST /jobs            upload the model + settings   -> job id, state queued
    GET  /jobs/<id>       slices; then you get preview, time, grams, metres, layers
    POST /jobs/<id>/upload  ONLY NOW does the .gcode.3mf go to the printer's card

Nothing is sent to the printer until that third call, and nothing ever starts a
print — the job ends `ready` with the full `project_file` payload for Bambu Watch
to send when a human taps.

    GET  /                      the page: upload, look, confirm
    GET  /presets               what this install can slice with
    GET  /jobs                  recent jobs
    GET  /jobs/<id>             one job: state, estimate, params, log, payload
    GET  /jobs/<id>/preview.png top-down picture of the toolpath
    GET  /jobs/<id>/download    the sliced .gcode.3mf itself
    POST /jobs/<id>/reslice?copies=2&rx=15   slice the same upload again
    DELETE /jobs/<id>           forget it, and remove its file from the card

Params on `POST /jobs` and `reslice`: `process`, `filament`, `copies`, `rx`,
`ry`, `scale`, `orient`.

## What the slicer will and will not do

Measured on this install, not assumed:

| setting | result |
|---|---|
| `copies` → `--clone-objects N` | **works.** N is the total number of copies (2 → 8.63 g, 3 → 12.89 g for a 4.36 g part) |
| `scale` → `--scale` | **works** (1.5 → 10.41 g) |
| `rx` / `ry` → `--rotate-x/-y` | **works**: a 60 mm tower laid over 30° slices as 321 layers with generated support |
| `orient` → `--orient 1` | works, but leave it off: it re-orients the model to whatever it thinks is flattest |
| **`rz`, a Z turn** | **does not work, and is refused with an explanation.** Two ways tried: (a) rotate the triangles and write a new binary STL — the importer normalises the bounding box, so a 30° turn comes back as the original rectangle; (b) `--rotate 30` — the angle appears in the project's `<item transform="0.866 -0.5 …">` but the toolpath is **byte-identical** to no rotation (`--rotate 90` on a square tower has the same gcode sha256 as `--rotate 0`). Turn it in CAD. |

**`--ensure-on-bed` is always passed.** Without it a rotated model is silently
clipped: `--rotate 45` on a 50-layer cube produced **25 layers and 2.93 g** — half
the part gone, no warning, a plausible-looking gcode.

**No `--arrange`.** It overrides rotation and re-places objects; clones are placed
well enough without it.

## The 3D view

The page draws the toolpath itself, on a canvas, with drag-to-turn and
wheel/pinch-to-zoom, plus `iso` / `top` / `front` / `fit` buttons. It is real
geometry, not a picture: it reads `GET /jobs/<id>/toolpath.bin`, a compact
stream of per-layer segments (uint16 at 0.01 mm, one feature byte each), 680 KB
raw and about **177 KB gzipped** for 77,000 moves.

Two things worth knowing if you touch `page.html`:

- **The stream stores all the coordinates, then all the feature bytes.** Not
  interleaved. Reading it interleaved produces plausible-looking spaghetti.
- **The viewer projects in plain code**, one `project(x,y,z,out)` used for
  everything. An earlier version used canvas `setTransform` with one affine per
  layer (elegant: a rotation maps a plane to a plane) but the sign conventions
  of `Rx·Rz` hidden inside a matrix are easy to get wrong and hard to see; two
  rounds of "the camera looks fine" were actually wrong. Explicit maths, verified
  against printed corner coordinates, was faster in the end.
- **Which is 0° pitch:** `camPitch` 0 looks straight down, 1.45 is a front
  elevation. `Rx(pitch)·Rz(yaw)`, screen y down. **Drag down = pull the camera
  up over the model**, as CAD and Bambu Studio do; the first version had it the
  other way and read as reversed.
- The fit ignores layer 0, where the purge line lives, or a 25 mm part is
  framed inside a 130 × 180 mm rectangle.

## The flat preview

`--export-png` is not usable here: the AppImage is built for Wayland and carries
no GL, so it dies with `glfwInit return error, code 65544` under Xvfb (and it
refuses to run in the same pass as `--slice`: *"should not set export_png with
other actions together"*). So `render.py` draws the **gcode** with no
dependencies — a hand-written PNG encoder, walls in blue, top surfaces amber,
supports green, bed and 20 mm grid for scale. 6,000 moves render in ~0.3 s.

## Presets

`GET /presets` lists what the bundled Bambu Studio accepts for a stock A1 with a
0.4 nozzle: 10 process presets (0.08 Extra Fine … 0.28 Extra Draft) and 151
filaments. Per job, override either:

    curl -F file=@part.stl \
      "http://slicer.proteus.office.mesh:8799/slice?process=0.16mm%20High%20Quality%20@BBL%20A1.json&filament=Bambu%20PETG%20HF%20@BBL%20A1.json"

The default lives in `config.json` (`process`, `filament`, `machine`). Changing
it is one line and a `systemctl --user restart slicer`.

**Presets are a chain of `inherits` files and the child alone is not usable.**
Loading `Bambu PLA Basic @BBL A1.json` by itself slices happily and lies: no
density (weight `0.00 g`), wrong print time (`31m` where the truth was `8m`), and
no AMS tray id. `_resolve()` walks `@BBL A1 → @base → fdm_filament_pla`, merges,
and writes one resolved file per job. It also sets `from = "system"`, without
which the slicer refuses the file with *"file …'s from unsupported"*.

## Configuration — `config.json`

| key | meaning |
|---|---|
| `host`, `port` | where to listen (`0.0.0.0:8799`) |
| `machine`, `process`, `filament` | the default preset per job |
| `printer`, `ftps_port`, `card_dir` | `192.168.1.50`, `990`, `/cache` |
| `code_file` | the printer's access code, `~/.bambu-code`, mode 600, never echoed |
| `max_upload_mb` | refuse anything larger |
| `token_file` | **empty = open.** If set to a file, every POST/DELETE must carry it |

### The token

Reachability on this mesh does not check who is asking: any `<name>.<device>.mesh`
resolves to the device and the daemon forwards to any listening port — a name I
invented reached this service just as well as the declared one. So the port is
open to every member of the office mesh.

To require a token:

    python3 -c "import secrets;open('/home/vpavlin/slicer/token','w').write(secrets.token_urlsafe(24))"
    chmod 600 /home/vpavlin/slicer/token
    # set "token_file": "/home/vpavlin/slicer/token" in config.json, restart

then send `X-Slicer-Token: <token>` or `?token=<token>`. Reads stay open.
Verified: no token → 401, wrong token → 401, right token → 202.

## Why FTPS by hand

The printer's FTPS answers `USER` with `331` and no message, which every stock
client calls malformed; `commons-net` refused to log in at all. `Ftps` is six
commands written by hand (implicit TLS, greet, USER, PASS, PBSZ 0, PROT P,
PASV + STOR). The data connection is retried against **the control connection's
own address** — over a mesh name the passive port the printer advertises is not
routable, which is the same `502` this project hit from Android.

## What it will not do

- **Multi-plate.** One plate per job (`--slice 0` on a single-plate model).
- **Download from Printables or MakerWorld by URL** without your login: the
  Printables API exposes `filesCount` but no public file field and the model page
  is behind a Turnstile challenge; MakerWorld's download endpoint answers 403.
  A plain STL/3MF URL works. In practice the phone already has the file — post it.
- **Start, pause or stop a print.** That is Bambu Watch's job and its journal.

## Files

    slicer.py     the service (stdlib only, no dependencies)
    config.json   defaults
    bin/          xvfb-run and Xvfb, symlinked out of /nix/store so the service
                  does not depend on a volatile profile
    app/          Bambu Studio 02.08.02.61, extract of the official AppImage
    jobs/<id>/    input, resolved presets, out/, job.json, log
    token         only if you turn the gate on
