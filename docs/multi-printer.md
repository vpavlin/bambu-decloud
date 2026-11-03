# One slicer, several printers

The slicer started life as "the A1". With a second machine arriving (a P1S), that
assumption has to go — and it is not only the slicer: the phone app, the card upload, the
presets and the print command all assumed one printer existed.

## What a "printer" is here

A record with everything needed to slice *for* it and to reach it:

```json
{ "id": "p1s", "name": "Bambu Lab P1S",
  "host": "…", "serial": "…", "ftps_port": 990, "code_file": "~/.bambu-p1s-code",
  "card_dir": "",
  "machine":  "Bambu Lab P1S 0.4 nozzle.json",
  "process":  "0.20mm Standard @BBL X1C.json",
  "filament": "Bambu PLA Basic @BBL X1C.json",
  "family": "X1C", "bed_mm": [256, 256], "has_ams": true, "camera_port": 6000 }
```

Two fields deserve a note:

- **`family`** decides which presets are legal. Bambu ships process and filament presets
  **per family**, not per model: a P1S takes the `@BBL X1C` presets, an A1 its own. Mixing
  them produces a slice that cannot print, and it fails at the printer rather than in the
  slicer. A machine's presets are looked up by family, never by hand.
- **`code_file`** is a path to a mode-600 file holding the LAN access code. Credentials
  never appear in `printers.json` itself, so the file can be copied around.

## API

| call | meaning |
|---|---|
| `GET /printers` | what exists: id, name, machine, presets, bed, AMS, camera port. **No credentials, no paths.** |
| `POST /jobs?printer=p1s` | slice for that printer. The machine/process/filament default to that printer's own. |
| `POST /jobs/<id>/upload?tray=N` | uploads to **that job's** printer, over its own FTPS endpoint and access code |

Jobs record which printer they belong to (`job.printer`) and their machine, so a job is
never ambiguous later.

## Compatibility

No `printers.json`, or no `printer=` parameter, behaves exactly as before: `config.json`
becomes a single anonymous printer (`id: "default"`). Existing jobs, scripts and the app
keep working, and a second machine is additive.

## Per-family correctness, and how to check it

The failure mode here is silent: the wrong preset still slices. So verify by *artefact*,
not by intent:

```
same STL, two printers:
  a1   gcode sha256 0b08fa8c…   header "Bambu Lab A1"   7596 s
  p1s  gcode sha256 a93e95a1…   header "Bambu Lab P1S"  6348 s
```

Different machine in the header, different gcode, same geometry (max z 57.40) and the same
filament use — and the P1S comes out 20 % faster because it is coreXY. If two printers
produce byte-identical gcode, one of them is not being sliced for.

## What the P1S changes beyond the profile

- **Start gcode lives in a `template` file**, exactly like the A1's. The instantiation fix
  that made the A1 load filament applies to it unchanged; verify that the output contains
  `M620 SxA` before trusting any print.
- **No lidar.** Flow calibration (`flow_cali`) is an X1-series feature; on a P1S the flag may
  be ignored rather than honoured. Treat "it sliced" as unrelated to "it will calibrate",
  and check on the hardware.
- **AMS is optional.** `has_ams` is per printer, and `use_ams`/`ams_mapping` must follow it —
  a machine without an AMS is exactly the case where `use_ams: false` is *correct*.
- Chamber temperature and the auxiliary fan differ; both come from the machine profile, so
  nothing extra is needed as long as the right machine is selected.

## The app

The phone currently holds **one** printer's credentials and sends the print command itself.
With several machines that is the wrong split: credentials belong where the printers are
registered (the slicer), and the phone should be a client.

Planned shape:

1. `GET /printers` fills a picker; the selection is remembered.
2. The slice screen's presets come from the selected printer's family.
3. **The slicer sends the print command** (`POST /jobs/<id>/print`), so the phone needs no
   per-printer access code and one build works with any number of machines.
4. The monitor still opens its own MQTT connection — but to the *selected* printer, whose
   host/serial it gets from the same list.

Step 3 is the important one: it is also what stops the phone from being the only component
that can start a print, which is what made today's debugging so hard.
