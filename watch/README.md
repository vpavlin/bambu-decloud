# Bambu Watch

A read-only monitor for a Bambu Lab printer on the LAN, no cloud. One screen plus
settings, no control and no uploads.

Written for the office A1 (`YOUR_SERIAL` at 192.168.1.50), but it takes any
address the phone can route to — a name or an IPv4 literal. Nothing assumes the
printer is on the same LAN as the phone.

## What it shows

- camera feed
- state: idle / printing / paused / finished / error, plus `print_error` and any HMS codes
- file name, progress %, current and total layer, time remaining
- nozzle, bed, and chamber temperature when the printer reports one
- AMS: colour swatch, material, remaining %, and which tray is active

## Protocol, as implemented

- **State** — MQTT over TLS on 8883, user `bblp`, password = access code.
  Subscribe `device/+/report`, publish `{"pushing":{"sequence_id":"0","command":"pushall"}}`
  to `device/<serial>/request`. The first full report establishes state; later
  reports are **merged**, because they carry only the fields that changed.
- **Serial** — learned from the report topic, from the report's `info.serial_number`,
  or from the TLS certificate CN. It is an optional settings field, not a requirement.
- **Camera (P1/A1)** — TLS to 6000, an 80-byte auth packet (`bblp` + code), then
  length-prefixed JPEG frames. The framing is implemented directly; there is no
  HTTP and no RTSP on that port.
- **Certificates** — the printer's certificate is signed by its own "BBL CA", so a
  public trust store is useless. The leaf is **pinned on first use** and a changed
  certificate is refused, not warned about and ignored. The pin can be cleared in
  settings if the printer is genuinely replaced.

## Security

- The access code is stored only in `EncryptedSharedPreferences` (AES-256-GCM via the
  Android keystore). Not logged, not in the repo, not in crash messages.
- Only `android.permission.INTERNET` is requested.

## Build

```bash
./build.sh assembleDebug        # see SETUP.md for the toolchain
```

## Status

0.1. Built and installed; the protocol above is exercised against a local mock
(`mock/`) that speaks the same TLS, the same topic layout and the same camera framing.
Not yet run against the real printer.
