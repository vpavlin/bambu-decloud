# Protocol notes

## MQTT

- Broker: the printer itself, `:8883`, TLS with a self-signed certificate (verify nothing,
  or pin it).
- Credentials: user `bblp`, password = the printer's LAN access code.
- Subscribe to `device/<serial>/report` — *only* that topic. `device/+/report` is rejected
  by the broker.
- Publish to `device/<serial>/request`.
- On connect, send `{"pushing": {"sequence_id": "1", "command": "pushall"}}` to get a full
  state report immediately.

### `print.project_file`

The fields that matter, spelled exactly:

```json
{"print": {
  "sequence_id": "1", "command": "project_file",
  "param": "Metadata/plate_1.gcode",
  "subtask_name": "<name without .gcode.3mf>",
  "url": "ftp:///<file on the card>", "file": "",
  "project_id": "0", "profile_id": "0", "task_id": "0", "subtask_id": "0",
  "bed_type": "auto", "bed_levelling": true, "flow_cali": true,
  "vibration_cali": false,
  "ams_mapping": [1], "use_ams": true
}}
```

Both `bed_levelling` and `bed_leveling` are seen in the wild; our payload sends the former
and a test asserts both spellings keep their documented defaults.

## FTPS

- Port **990**, implicit TLS, user `bblp`, password = access code.
- The print pipeline reads from the **card root**. `/cache` is leftovers and will fail.

## Camera

- Port 6000, a proprietary stream: 80-byte header, then JPEG frames. It needs an auth
  packet built from the access code (`CameraAuth.kt`).
