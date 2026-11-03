# What cost us days

Every entry here was learned on a real A1 with real failures. Read it before touching the
slicer or the print path; each one looks like something else when it bites.

## The printer's error channel is `hms`, not `print_error`

`print_error 0` means nothing. The real diagnostic arrives as an array:

```json
"hms": [{"attr": 50336000, "code": 131073}]
```

The error table keys on the pair as **sixteen hex digits**: `"%08X%08X".format(attr, code)`
→ `0300110000020001` → *"The resonance frequency of the Y axis is low. The timing belt may
be loose."*

Showing the two integers is showing nothing. A print that fails with an empty
`print_error` is not failing without a reason.

## `use_ams: false` means "filament is already at the nozzle"

It does **not** mean "the printer decides". With no AMS mapping, the printer loads nothing
and draws the model in air — which looks exactly like a working print from every status
field, because it *is* printing.

The slot must travel with the print command (`ams_mapping: [n]`, `use_ams: true`). A slot
chosen in a UI after slicing is worthless unless it reaches that command: the mapping is a
field of `project_file`, not of the gcode, so it needs no reslice — it needs to be sent.

## Bambu presets: inheritance *and* template instantiation

A machine preset like `Bambu Lab A1 0.4 nozzle.json` defines almost nothing; it declares
`inherits` and the chain ends at a generic `fdm_machine_common`. The real start sequence
(filament load, nozzle brush, wipe) lives in sibling files named
`<machine> template machine_start_gcode.json`, marked `instantiation`.

Merge the inheritance chain and **instantiate those templates**, or you slice with a
22-line stub that has no `M620` in it — no filament load — and wonder why the toolhead
moves dry. The same trap in the filament chain makes `filament_density` 0: the print then
weighs 0.00 g and the estimated time is fiction.

## The resonance sweep is six gcode commands

The A1 runs `M970`/`M974` commands at the start of every print. Bambu's wiki calls this a
frequency sweep that decides whether the belts need re-tensioning — it is real, it catches
loose belts, and it costs minutes per print.

To skip it, remove those lines from the plate gcode and **rewrite
`Metadata/plate_1.gcode.md5`** inside the 3MF (uppercase MD5) or the printer refuses the
project. Keep `G28` (homing) and `G29` (bed levelling). The calibration stored from the
touchscreen still applies.

## Print from the ROOT of the card, not `/cache`

`/cache` is where the printer dumps leftovers. A `project_file` naming a file there gets
`print_error 83935248` ("MicroSD card read/write exception") every time — including for
Bambu Studio's own 3MF, which is what makes a dying card look like the culprit.

## `project_file` always answers `success`

Even for a file that does not exist. The real answer arrives asynchronously in `hms` /
`print_error`. Never treat the ack as evidence.

## A moving toolhead with no extrusion is usually still PREPARE

The start sequence wipes, shakes, touches off and runs a nozzle load line before the first
purge, all without extrusion by design. Do not diagnose a feeding bug until you have seen
the first purge line, or an `hms` about filament.

## STLs from the internet are often in metres

A 2.0-unit-tall model read as millimetres slices to 3 layers and 0.09 g. Check the bounding
box and scale by 100 or 1000. A slicer that silently accepts this is worse than one that
refuses.

## FTPS, not FTP

The printer's file service is FTPS on **990** (implicit TLS), with the access code as the
password and `bblp` as the user. Plain FTP on 21 is not open.

## A parameter list in two places is a parameter that disappears

Adding `support`, `infill` and `wallgen` to the slicer's `PARAMS` changed nothing: the job
creation handler kept its **own** hard-coded tuple of accepted names, so the values were
dropped before they reached the job, the slicer sliced with defaults, and the only symptom
was "the setting does nothing".

Same shape as `use_ams`: a control that is connected to nothing. Both now derive from one
list. When a UI control appears to do nothing, trace the value to where it is *used* before
suspecting the setting itself.

## Process overrides belong in the process profile

Support, infill density and wall generator are **process** settings, not print-command
fields: write them into the resolved process profile that the slicer is handed
(`support_enable`, `sparse_infill_density` as `"50%"`, `wall_generator` as `arachne`), and
leave the print command to the things it actually carries — bed levelling, flow
calibration, and the resonance flags.

Check it by weight, not by intent: the same model at 15 % infill is 11.46 g and at 50 %
infill is 17.94 g. If the estimate does not move, the override did not apply.

## A grep is not a leak scan — part two: the token file

`slicer/token` sat in this public repo for weeks and was byte-identical to the live file.
It was never *in force* (`token_file: ""` skips the check, and the access log shows zero
requests carrying a token), so nothing was exposed in practice — but "not currently
enforced" is not a security property, it is a coincidence.

The rule that would have caught it: **scan for secrets by kind, not by string.** Looking for
the printer serial and the LAN IP finds those two; it does not find a 32-character random
string in a file called `token` next to a config key called `token_file`. A `git ls-files`
review of what is actually committed — every file, by name — is the cheap check that works.

Now: `.gitignore` covers `slicer/token`, the live value is rotated (so the published one is
dead), and `code_file`/`token_file` paths are the only secret-adjacent things in the tree.
