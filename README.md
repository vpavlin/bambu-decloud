# bambu-decloud

Everything Bambu's cloud does for a Bambu Lab A1, done on your own network: slice from a
phone, print over the LAN, watch it on the camera, and drive FreeCAD on a laptop from a
tablet. No account, no cloud, no vendor service in the loop.

Three pieces, all written because the official app needs the internet and the slicer needs
a desktop:

| piece | what it is | where it runs |
---|---|---|
| [`watch/`](watch) | Android app: monitor, camera, AMS, job list, slicing client | your phone |
| [`slicer/`](slicer) | headless Bambu Studio behind a small HTTP API | any Linux box on the LAN |
| [`freecad/`](freecad) | FreeCAD macro that publishes a model to WebDAV and queues a slice | the CAD machine |
| [`tools/`](tools) | `rfb-grab.py`: one framebuffer from a VNC server, no client, no library | anywhere |

```
   phone (watch)                  LAN                        printer
   ┌──────────┐   HTTP    ┌────────────────┐   FTPS 990   ┌──────────┐
   │ Slice UI │ ────────► │ slicer (HTTP)  │ ───────────► │  A1      │
   │ Monitor  │ ◄──────── │  Bambu Studio  │              │  8883    │
   └────┬─────┘  preview  └────────────────┘              └────┬─────┘
        │  MQTT 8883 (own connection, read-mostly)              │
        └───────────────────────────────────────────────────────┘

   laptop (FreeCAD)  ──WebDAV PUT──►  drop  ◄──?url=──  slicer
```

There is a fourth piece that makes the rest possible and is not ours: a private overlay
network that gives every device a stable address and a DNS name, so nothing needs a port
forward, an account or a public IP. It is described in
[`docs/the-mesh.md`](docs/the-mesh.md), because with it removed this design does not exist.

- [docs/print-server.md](docs/print-server.md) — CUPS on the mesh: the vendor driver, the PPD fix, printing costs, and the traps that look like hardware faults

## The full story

| document | what it covers |
|---|---|
| [`docs/lessons.md`](docs/lessons.md) | the printer: its real error channel, filament semantics, presets, the resonance sweep |
| [`docs/protocol.md`](docs/protocol.md) | MQTT topics and payloads, FTPS, camera framing |
| [`docs/freecad-on-a-tablet.md`](docs/freecad-on-a-tablet.md) | **a desktop CAD program on a pen tablet**: Xvfb with GLX, VirtualGL on the GPU, x11vnc, wlvncc in a Basecamp tab, input, scaling, dead ends |
| [`docs/the-mesh.md`](docs/the-mesh.md) | the overlay network: names, services, portless reachability, what a shared desktop really is |
| [`infra/`](infra) | systemd units for the slicer and the FreeCAD desktop, and the BasecampOS tab entry |

Read `docs/freecad-on-a-tablet.md` before changing anything about the remote desktop: it
records four approaches that do not work and why, which is cheaper than rediscovering them.

## The rules this project follows

- **LAN only.** Nothing here talks to `*.bambulab.com`. The printer is addressed by IP or
  by a local DNS name.
- **Read-mostly by default.** The app refuses every command that changes the printer
  unless you turn read-only off in Settings.
- **The printer's own words.** Errors are shown translated from Bambu's 5,943-code table
  (and the `hms` array), not guessed at or summarised.
- **No secrets in git.** Access codes, VNC passwords and keystores live in mode-600 files
  on the machine that needs them.

## Quick start

**Slicer** (needs the official Bambu Studio AppImage extracted; see `slicer/README.md`):

```
python3 slicer/slicer.py          # listens on 0.0.0.0:8799 by default
```

**App**: build with Gradle, or take a signed APK from wherever you distribute it. Point it
at `<slicer host>:8799` in Settings, enter the printer's IP and access code.

**FreeCAD**: copy the two files in `freecad/` into `~/.local/share/FreeCAD/Macro/`, then
*Macro → Macros… → PublishToDrop*, and add it to a toolbar.

## Documentation

- [`docs/lessons.md`](docs/lessons.md) — the things that cost us days. Read this before
  changing anything: the printer's real error channel, what `use_ams` actually means, why
  a sliced file may not load filament, and how to skip the resonance sweep.
- [`docs/protocol.md`](docs/protocol.md) — MQTT topics and payload shapes, FTPS quirks.
- [`infra/`](infra) — systemd units and the network facts (ports, mesh names, firewalls).

## Status

Working on real hardware: monitoring, camera, AMS, card browsing, slicing, printing from
the app, and FreeCAD-on-a-tablet via VNC. The `slicer` has no authentication of its own —
put it behind your LAN's trust model, or add the token gate the code already supports
(`token_file` in `config.json`).

Licences: the app and the slicer are ours; Bambu Studio is Bambu's, fetched not vendored;
the error table is extracted from the printer's own firmware strings.
