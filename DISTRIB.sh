#!/usr/bin/env bash
#
# Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
# SPDX-License-Identifier: Apache-2.0
#
# Sammelt die fertigen Anwendungspakete aus target/ nach distrib/ und haengt
# Version, Plattform und Architektur an den Dateinamen. Baut selbst nichts -
# das erledigen die drei BUILD_NATIVE*-Skripte:
#
#   ./BUILD_NATIVE_MACOS.sh --create-app     -> target/DriveMount-macos.zip
#   ./BUILD_NATIVE_WINDOWS.sh --create-app   -> target/DriveMount-windows.zip
#   ./BUILD_NATIVE_LINUX.sh --create-app     -> target/DriveMount-linux.tar.gz
#
#   ./DISTRIB.sh          sammeln
#   ./DISTRIB.sh --list   nur zeigen, was in distrib/ liegt
#
# Wichtig: "BUILD_NATIVE_MACOS.sh" raeumt target/ mit "mvn clean" ab. Pakete
# anderer Plattformen also einsammeln, bevor der naechste macOS-Build laeuft -
# genau dafuer ist dieses Skript da.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

DISTRIB="distrib"

pom_value()
{
  sed -n '/<\/parent>/,$p' pom.xml \
    | sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" | head -1
}

VERSION="$(pom_value version)"
[[ -n "$VERSION" ]] || { echo "FEHLER: Version nicht lesbar." >&2; exit 1; }

if [[ "${1:-}" == "--list" ]]; then
  echo "==> $DISTRIB (Version $VERSION)"
  ls -lh "$DISTRIB" 2>/dev/null | tail -n +2 || echo "    (leer)"
  exit 0
fi

mkdir -p "$DISTRIB"

# Die macOS-Architektur richtet sich nach dem Rechner, der gebaut hat -
# ein Intel-Mac liefert x86_64, Apple Silicon arm64. Windows und Linux
# werden bisher nur auf x86_64 gebaut; kommt das je anders, gehoert die
# Architektur aus dem jeweiligen Build-Host gelesen.
MACOS_ARCH="$(uname -m)"

collect()
{
  local source="$1" target="$2"
  if [[ -f "$source" ]]; then
    cp "$source" "$DISTRIB/$target"
    # Rechte vereinheitlichen: das Windows-Paket kommt per scp mit den
    # Rechten des Build-Rechners an (0600) und waere sonst nur fuer den
    # Eigentuemer lesbar - unguenstig fuer etwas, das verteilt wird.
    chmod 644 "$DISTRIB/$target"
    printf '    %-44s %s\n' "$target" "$(du -h "$source" | cut -f1)"
  else
    printf '    %-44s %s\n' "$target" "FEHLT ($source)"
  fi
}

echo "==> Pakete nach $DISTRIB/ (Version $VERSION)"
collect "target/DriveMount-macos.zip" \
  "DriveMount-$VERSION-macos-$MACOS_ARCH.zip"
collect "target/DriveMount-windows.zip" \
  "DriveMount-$VERSION-windows-x86_64.zip"
collect "target/DriveMount-linux.tar.gz" \
  "DriveMount-$VERSION-linux-x86_64.tar.gz"

echo
echo "==> Pruefsummen"
( cd "$DISTRIB" && shasum -a 256 DriveMount-* > SHA256SUMS 2>/dev/null \
  || sha256sum DriveMount-* > SHA256SUMS )
sed 's/^/    /' "$DISTRIB/SHA256SUMS"
