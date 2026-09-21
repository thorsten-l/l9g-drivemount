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
# Baut die Anwendungspakete fuer alle drei Plattformen und sammelt sie nach
# distrib/. Reine Klammer um die vier vorhandenen Skripte - die Arbeit machen
# die, hier steht nur die richtige Reihenfolge und die Buchfuehrung darueber.
#
#   ./BUILD_ALL_APPS.sh                  alle drei bauen und einsammeln
#   ./BUILD_ALL_APPS.sh --fast           ohne Tests (an alle drei durchgereicht)
#   ./BUILD_ALL_APPS.sh --no-notarize    macOS ohne Notarisierung (spart Minuten)
#   ./BUILD_ALL_APPS.sh --skip-macos     einzelne Plattform auslassen
#   ./BUILD_ALL_APPS.sh --skip-windows
#   ./BUILD_ALL_APPS.sh --skip-linux
#   ./BUILD_ALL_APPS.sh --no-distrib     nicht einsammeln
#
# Die Reihenfolge ist nicht beliebig: BUILD_NATIVE_MACOS.sh startet mit
# "mvn clean" und raeumt target/ ab. Es muss deshalb zuerst laufen - die
# beiden anderen bauen auf ihren eigenen Rechnern und legen ihr Ergebnis
# danach in dasselbe target/. Umgekehrt waeren die fertigen Pakete wieder weg.
#
# Ein Fehlschlag bricht NICHT ab. Ein abgeschalteter Windows- oder
# Linux-Rechner soll nicht die beiden anderen Pakete kosten; was fehlt, steht
# am Ende in der Zusammenfassung, und der Exit-Code ist dann ungleich 0.
#
# Dauer: grob eine Viertelstunde. Der macOS-Teil notarisiert (Netz noetig),
# die beiden anderen bauen per ssh auf ihren Zielrechnern.
#
# Die Zusammenfassung nennt neben der Gesamtdauer je Schritt auch die reine
# Zeit von native-image. Die steht in dessen Abschlusszeile
# ("Finished generating 'drivemount' in 2m 31s.") und ist die einzige Zahl,
# die sich zwischen den Plattformen wirklich vergleichen laesst - alles
# andere enthaelt Tests, Uebertragung per ssh, Signatur und Notarisierung.
# Die vollstaendige Ausgabe jedes Schritts bleibt unter .build-logs/ liegen.

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Bash 3.2 (macOS-Standard) bricht unter "set -u" schon beim Expandieren
# eines leeren Arrays ab. Deshalb ueberall die Form ${A[@]+"${A[@]}"}.
PASSTHROUGH=()
MACOS_EXTRA=()
SKIP_MACOS=""
SKIP_WINDOWS=""
SKIP_LINUX=""
RUN_DISTRIB=1

for arg in "$@"; do
  case "$arg" in
    --fast)         PASSTHROUGH+=(--fast) ;;
    --no-notarize)  MACOS_EXTRA+=(--no-notarize) ;;
    --skip-macos)   SKIP_MACOS=1 ;;
    --skip-windows) SKIP_WINDOWS=1 ;;
    --skip-linux)   SKIP_LINUX=1 ;;
    --no-distrib)   RUN_DISTRIB="" ;;
    -h|--help)      sed -n '/^# Baut die Anwendungspakete/,/^# die beiden anderen/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

# --no-notarize kennt nur das macOS-Skript; die anderen wuerden mit
# "Unbekannte Option" abbrechen.

pom_value()
{
  sed -n '/<\/parent>/,$p' pom.xml \
    | sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" | head -1
}

VERSION="$(pom_value version)"

# Ausgabe jedes Schritts mitschneiden. Bewusst NICHT unter target/ - der
# macOS-Build beginnt mit "mvn clean", und eine Datei, die waehrend des
# Schreibens weggeraeumt wird, ist anschliessend leer.
LOG_DIR="$PROJECT_DIR/.build-logs"
mkdir -p "$LOG_DIR"

hms()
{
  local s="$1"
  printf '%dm %02ds' $(( s / 60 )) $(( s % 60 ))
}

# Ergebnisse je Schritt, fuer die Zusammenfassung am Ende.
STEP_NAMES=()
STEP_STATUS=()
STEP_TIME=()
STEP_GRAAL=()
STEP_RESULT=()
STEP_INDEX=0

# Holt die Dauer aus der Abschlusszeile von native-image:
#   "Finished generating 'drivemount' in 2m 31s."
# Zuerst ANSI-Sequenzen entfernen - native-image faerbt seine Ausgabe, und
# die Steuerzeichen stehen mitten in der Zeile. Gibt es mehrere Zeilen
# (Windows baut nach dem Agent-Lauf erneut), zaehlt die letzte.
graal_time()
{
  local log="$1"
  [[ -f "$log" ]] || return 0
  LC_ALL=C sed $'s/\033\[[0-9;]*[a-zA-Z]//g' "$log" \
    | grep -aoE "Finished generating '[^']+' in [^.]+" \
    | tail -1 \
    | sed -E "s/.* in //"
}

run_step()
{
  local name="$1" artifact="$2"; shift 2

  STEP_INDEX=$(( STEP_INDEX + 1 ))

  if [[ "$1" == "SKIP" ]]; then
    STEP_NAMES+=("$name"); STEP_STATUS+=("uebersprungen")
    STEP_TIME+=("-"); STEP_GRAAL+=("-"); STEP_RESULT+=("-")
    echo
    echo "############################################################"
    echo "# $name - uebersprungen"
    echo "############################################################"
    return 0
  fi

  echo
  echo "############################################################"
  echo "# $name"
  echo "#   $*"
  echo "#   Start: $(date '+%H:%M:%S')"
  echo "############################################################"

  local log="$LOG_DIR/step-$STEP_INDEX.log"
  local start rc end
  start="$(date +%s)"
  # Mitschneiden und gleichzeitig durchreichen. stderr wandert dabei nach
  # stdout, sonst fehlte im Log die Haelfte. rc kommt aus PIPESTATUS, sonst
  # wuerde der Exit-Code von tee gemessen.
  "$@" 2>&1 | tee "$log"
  rc="${PIPESTATUS[0]}"
  end="$(date +%s)"

  local elapsed; elapsed="$(hms $(( end - start )))"
  local result="-"
  local graal; graal="$(graal_time "$log")"
  [[ -n "$graal" ]] || graal="-"

  if [[ -e "$artifact" ]]; then
    # ls statt du: du meldet belegte Bloecke und macht aus 304 Byte 4.0K.
    result="$(ls -lh "$artifact" 2>/dev/null | awk '{print $5}')"
  fi

  STEP_NAMES+=("$name")
  STEP_TIME+=("$elapsed")
  STEP_GRAAL+=("$graal")
  STEP_RESULT+=("$result")

  if [[ $rc -eq 0 && -e "$artifact" ]]; then
    STEP_STATUS+=("ok")
    echo "--- $name: fertig nach $elapsed (native-image $graal)"
    echo "    -> $artifact ($result)"
  elif [[ $rc -eq 0 ]]; then
    STEP_STATUS+=("kein Artefakt")
    echo "--- $name: Exit 0 nach $elapsed, aber $artifact fehlt" >&2
  else
    STEP_STATUS+=("FEHLER (Exit $rc)")
    echo "--- $name: abgebrochen nach $elapsed, Exit $rc" >&2
  fi
  return 0
}

# ------------------------------------------------------------------ Preflight
echo "============================================================"
echo " DriveMount - Pakete fuer alle Plattformen"
echo "============================================================"
echo "  Projekt     : $PROJECT_DIR"
echo "  Version     : ${VERSION:-unbekannt}"
echo "  Beginn      : $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Optionen    : ${PASSTHROUGH[*]:-keine}${MACOS_EXTRA[*]:+ ${MACOS_EXTRA[*]} (nur macOS)}"
echo

MISSING=""
for f in BUILD_NATIVE_MACOS.sh BUILD_NATIVE_WINDOWS.sh BUILD_NATIVE_LINUX.sh DISTRIB.sh; do
  if [[ -x "$f" ]]; then
    printf '  %-28s vorhanden\n' "$f"
  else
    printf '  %-28s FEHLT oder nicht ausfuehrbar\n' "$f"
    MISSING=1
  fi
done

# Ohne .env fehlen den beiden Remote-Builds die ssh-Ziele; sie brechen dann
# einzeln ab. Kein Abbruch hier - ein reiner macOS-Lauf geht auch ohne.
if [[ -f .env ]]; then
  printf '  %-28s vorhanden\n' ".env"
else
  printf '  %-28s FEHLT - "cp env.sample .env" (nur macOS baut ohne)\n' ".env"
fi

# Ohne diese beiden Dateien entsteht zwar ein Binary, aber keines, das sich
# anmelden kann - siehe PREBUILD.sh.
for f in src/main/resources/application.yaml src/main/resources/assets/secret.bin; do
  if [[ -f "$f" ]]; then
    printf '  %-28s vorhanden\n' "$(basename "$f")"
  else
    printf '  %-28s FEHLT - erst ./PREBUILD.sh laufen lassen\n' "$(basename "$f")"
    MISSING=1
  fi
done

if [[ -n "$MISSING" ]]; then
  echo
  echo "Abbruch: Voraussetzungen unvollstaendig (siehe oben)." >&2
  exit 1
fi

TOTAL_START="$(date +%s)"

# ------------------------------------------------------------------- Schritte
# macOS zuerst: nur dieses Skript raeumt target/ lokal mit "mvn clean" ab.
if [[ -n "$SKIP_MACOS" ]]; then
  run_step "1/4  macOS  (lokal)" "" SKIP
else
  run_step "1/4  macOS  (lokal)" "target/DriveMount-macos.zip" \
    ./BUILD_NATIVE_MACOS.sh --create-app \
      ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"} ${MACOS_EXTRA[@]+"${MACOS_EXTRA[@]}"}
fi

if [[ -n "$SKIP_WINDOWS" ]]; then
  run_step "2/4  Windows (ssh)" "" SKIP
else
  run_step "2/4  Windows (ssh)" "target/DriveMount-windows.zip" \
    ./BUILD_NATIVE_WINDOWS.sh --create-app ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
fi

if [[ -n "$SKIP_LINUX" ]]; then
  run_step "3/4  Linux   (ssh)" "" SKIP
else
  run_step "3/4  Linux   (ssh)" "target/DriveMount-linux.tar.gz" \
    ./BUILD_NATIVE_LINUX.sh --create-app ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
fi

if [[ -n "$RUN_DISTRIB" ]]; then
  run_step "4/4  Einsammeln" "distrib/SHA256SUMS" ./DISTRIB.sh
else
  run_step "4/4  Einsammeln" "" SKIP
fi

# --------------------------------------------------------------- Zusammenfassung
TOTAL_END="$(date +%s)"

echo
echo "============================================================"
echo " Zusammenfassung"
echo "============================================================"
printf '  %-22s %-18s %-10s %-12s %s\n' \
  "Schritt" "Status" "Dauer" "native-image" "Groesse"
printf '  %-22s %-18s %-10s %-12s %s\n' \
  "----------------------" "------------------" "----------" "------------" "-------"

FAILED=0
for i in "${!STEP_NAMES[@]}"; do
  printf '  %-22s %-18s %-10s %-12s %s\n' \
    "${STEP_NAMES[$i]}" "${STEP_STATUS[$i]}" "${STEP_TIME[$i]}" \
    "${STEP_GRAAL[$i]}" "${STEP_RESULT[$i]}"
  case "${STEP_STATUS[$i]}" in
    ok|uebersprungen) ;;
    *) FAILED=$(( FAILED + 1 )) ;;
  esac
done

echo
echo "  Gesamtdauer : $(hms $(( TOTAL_END - TOTAL_START )))"
echo "  Ende        : $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Logs        : ${LOG_DIR#$PROJECT_DIR/}/step-*.log"

if [[ -d distrib ]]; then
  echo
  echo "  distrib/ (Version ${VERSION:-?})"
  ls -lh distrib 2>/dev/null | tail -n +2 | sed 's/^/    /'
fi

echo
if [[ $FAILED -eq 0 ]]; then
  echo "==> Alle Schritte erfolgreich."
  exit 0
fi

echo "==> $FAILED Schritt(e) fehlgeschlagen - Ausgabe oben pruefen." >&2
echo "    Einzeln nachziehen und danach ./DISTRIB.sh erneut aufrufen;" >&2
echo "    ein weiterer macOS-Build wuerde die anderen Pakete loeschen." >&2
exit 1
