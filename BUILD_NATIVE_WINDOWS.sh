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
# Baut drivemount.exe auf einem Windows-Rechner. GraalVM kann nicht cross-
# compilieren - fuer ein Windows-Binary muss auf Windows gebaut werden.
# Das Skript kopiert die Quellen per ssh dorthin, stoesst den Build an und
# holt target/drivemount.exe zurueck.
#
#   ./BUILD_NATIVE_WINDOWS.sh              Quellen kopieren, bauen, Binary holen
#   ./BUILD_NATIVE_WINDOWS.sh --fast       ohne Tests
#   ./BUILD_NATIVE_WINDOWS.sh --no-sync    ohne Kopieren (Stand drueben nutzen)
#   ./BUILD_NATIVE_WINDOWS.sh --setup      fehlende Voraussetzungen nachinstallieren
#   ./BUILD_NATIVE_WINDOWS.sh --check      nur pruefen, nicht bauen
#   ./BUILD_NATIVE_WINDOWS.sh --fetch      nur das fertige Binary holen
#   ./BUILD_NATIVE_WINDOWS.sh --local-deps  de.l9g-Artefakte aus ~/.m2 mitliefern
#                                          (nur noetig, solange eine Version
#                                           nicht in Maven Central liegt)
#   ./BUILD_NATIVE_WINDOWS.sh --create-app  EXE mit Icon und Versionsinfos bauen
#                                          und als Ordner samt DLLs verpacken
#                                          (holt target/DriveMount-windows.zip)
#   ./BUILD_NATIVE_WINDOWS.sh --console     nur mit --create-app: Konsolenfenster
#                                          behalten (zum Fehlersuchen)
#
# Umgebung: kommt aus ./.env (siehe env.sample), einzeln uebersteuerbar
# ueber die Umgebung - ein gesetztes WIN_HOST=... sticht den Eintrag dort.
#
#   WIN_HOST           ssh-Ziel des Windows-Rechners (Pflicht)
#   WIN_PROJECT        Projektverzeichnis drueben
#   WIN_STAGING        Ablage fuer uebertragene Archive, mit Schraegstrichen
#   WIN_NIK_HOME       Liberica NIK Full drueben
#   WIN_COMPANY_NAME   CompanyName in den Versionsinfos der EXE
#
# Voraussetzungen auf dem Windows-Rechner: Liberica NIK *Full* (LibericaFX)
# und die Visual Studio Build Tools mit C++-Workload - ohne MSVC kann
# native-image nicht linken. Maven wird nicht gebraucht, das Projekt bringt
# den Wrapper (mvnw.cmd) mit. Fehlt etwas, bricht der Preflight drueben mit
# Klartext ab; --setup installiert die Build Tools nach (Adminrechte).
#
# Ergebnis: target/drivemount.exe (mit --create-app zusaetzlich
#           target/DriveMount-windows.zip)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Werte dieser Installation aus ./.env holen; bereits gesetzte
# Umgebungsvariablen bleiben unangetastet.
# shellcheck source=load-env.sh
[[ -f "$PROJECT_DIR/load-env.sh" ]] && . "$PROJECT_DIR/load-env.sh" \
  && drivemount_load_env "$PROJECT_DIR/.env"

WIN_HOST="${WIN_HOST:-}"
WIN_PROJECT="${WIN_PROJECT:-C:\\Users\\Public\\l9g-drivemount}"
WIN_STAGING="${WIN_STAGING:-C:/Users/Public}"
WIN_NIK_HOME="${WIN_NIK_HOME:-C:\\Program Files\\BellSoft\\LibericaNIK-Full-25-OpenJDK-25}"
WIN_COMPANY_NAME="${WIN_COMPANY_NAME:-}"

if [[ -z "$WIN_HOST" ]]; then
  echo "FEHLER: WIN_HOST ist nicht gesetzt." >&2
  echo "        Eintrag in ./.env ergaenzen (Vorlage: env.sample) oder" >&2
  echo "        einmalig mit WIN_HOST=... $(basename "$0") starten." >&2
  exit 2
fi
# scp zerlegt Backslashes im entfernten Pfad; Windows versteht Schraegstriche.
WIN_PROJECT_SCP="${WIN_PROJECT//\\//}"

SYNC=1
LOCAL_DEPS=""
FETCH_ONLY=""
CHECK_ONLY=""
SETUP=""
CREATE_APP=""
PS_ARGS=()

for arg in "$@"; do
  case "$arg" in
    --fast)     PS_ARGS+=(-SkipTests) ;;
    --no-clean) PS_ARGS+=(-NoClean) ;;
    --no-sync)  SYNC="" ;;
    --check)    CHECK_ONLY=1 ;;
    --fetch)    FETCH_ONLY=1 ;;
    --local-deps) LOCAL_DEPS=1 ;;
    --create-app) CREATE_APP=1; PS_ARGS+=(-CreateApp) ;;
    --console)    PS_ARGS+=(-KeepConsole) ;;
    --setup)    SETUP=1 ;;
    -h|--help)  sed -n '/^# Baut drivemount.exe/,/^# Ergebnis/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

cd "$PROJECT_DIR"

ssh_win()
{
  # -T: kein Pseudo-Terminal, sonst mischt PowerShell Steuerzeichen in die
  # Ausgabe. Fehlerstrom mitnehmen, die Warnungen des Clients filtern.
  ssh -T -o ConnectTimeout=15 "$WIN_HOST" "$@" 2>&1 \
    | grep -vE '^\*\* (WARNING|This session|The server)' || return "${PIPESTATUS[0]}"
}

fetch_binary()
{
  echo "==> Binary zurueckholen"
  mkdir -p target
  # ohne -q, damit ein Fehler sichtbar wird statt still zu verschwinden
  scp "$WIN_HOST:$WIN_PROJECT_SCP/target/drivemount.exe" target/drivemount.exe \
    2>&1 | grep -vE '^\*\* (WARNING|This session|The server)' || true
  if [[ ! -s target/drivemount.exe ]]; then
    echo "FEHLER: target/drivemount.exe wurde nicht uebertragen." >&2
    exit 1
  fi
  ls -la target/drivemount.exe

  # Mit --create-app kommt zusaetzlich der fertige App-Ordner als ZIP:
  # die EXE allein laeuft nicht, sie laedt zur Laufzeit die JDK-DLLs nach,
  # die native-image daneben legt.
  if [[ -n "$CREATE_APP" ]]; then
    scp "$WIN_HOST:$WIN_PROJECT_SCP/target/DriveMount-windows.zip" \
      target/DriveMount-windows.zip \
      2>&1 | grep -vE '^\*\* (WARNING|This session|The server)' || true
    if [[ ! -s target/DriveMount-windows.zip ]]; then
      echo "FEHLER: target/DriveMount-windows.zip wurde nicht uebertragen." >&2
      exit 1
    fi
    ls -la target/DriveMount-windows.zip
  fi
}


echo "==> Windows-Host: $WIN_HOST"
ssh_win 'Write-Output "    $(whoami) / $([Environment]::OSVersion.VersionString)"'

# Das PowerShell-Skript immer mituebertragen - es gehoert zum Projektstand.
REMOTE_PS="$WIN_STAGING/build-native-windows.ps1"
scp -q build-native-windows.ps1 "$WIN_HOST:$REMOTE_PS"

if [[ -n "$SETUP" ]]; then
  echo "==> Voraussetzungen nachinstallieren"
  ssh_win "powershell -NoProfile -ExecutionPolicy Bypass -File '$REMOTE_PS' -Setup"
  exit $?
fi

if [[ -n "$FETCH_ONLY" ]]; then
  fetch_binary
  exit 0
fi

if [[ -n "$SYNC" ]]; then
  echo "==> Quellen uebertragen nach $WIN_PROJECT"
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

  scp -q "$TARBALL" "$WIN_HOST:$WIN_STAGING/drivemount-src.tgz"

  # de.l9g-Artefakte mitliefern: die pom deklariert kein <repositories>, die
  # Bibliothek liegt also nur im lokalen ~/.m2 und waere drueben nicht
  # aufloesbar. Betrifft crypto-core/crypto-spring.
  # Normalerweise loest Maven de.l9g aus Maven Central auf. Nur wenn eine
  # Version dort noch nicht liegt (frisch gebaut, noch nicht deployt oder
  # noch nicht synchronisiert), muss sie von hier mitkommen: die pom
  # deklariert kein <repositories>, drueben gaebe es sonst keine Quelle.
  # Bewusst nicht automatisch - sonst baut das Skript stillschweigend gegen
  # eine Version, die es offiziell gar nicht gibt.
  L9G_CRYPTO_VERSION="$(sed -n 's|.*<l9g.crypto.version>\([^<]*\).*|\1|p' pom.xml | head -1)"
  if [[ -n "$LOCAL_DEPS" && -n "$L9G_CRYPTO_VERSION" \
    && -d "$HOME/.m2/repository/de/l9g" ]]; then
    echo "==> de.l9g-Artefakte ($L9G_CRYPTO_VERSION) aus ~/.m2 uebertragen"
    M2BALL="$(mktemp -t drivemount-m2).tgz"
    trap 'rm -f "$TARBALL" "$M2BALL"' EXIT
    # Nur was drueben gebraucht wird - das ganze de/l9g-Verzeichnis waere
    # ueber 600 MB gross (crypto-tool und die Vault-Beispiel-App).
    M2_ART=()
    for a in crypto-parent crypto-core crypto-spring; do
      [[ -d "$HOME/.m2/repository/de/l9g/$a/$L9G_CRYPTO_VERSION" ]] \
        && M2_ART+=("de/l9g/$a/$L9G_CRYPTO_VERSION")
    done
    tar czf "$M2BALL" -C "$HOME/.m2/repository" "${M2_ART[@]}"
    scp -q "$M2BALL" "$WIN_HOST:$WIN_STAGING/drivemount-m2.tgz"
    ssh_win "
      \$repo = Join-Path \$env:USERPROFILE '.m2\repository'
      if (-not (Test-Path \$repo)) { New-Item -ItemType Directory -Force -Path \$repo | Out-Null }
      Set-Location \$repo
      tar xzf '$WIN_STAGING/drivemount-m2.tgz'
      Remove-Item '$WIN_STAGING/drivemount-m2.tgz' -ErrorAction SilentlyContinue
      Write-Output "    de/l9g bereitgestellt"
    "
  fi
  # Quellen ersetzen, target/ drueben aber stehen lassen (Maven-Cache im
  # Zielverzeichnis spart Zeit beim naechsten Lauf).
  ssh_win "
    if (-not (Test-Path '$WIN_PROJECT')) { New-Item -ItemType Directory -Force -Path '$WIN_PROJECT' | Out-Null }
    Set-Location '$WIN_PROJECT'
    Remove-Item -Recurse -Force src -ErrorAction SilentlyContinue
    tar xzf '$WIN_STAGING/drivemount-src.tgz'
    Remove-Item '$WIN_STAGING/drivemount-src.tgz' -ErrorAction SilentlyContinue
    Write-Output \"    entpackt: \$((Get-ChildItem -Recurse src -File).Count) Quelldateien\"
  "
fi

if [[ -n "$CHECK_ONLY" ]]; then
  echo "==> Nur Preflight"
  ssh_win "powershell -NoProfile -ExecutionPolicy Bypass -File '$REMOTE_PS' \
    -NikHome '$WIN_NIK_HOME' -ProjectDir '$WIN_PROJECT' -Check"
  exit $?
fi

echo "==> Build starten"
# -CompanyName nur mitgeben, wenn ein Wert da ist. Ein leeres Argument
# ueberlebt den Weg durch ssh und PowerShell nicht: die Anfuehrungszeichen
# fallen unterwegs weg, uebrig bleibt ein Parameter ohne Wert, und
# PowerShell bricht mit "Fehlendes Argument fuer den Parameter CompanyName"
# ab. Das Skript drueben hat ohnehin '' als Vorgabe.
COMPANY_ARG=""
if [[ -n "$WIN_COMPANY_NAME" ]]; then
  COMPANY_ARG="-CompanyName '$WIN_COMPANY_NAME'"
fi

ssh_win "powershell -NoProfile -ExecutionPolicy Bypass -File '$REMOTE_PS' \
  -NikHome '$WIN_NIK_HOME' -ProjectDir '$WIN_PROJECT' \
  $COMPANY_ARG ${PS_ARGS[*]:-}"

fetch_binary
