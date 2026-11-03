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
