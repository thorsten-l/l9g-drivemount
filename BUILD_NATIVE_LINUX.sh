#!/usr/bin/env bash
#
# Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Baut drivemount auf einem Linux-Rechner. GraalVM kann nicht cross-
# compilieren - fuer ein Linux-Binary muss auf Linux gebaut werden.
# Das Skript kopiert die Quellen per ssh dorthin, stoesst den Build an und
# holt das Binary zurueck.
#
# Deutlich schlanker als das Windows-Gegenstueck: drueben laeuft dasselbe
# BUILD_NATIVE_MACOS.sh wie auf dem Mac. Der Name ist an dieser Stelle
# irrefuehrend - das Skript verzweigt selbst ueber "uname -s" und kennt den
# Linux-Fall (Toolchain-Preflight, keine Signatur, kein App-Bundle). Es
# braucht also kein eigenes Remote-Skript.
#
#   ./BUILD_NATIVE_LINUX.sh              Quellen kopieren, bauen, Binary holen
#   ./BUILD_NATIVE_LINUX.sh --fast       ohne Tests
#   ./BUILD_NATIVE_LINUX.sh --no-sync    ohne Kopieren (Stand drueben nutzen)
#   ./BUILD_NATIVE_LINUX.sh --check      nur Voraussetzungen pruefen
#   ./BUILD_NATIVE_LINUX.sh --fetch      nur das fertige Binary holen
#   ./BUILD_NATIVE_LINUX.sh --setup      fehlende Entwicklungspakete nachinstallieren
#                                        (fragt drueben nach dem sudo-Passwort,
#                                         laeuft deshalb mit Terminal)
#   ./BUILD_NATIVE_LINUX.sh --create-app Desktop-Anwendung bauen: Binary, Symbole
#                                        und .desktop-Starter. Wird drueben nach
#                                        ~/.local installiert und kommt als
#                                        target/DriveMount-linux.tar.gz zurueck
#
# Umgebung: kommt aus ./.env (siehe env.sample), einzeln uebersteuerbar
# ueber die Umgebung - ein gesetztes LINUX_HOST=... sticht den Eintrag dort.
#
#   LINUX_HOST      ssh-Ziel des Linux-Rechners (Pflicht)
#   LINUX_PROJECT   Projektverzeichnis drueben, relativ zum Home
#   LINUX_NIK_HOME  Liberica NIK Full drueben
#
# Voraussetzungen drueben: Liberica NIK *Full* (LibericaFX), ein C-Compiler
# und die zlib-Header (build-essential + zlib1g-dev). Fuer den Mount zur
# Laufzeit zusaetzlich gvfs samt Backends - das braucht der Build nicht.
#
# Ausserdem die *Entwicklungs*pakete von GTK, X11 und GL. Auf einem normalen
# Desktop liegen nur die Laufzeitbibliotheken (libgtk-3.so.0), der Linker
# braucht aber den Symlink ohne Versionsnummer (libgtk-3.so) aus dem
# -dev-Paket. Fehlt das, laeuft der Build erst vollstaendig durch und
# scheitert ganz am Ende mit "/usr/bin/ld: cannot find -lgtk-3".
#
# Ergebnis: target/drivemount-linux
#           (mit --create-app zusaetzlich target/DriveMount-linux.tar.gz)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Werte dieser Installation aus ./.env holen; bereits gesetzte
# Umgebungsvariablen bleiben unangetastet.
# shellcheck source=load-env.sh
[[ -f "$PROJECT_DIR/load-env.sh" ]] && . "$PROJECT_DIR/load-env.sh" \
  && drivemount_load_env "$PROJECT_DIR/.env"

LINUX_HOST="${LINUX_HOST:-}"
LINUX_PROJECT="${LINUX_PROJECT:-Projects/l9g-drivemount}"
LINUX_NIK_HOME="${LINUX_NIK_HOME:-/opt/nik/25-full}"

if [[ -z "$LINUX_HOST" ]]; then
  echo "FEHLER: LINUX_HOST ist nicht gesetzt." >&2
  echo "        Eintrag in ./.env ergaenzen (Vorlage: env.sample) oder" >&2
  echo "        einmalig mit LINUX_HOST=... $(basename "$0") starten." >&2
  exit 2
fi

# Das Ergebnis heisst lokal anders als drueben: target/drivemount ist auf
# diesem Rechner schon das macOS-Binary. Ueberschreiben waere eine boese
# Ueberraschung.
LOCAL_BINARY="target/drivemount-linux"

# Entwicklungspakete, die JavaFX zum Linken braucht. libgtk-3-dev zieht
# cairo, pango, atk, gdk-pixbuf und ueber libpango1.0-dev auch harfbuzz mit.
DEV_PACKAGES="libgtk-3-dev libxtst-dev libgl1-mesa-dev zlib1g-dev"

SYNC=1
CHECK_ONLY=""
FETCH_ONLY=""
SETUP=""
CREATE_APP=""
BUILD_ARGS=()

for arg in "$@"; do
  case "$arg" in
    --fast)     BUILD_ARGS+=(--fast) ;;
    --no-clean) BUILD_ARGS+=(--no-clean) ;;
    --no-sync)  SYNC="" ;;
    --check)    CHECK_ONLY=1 ;;
    --fetch)    FETCH_ONLY=1 ;;
    --setup)    SETUP=1 ;;
    --create-app) CREATE_APP=1 ;;
    -h|--help)  sed -n '/^# Baut drivemount auf/,/^# Ergebnis/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

cd "$PROJECT_DIR"

ssh_linux()
{
  ssh -T -o ConnectTimeout=15 "$LINUX_HOST" "$@" 2>&1 \
    | grep -vE '^\*\* (WARNING|This session|The server)' || return "${PIPESTATUS[0]}"
}

fetch_binary()
{
  echo "==> Binary zurueckholen"
  mkdir -p target
  scp "$LINUX_HOST:$LINUX_PROJECT/target/drivemount" "$LOCAL_BINARY" \
    2>&1 | grep -vE '^\*\* (WARNING|This session|The server)' || true
  if [[ ! -s "$LOCAL_BINARY" ]]; then
    echo "FEHLER: $LOCAL_BINARY wurde nicht uebertragen." >&2
    exit 1
  fi
  ls -la "$LOCAL_BINARY"
}

echo "==> Linux-Host: $LINUX_HOST"
ssh_linux 'echo "    $(whoami)@$(hostname) / $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-$(uname -sr)}")"'

if [[ -n "$SETUP" ]]; then
  echo "==> Entwicklungspakete nachinstallieren"
  echo "    $DEV_PACKAGES"
  echo "    sudo fragt gleich nach dem Passwort."
  echo
  # Mit Terminal (-t), sonst kann sudo nicht nach dem Passwort fragen.
  ssh -t -o ConnectTimeout=15 "$LINUX_HOST" \
    "sudo apt-get update && sudo apt-get install -y --no-install-recommends $DEV_PACKAGES"
  exit $?
fi

if [[ -n "$CHECK_ONLY" ]]; then
  echo "==> Preflight"
  ssh_linux "
    printf '    NIK          : '; [ -x '$LINUX_NIK_HOME/bin/native-image' ] && echo ok || echo FEHLT
    printf '    LibericaFX   : '; [ -e '$LINUX_NIK_HOME/jmods/javafx.controls.jmod' ] && echo ok || echo FEHLT
    printf '    C-Compiler   : '; command -v cc >/dev/null && echo ok || echo 'FEHLT (build-essential)'
    printf '    zlib-Header  : '; [ -e /usr/include/zlib.h ] && echo ok || echo 'FEHLT (zlib1g-dev)'
    printf '    Projektstand : '; [ -e '$LINUX_PROJECT/pom.xml' ] && echo ok || echo 'FEHLT (ohne --no-sync starten)'
    printf '    gvfs/gio     : '; command -v gio >/dev/null && echo \"ok (\$(gio --version))\" || echo 'FEHLT (gvfs-backends)'
    for p in gtk+-3.0 xtst gl; do
      printf '    %-12s : ' \"\$p\"
      pkg-config --exists \"\$p\" 2>/dev/null && echo 'dev ok' || echo 'FEHLT (--setup)'
    done
  "
  exit 0
fi

if [[ -n "$FETCH_ONLY" ]]; then
  fetch_binary
  exit 0
fi

if [[ -n "$SYNC" ]]; then
  echo "==> Quellen uebertragen nach $LINUX_HOST:$LINUX_PROJECT"
  # target/ und .git/ bleiben draussen: das eine wird drueben neu gebaut,
  # das andere ist gross und unnoetig.
  TARBALL="$(mktemp -t drivemount-src).tgz"
  trap 'rm -f "$TARBALL"' EXIT
  tar czf "$TARBALL" \
    --exclude='./target' --exclude='./.git' --exclude='./.idea' \
    --exclude='.DS_Store' \
    ./pom.xml ./mvnw ./mvnw.cmd ./.mvn \
    ./src ./packaging ./*.sh ./*.ps1 ./*.md ./LICENSE 2>/dev/null
  echo "    $(du -h "$TARBALL" | cut -f1)"

  scp -q "$TARBALL" "$LINUX_HOST:/tmp/drivemount-src.tgz"
  ssh_linux "
    mkdir -p '$LINUX_PROJECT'
    cd '$LINUX_PROJECT'
    rm -rf src
    tar xzf /tmp/drivemount-src.tgz
    rm -f /tmp/drivemount-src.tgz
    chmod +x ./*.sh
    echo \"    entpackt: \$(find src -type f | wc -l | tr -d ' ') Quelldateien\"
  "
fi

echo "==> Build starten"
# Drueben laeuft dasselbe BUILD_NATIVE_MACOS.sh - trotz des Namens das
# gemeinsame Skript fuer beide Unix-Plattformen. NIK_HOME durchreichen.
ssh_linux "cd '$LINUX_PROJECT' && NIK_HOME='$LINUX_NIK_HOME' ./BUILD_NATIVE_MACOS.sh ${BUILD_ARGS[*]:-}"

fetch_binary

# ------------------------------------------------------------ Desktop-App
# Unter Linux ist eine "App" kein Bundle wie auf macOS, sondern ein
# .desktop-Starter plus Symbole im hicolor-Theme. Beides landet unter
# ~/.local, also ohne root. Zusaetzlich entsteht ein Tarball, mit dem sich
# dasselbe auf anderen Rechnern einspielen laesst.
if [[ -n "$CREATE_APP" ]]; then
  echo
  echo "==> Desktop-Anwendung zusammenstellen"
  ssh_linux "
    set -e
    cd '$LINUX_PROJECT'
    rm -rf target/app
    mkdir -p target/app/DriveMount/icons
    cp target/drivemount            target/app/DriveMount/drivemount
    cp packaging/icons/*x*.png      target/app/DriveMount/icons/
    cp packaging/icon.svg           target/app/DriveMount/icons/icon.svg
    cp packaging/linux/drivemount.desktop.in target/app/DriveMount/
    cp packaging/linux/install.sh   target/app/DriveMount/
    chmod +x target/app/DriveMount/install.sh target/app/DriveMount/drivemount
    tar czf target/DriveMount-linux.tar.gz -C target/app DriveMount
    echo \"    \$(du -h target/DriveMount-linux.tar.gz | cut -f1) Tarball\"
    echo '==> Installieren nach ~/.local'
    ./target/app/DriveMount/install.sh
  "

  echo "==> Tarball zurueckholen"
  scp "$LINUX_HOST:$LINUX_PROJECT/target/DriveMount-linux.tar.gz" \
    target/DriveMount-linux.tar.gz \
    2>&1 | grep -vE '^\*\* (WARNING|This session|The server)' || true
  if [[ ! -s target/DriveMount-linux.tar.gz ]]; then
    echo "FEHLER: target/DriveMount-linux.tar.gz wurde nicht uebertragen." >&2
    exit 1
  fi
  ls -la target/DriveMount-linux.tar.gz
fi
