# A printer on the mesh

The Brother DCP-T230 (USB-only inkjet MFP) on `proteus`, shared to the office mesh through
CUPS. Written after a day in which nearly every layer of this lied in a different way.

## The shape

```
laptop / phone ──ipp──▶ CUPS on proteus (mesh service "cups")  ──usb──▶ DCP-T230
```

Clients do not need a driver. They send IPP/PDF; the vendor filter on proteus does the
rendering. That is also why a browser, macOS or Android can print to it.

Reach it as:

```
web   http://cups.proteus.office.mesh
ipp   ipp://cups.proteus.office.mesh:631/printers/DCPT230
ipp   ipp://cups.proteus.office.mesh/printers/DCPT230        (portless, via the daemon)
```

## Driverless does not work on this device

`ipp-usb` finds it and exposes `localhost:60000`, but the printer never answers IPP over
USB — `ipptool` aborts and the service deactivates itself. The vendor driver is required.

```
dcpt230pdrv 3.6.1-2   printer
brscan5 1.8.1-0       scanner
brscan-skey 0.3.6-0
```

Installed with Brother's own `linux-brprinter-installer`. **`usblp` must be loaded** (the
Brother backend uses it); `ipp-usb` must be stopped and masked so the two do not fight over
the interface.

## Two traps that both look like broken hardware

**1. The installer writes a device URI that cannot work.** It sets `usb://dev/usblp0`, which
CUPS reads as vendor `dev`, product `usblp0`, and the job sits at *"Waiting for printer to
become available"* forever. The correct URI is what `lpinfo -v` reports:

```
usb://Brother/DCP-T230?serial=…
```

**2. Brother's PPD declares page sizes with empty dimensions**, and seven borderless
variants that duplicate their standard twins. CUPS turns a size it cannot map into an
**empty keyword** in `media-supported` — an RFC 8011 violation — and a strict client then
refuses the whole queue with:

```
lpadmin: Printer returned invalid data: "media-supported": Bad keyword value ""
```

Meanwhile `lpstat` on the server shows *idle, accepting*, and printing from the server
itself works. The queue looks healthy and every client refuses it.

`infra/tools/dcpt230-ppd-fix` repairs a PPD, idempotently:

- fills dimensions only where the driver left them **empty** (never touches its margins —
  A4 is `9 9 586 833`, about 3 mm);
- keeps every borderless variant, each 2 pt **smaller** than its twin, because an exact
  duplicate is what becomes the empty keyword;
- writes a `.bak-<timestamp>` before touching anything.

Run it after a driver reinstall **and after any `lpadmin -o`**, which rewrites the queue's
PPD and merges the driver's original back in.

Canonical names: `A4` (default, margins), `BrA4_B` (borderless A4, 593x840),
`BrPostC4x6_B` (288x432, full bleed), `BrPostC4x6_S` (286x430, bordered), and the rest of
the `Br*_B` / `Br*_S` pairs. `A4_B` is **not** a synonym for `BrA4_B`; do not alias it.

## The mesh service

```
mesh.office.services = ["basecampos:8787", "slicer:8799", "cups:631->[fdb0:…]:631"]
```

applied with `sudo shrooms reload` (`systemctl reload shrooms` fails on that unit;
`shrooms config validate` first).

**The arrow is required.** Without it the daemon forwards to loopback, and CUPS rejects a
Host it was not given *on a local connection* — `ServerAlias *` does not help there, it just
logs `invalid Host: cups.proteus.office.mesh` and returns 400. Pointing the service at the
mesh listener (`->[fdb0:…]:631`) works on both the port and the portless path.

Firewall: **do not create a zone for one port.** An interface lives in exactly one zone, so
moving `shrooms0` into a new `mesh` zone silently removed it from `public`, where every
other mesh rule lived — SSH, the agent, Foray and backup all went dark on the mesh. The
working shape is a destination-scoped rich rule in `public`, like the rest:

```
firewall-cmd --permanent --zone=public --add-rich-rule='
  rule family="ipv6" destination address="<mesh addr>/128" port port="631" protocol="tcp" accept'
```

## Costing prints

CUPS cannot account for this printer: the Brother filter never reports page counts, so every
job in the journal is `total 0`. The count has to come from the document.

- `infra/tools/pp` — print **and** record: counts pages (PDF via gs/pdfinfo, PS via
  `%%Pages`/`showpage`, images = 1, text estimated), passes `lp` options through, appends a
  CSV line to `/var/lib/printcost/ledger.csv`. `--dry-run` counts without printing.
- `infra/tools/printcost` — totals from the ledger, plus filament/electricity from the
  slicer's estimates.
- `infra/tools/printcost.example.conf` — prices.

Ink is an **estimate**, derived from Brother's own yield figures (quoted at 5 % page
coverage) scaled by coverage:

```
A4 mono at 5 %    0.17 Kč        A4 colour at 100 %   2.17 Kč
A4 colour at 5 %  0.25 Kč        10x15 photo          1.55 Kč
```

Paper and filament are real prices; ink is a model. Say so when quoting it.

## Paper

The tray takes a stack of plain paper (150 sheets at 80 g/m²); the manual feed slot takes
**one sheet at a time, 64–300 g/m²**. Photo stock in practice feeds from the tray when it is
light enough — tested with matte 10x15, five sheets, no misfeed. Glossy is riskier: coating
plus stiffness. If double-feeds appear, rattle the stack, load few, or go to the slot.

Borderless is the point of photo paper: `-o PageSize=BrPostC4x6_B -o MediaType=Glossy`.

## Reading the gcode of a Bambu print

This Bambu build (02.08.02.61) labels features `; FEATURE: Outer wall` / `Bridge` /
`Support` — **not** `;TYPE:`. A `;TYPE:Bridge` grep returns nothing and looks like "no
bridging at all", which is the false negative that wasted an hour.

Layer heights appear as `; Z_HEIGHT: 0.2`, not `; Z:`.

## One more trap, for whoever slices next

The A1 machine profile leaves `curr_bed_type` unset, so the CLI assumes a **Cool Plate** and
refuses PETG outright:

```
got error when validate: Plate 1: Cool Plate does not support filament 1
```

The bed type has no CLI flag; it must be set in the profile (`curr_bed_type`). Our slicer
takes it from `config.json` (`bed_type: "Textured PEI Plate"`).
