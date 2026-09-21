#!/usr/bin/env bash
#
# Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
# SPDX-License-Identifier: Apache-2.0
#
# Erzeugt aus packaging/icon.svg die Anwendungs-Icons:
#
#   packaging/drivemount.icns        macOS-App-Bundle
#   packaging/drivemount.ico         Windows-EXE (Ressource)
#   packaging/icons/<n>x<n>.png      Linux, hicolor-Theme
#
# Bewusst ein eigener Schritt und nicht Teil des Builds: rsvg-convert ist
# kein Standardwerkzeug (brew install librsvg). Die Ergebnisse liegen im
# Projekt, die Build-Skripte verbrauchen sie nur. Neu erzeugen, wenn sich
# das Logo aendert.
#
#   ./packaging/make-icons.sh

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SVG="$DIR/icon.svg"
NAME="drivemount"

[[ -f "$SVG" ]] || { echo "FEHLER: $SVG fehlt" >&2; exit 1; }

command -v rsvg-convert >/dev/null 2>&1 || {
  echo "FEHLER: rsvg-convert fehlt (brew install librsvg)." >&2
  exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

render()
{
  rsvg-convert -w "$1" -h "$1" "$SVG" -o "$2"
}

# ---------------------------------------------------------------- macOS
# iconutil erwartet genau diese Dateinamen; @2x ist die Retina-Variante
# derselben Punktgroesse, also die doppelte Pixelzahl.
ICONSET="$WORK/$NAME.iconset"
mkdir -p "$ICONSET"
for pt in 16 32 128 256 512; do
  render "$pt"           "$ICONSET/icon_${pt}x${pt}.png"
  render "$((pt * 2))"   "$ICONSET/icon_${pt}x${pt}@2x.png"
done

if command -v iconutil >/dev/null 2>&1; then
  iconutil -c icns "$ICONSET" -o "$DIR/$NAME.icns"
  echo "  $DIR/$NAME.icns  ($(du -h "$DIR/$NAME.icns" | cut -f1))"
else
  echo "HINWEIS: iconutil nur auf macOS - .icns uebersprungen." >&2
fi

# -------------------------------------------------------------- Windows
# .ico von Hand schreiben: ICONDIR + je ein ICONDIRENTRY, die Bilddaten
# als PNG (von Windows Vista an unterstuetzt). Spart eine Abhaengigkeit
# auf ImageMagick.
for px in 16 24 32 48 64 128 256; do
  render "$px" "$WORK/ico_$px.png"
done

# ---------------------------------------------------------------- Linux
# Der hicolor-Theme will je Groesse eine eigene PNG-Datei. Die SVG kommt
# zusaetzlich als "scalable" mit, GNOME rendert sie selbst - dann sieht das
# Symbol auch auf Bildschirmen jenseits der abgelegten Groessen scharf aus.
mkdir -p "$DIR/icons"
for px in 16 24 32 48 64 128 256 512; do
  render "$px" "$DIR/icons/${px}x${px}.png"
done
echo "  $DIR/icons/  ($(ls "$DIR/icons" | wc -l | tr -d ' ') Groessen)"

python3 - "$WORK" "$DIR/$NAME.ico" <<'PY'
import pathlib, struct, sys

work, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
sizes = [16, 24, 32, 48, 64, 128, 256]
images = [(px, (work / f"ico_{px}.png").read_bytes()) for px in sizes]

header = struct.pack("<HHH", 0, 1, len(images))
offset = len(header) + 16 * len(images)
entries, blobs = b"", b""
for px, data in images:
    # 0 steht im ICO-Format fuer 256 Pixel - ein Byte fasst 256 nicht.
    entries += struct.pack("<BBBBHHII", px % 256, px % 256, 0, 0, 1, 32,
                           len(data), offset)
    blobs += data
    offset += len(data)

out.write_bytes(header + entries + blobs)
print(f"  {out}  ({out.stat().st_size // 1024} KB, {len(images)} Groessen)")
PY
