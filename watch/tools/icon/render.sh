#!/usr/bin/env bash
# Render the Bambu Watch icon set from mark.svg.
#   legacy square/round icons (48..192), adaptive-icon foreground/background (432),
#   512 store icon, and a wordmark banner. Requires rsvg-convert.
set -euo pipefail
cd "$(dirname "$0")"
export PATH=/var/tmp/proteus/nixprof/bin:$PATH
RES=../../app/src/main/res   # tools/icon -> repo root -> app/src/main/res
BG="#111318"

# adaptive icon: background + foreground, content inside the 66/108 safe zone
rsvg-convert -w 432 -h 432 mark.svg -o fg-raw.png
python3 - "$BG" <<'PY'
import sys, subprocess
BG=sys.argv[1]
# foreground: mark scaled to 78% and centred on transparency (safe zone is 61%; 78% of the
# canvas is the usual visual weight, the mark itself is well inside it)
subprocess.run(["rsvg-convert","-w","338","-h","338","mark.svg","-o","mark-338.png"],check=True)
PY
echo "  fg-raw.png + mark-338.png rendered"

for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  name=${d%%:*}; px=${d##*:}
  mkdir -p "$RES/mipmap-$name"
  # legacy square (rounded corners applied by launchers on modern Android; keep it square-edged
  # with its own rounding so old launchers do not clip the ring)
  rsvg-convert -w "$px" -h "$px" icon-square.svg -o "$RES/mipmap-$name/ic_launcher.png"
  rsvg-convert -w "$px" -h "$px" icon-round.svg  -o "$RES/mipmap-$name/ic_launcher_round.png"
done
echo "  legacy icons: 5 densities, square + round"

mkdir -p "$RES/mipmap-anydpi-v26"
cat > "$RES/mipmap-anydpi-v26/ic_launcher.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
XML
cp "$RES/mipmap-anydpi-v26/ic_launcher.xml" "$RES/mipmap-anydpi-v26/ic_launcher_round.xml"

mkdir -p "$RES/drawable"
rsvg-convert -w 432 -h 432 bg.svg -o "$RES/drawable/ic_launcher_background.png"
rsvg-convert -w 432 -h 432 fg.svg  -o "$RES/drawable/ic_launcher_foreground.png"
echo "  adaptive: background + foreground"

rsvg-convert -w 512 -h 512 icon-square.svg -o store-512.png
rsvg-convert -w 1200 -h 400 banner.svg -o banner-1200x400.png
echo "  store icon + banner"
