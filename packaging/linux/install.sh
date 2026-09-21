#!/usr/bin/env bash
#
# Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
# SPDX-License-Identifier: Apache-2.0
#
# Installiert DriveMount als Desktop-Anwendung fuer den aktuellen Benutzer -
# ohne root, alles unter ~/.local. Danach taucht es in der Uebersicht auf und
# laesst sich in die Leiste ziehen.
#
#   ./install.sh              installieren
#   ./install.sh --uninstall  wieder entfernen
#
# Erwartet neben sich: drivemount (Binary), drivemount.desktop.in, icons/

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="drivemount"

BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="$DATA_DIR/applications"
ICON_DIR="$DATA_DIR/icons/hicolor"

DESKTOP_FILE="$APP_DIR/$NAME.desktop"

refresh()
{
  # Fehlt eines der Werkzeuge, ist das kein Grund zum Abbruch - der Starter
  # erscheint dann spaetestens nach dem naechsten Anmelden.
  command -v update-desktop-database >/dev/null 2>&1 \
    && update-desktop-database "$APP_DIR" 2>/dev/null || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 \
    && gtk-update-icon-cache -f -t "$ICON_DIR" 2>/dev/null || true
}

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -f "$DESKTOP_FILE" "$BIN_DIR/$NAME"
  find "$ICON_DIR" -name "$NAME.png" -o -name "$NAME.svg" 2>/dev/null \
    | xargs -r rm -f
  refresh
  echo "DriveMount entfernt."
  exit 0
fi

[[ -f "$DIR/$NAME" ]] || { echo "FEHLER: $DIR/$NAME fehlt." >&2; exit 1; }

echo "==> Binary nach $BIN_DIR"
mkdir -p "$BIN_DIR"
install -m 755 "$DIR/$NAME" "$BIN_DIR/$NAME"

echo "==> Symbole nach $ICON_DIR"
for png in "$DIR"/icons/*x*.png; do
  [[ -e "$png" ]] || continue
  size="$(basename "$png" .png)"
  mkdir -p "$ICON_DIR/$size/apps"
  install -m 644 "$png" "$ICON_DIR/$size/apps/$NAME.png"
done
if [[ -f "$DIR/icons/icon.svg" ]]; then
  mkdir -p "$ICON_DIR/scalable/apps"
  install -m 644 "$DIR/icons/icon.svg" "$ICON_DIR/scalable/apps/$NAME.svg"
fi

echo "==> Starter nach $DESKTOP_FILE"
mkdir -p "$APP_DIR"
# Exec absolut eintragen: ~/.local/bin liegt nicht zwingend im PATH, den die
# Desktop-Umgebung ihren Startern mitgibt.
sed "s|@EXEC@|$BIN_DIR/$NAME|" "$DIR/$NAME.desktop.in" > "$DESKTOP_FILE"
chmod 644 "$DESKTOP_FILE"

if command -v desktop-file-validate >/dev/null 2>&1; then
  desktop-file-validate "$DESKTOP_FILE" && echo "    Starter ist gueltig"
fi

refresh

echo
echo "==> Fertig. DriveMount steht jetzt in der Anwendungsuebersicht."
echo "    Dort mit der rechten Maustaste -> \"Zu Favoriten hinzufuegen\","
echo "    dann liegt es dauerhaft in der Leiste."
