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
# Baut drivemount fuer macOS auf Intel (x86_64). GraalVM kann nicht cross-
# compilieren, auch nicht zwischen zwei Mac-Architekturen - ein Intel-Binary
# entsteht nur auf einem Intel-Mac. Das Skript kopiert die Quellen per ssh
# dorthin, laesst dort dasselbe BUILD_NATIVE_MACOS.sh laufen wie hier und
# holt das Binary zurueck.
#
# Signiert und notarisiert wird dagegen HIER, nicht auf dem Intel-Mac: dort
# liegt kein Developer-ID-Zertifikat, und ueber ssh ist der Schluesselbund
# ohnehin gesperrt. codesign und notarytool ist die Architektur des Binaries
# gleichgueltig - ein Apple-Silicon-Mac signiert ein Intel-Binary genauso.
# Das Zertifikat verlaesst so diesen Rechner nie.
#
#   ./BUILD_NATIVE_MACOS_INTEL.sh              Quellen kopieren, bauen, Binary holen
#   ./BUILD_NATIVE_MACOS_INTEL.sh --fast       ohne Tests
#   ./BUILD_NATIVE_MACOS_INTEL.sh --no-sync    ohne Kopieren (Stand drueben nutzen)
#   ./BUILD_NATIVE_MACOS_INTEL.sh --check      nur Voraussetzungen pruefen
#   ./BUILD_NATIVE_MACOS_INTEL.sh --fetch      nur das fertige Binary holen
#   ./BUILD_NATIVE_MACOS_INTEL.sh --setup      NIK drueben ssh-tauglich machen
#                                              (einmalig und nach jedem NIK-Update,
#                                              siehe unten)
#   ./BUILD_NATIVE_MACOS_INTEL.sh --create-app zusaetzlich hier DriveMount.app
#                                              bauen, signieren, notarisieren ->
#                                              target/DriveMount-macos-x86_64.zip
#   ./BUILD_NATIVE_MACOS_INTEL.sh --no-notarize  nur zusammen mit --create-app
#
# Umgebung: kommt aus ./.env (siehe env.sample), einzeln uebersteuerbar
# ueber die Umgebung.
#
#   MACOS_INTEL_HOST        ssh-Ziel des Intel-Macs (Pflicht)
#   MACOS_INTEL_PROJECT     Projektverzeichnis drueben, relativ zum Home
#   MACOS_INTEL_NIK_SOURCE  die installierte Liberica NIK Full drueben
#   MACOS_INTEL_NIK_HOME    die flache Kopie davon, mit der gebaut wird
#
# Voraussetzungen drueben: Liberica NIK *Full* (x86_64) und die Command Line
# Tools. Maven nicht - BUILD_NATIVE_MACOS.sh nimmt den Wrapper, wenn kein
# mvn auf dem PATH liegt.
#
# Warum eine Kopie der NIK? Zwei Fallen, beide auf dem Intel-Mac gemessen,
# und beide aeussern sich gleich: java und native-image starten ueber ssh
# nicht und geben keinen Laut von sich.
#
#  1. Quarantaene. Eine per Browser geladene NIK traegt auf jeder Datei
#     com.apple.quarantine. Gatekeeper will beim ersten Start nachfragen,
#     ueber ssh gibt es keinen Dialog - der Aufruf haengt, bis jemand ihn
#     abschiesst.
#
#  2. Das gebrochene Bundle-Siegel. Die NIK kommt als macOS-Bundle
#     (.../Contents/Home), und dessen Siegel stimmt nicht: "codesign --verify
#     --deep" meldet "invalid Info.plist (plist or signature have been
#     modified)" - bei der Intel- UND der Apple-Silicon-NIK, es liegt also an
#     BellSofts Paketierung (bin/native-image und die NIK-Lizenztexte sind
#     nach dem Versiegeln des JDK hinzugekommen). Jedes einzelne Programm
#     darin ist dagegen korrekt signiert ("valid on disk"). Ueber ssh prueft
#     macOS ein Programm aber im Kontext seines Bundles: syspolicyd rechnet
#     rund 160 s ueber das ganze Bundle, verwirft es (MacOS error -67030) und
#     beendet den Prozess mit SIGKILL. In Maven saehe das wie ein
#     unerklaerlicher Absturz aus.
#
#     Hier auf dem Apple-Silicon-Mac faellt das nicht auf, weil java dort aus
#     dem Terminal gestartet wird und nicht ueber ssh.
#
# --setup legt deshalb eine flache Kopie von .../Contents/Home an, ausserhalb
# jeder Bundle-Huelle, und laesst die Quarantaene beim Kopieren weg (ditto
# --noqtn). Dann prueft macOS jedes Programm fuer sich, und das besteht.
# Die installierte NIK bleibt unangetastet. Gemessen: java -version in 3 s,
# native-image --version in 1 s, statt Abbruch nach 162 s.
#
# Ergebnis: target/drivemount-macos-x86_64
#           (mit --create-app zusaetzlich target/DriveMount-macos-x86_64.zip)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Werte dieser Installation aus ./.env holen; bereits gesetzte
# Umgebungsvariablen bleiben unangetastet.
# shellcheck source=load-env.sh
[[ -f "$PROJECT_DIR/load-env.sh" ]] && . "$PROJECT_DIR/load-env.sh" \
  && drivemount_load_env "$PROJECT_DIR/.env"

MACOS_INTEL_HOST="${MACOS_INTEL_HOST:-}"
MACOS_INTEL_PROJECT="${MACOS_INTEL_PROJECT:-Projects/l9g-drivemount}"
MACOS_INTEL_NIK_SOURCE="${MACOS_INTEL_NIK_SOURCE:-/opt/nik/25-full}"
MACOS_INTEL_NIK_HOME="${MACOS_INTEL_NIK_HOME:-/opt/nik/25-full-flat}"

if [[ -z "$MACOS_INTEL_HOST" ]]; then
  echo "FEHLER: MACOS_INTEL_HOST ist nicht gesetzt." >&2
  echo "        Eintrag in ./.env ergaenzen (Vorlage: env.sample) oder" >&2
  echo "        einmalig mit MACOS_INTEL_HOST=... $(basename "$0") starten." >&2
  exit 2
fi

# Lokal anders benannt als drueben: target/drivemount ist auf diesem Rechner
# schon das Apple-Silicon-Binary.
LOCAL_BINARY="target/drivemount-macos-x86_64"

SYNC=1
CHECK_ONLY=""
FETCH_ONLY=""
SETUP=""
CREATE_APP=""
BUILD_ARGS=()
PACKAGE_ARGS=()

for arg in "$@"; do
  case "$arg" in
    --fast)        BUILD_ARGS+=(--fast) ;;
    --no-clean)    BUILD_ARGS+=(--no-clean) ;;
    --no-sync)     SYNC="" ;;
    --check)       CHECK_ONLY=1 ;;
    --fetch)       FETCH_ONLY=1 ;;
    --setup)       SETUP=1 ;;
    --create-app)  CREATE_APP=1 ;;
    --no-notarize) PACKAGE_ARGS+=(--no-notarize) ;;
    -h|--help)     sed -n '/^# Baut drivemount fuer macOS auf Intel/,/^# Ergebnis/p' "$0" \
                     | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

cd "$PROJECT_DIR"

# Alles drueben laeuft durch bash und nicht durch die Login-Shell (zsh unter
# macOS): die Befehle hier sind bash, und so bleibt es eindeutig.
ssh_intel()
{
  ssh -T -o ConnectTimeout=15 -o BatchMode=yes "$MACOS_INTEL_HOST" "bash -c $(printf '%q' "$1")"
}

# Drueben auszuwertende Pruefungen, als Text. Beide starten weder java noch
# native-image - genau das wuerde in den beschriebenen Faellen haengen.
#
#   IN_BUNDLE: 0, wenn NIK_HOME in einer .../Contents/Home-Huelle liegt
#   QUARANTINED: 0, wenn java das Quarantaene-Attribut traegt
IN_BUNDLE="case \"\$(cd -P '$MACOS_INTEL_NIK_HOME' 2>/dev/null && pwd -P)\" in */Contents/Home) true ;; *) false ;; esac"
QUARANTINED="xattr -p com.apple.quarantine '$MACOS_INTEL_NIK_HOME/bin/java' >/dev/null 2>&1"

fetch_binary()
{
  echo "==> Binary zurueckholen"
  mkdir -p target
  scp -q "$MACOS_INTEL_HOST:$MACOS_INTEL_PROJECT/target/drivemount" "$LOCAL_BINARY"
  if [[ ! -s "$LOCAL_BINARY" ]]; then
    echo "FEHLER: $LOCAL_BINARY wurde nicht uebertragen." >&2
    exit 1
  fi
  # Sicherheitsnetz gegen den falschen Rechner in .env: ein arm64-Binary
  # hier als "x86_64" zu verteilen, waere der schlimmste moegliche Fehler.
  local arch
  arch="$(lipo -archs "$LOCAL_BINARY" 2>/dev/null || echo '?')"
  if [[ "$arch" != "x86_64" ]]; then
    echo "FEHLER: $LOCAL_BINARY ist '$arch', nicht x86_64." >&2
    exit 1
  fi
  ls -la "$LOCAL_BINARY"
  echo "    Architektur: $arch"
}

echo "==> Intel-Mac: $MACOS_INTEL_HOST"
ssh_intel 'echo "    $(whoami)@$(hostname -s) / macOS $(sw_vers -productVersion) / $(uname -m)"'

if [[ "$(ssh_intel 'uname -m')" != "x86_64" ]]; then
  echo "FEHLER: $MACOS_INTEL_HOST ist kein Intel-Mac (uname -m != x86_64)." >&2
  exit 1
fi

# ------------------------------------------------------------------ --setup
if [[ -n "$SETUP" ]]; then
  echo "==> NIK ssh-tauglich machen (flache Kopie ohne Bundle-Huelle)"
  ssh_intel "
    set -e
    src=\$(cd -P '$MACOS_INTEL_NIK_SOURCE' 2>/dev/null && pwd -P || true)
    dst='$MACOS_INTEL_NIK_HOME'
    if [ -z \"\$src\" ] || [ ! -x \"\$src/bin/native-image\" ]; then
      echo '    FEHLER: keine NIK unter $MACOS_INTEL_NIK_SOURCE'; exit 1
    fi
    if [ ! -e \"\$src/jmods/javafx.controls.jmod\" ]; then
      echo '    FEHLER: \$src ist nicht die Full-Variante (LibericaFX fehlt)'; exit 1
    fi
    echo \"    Quelle : \$src\"
    echo \"    Ziel   : \$dst\"

    # Loeschen nur, was nach einer frueheren Kopie aussieht - nie die Quelle
    # selbst und nichts ohne JDK-Kennung. Ein falsch gesetztes
    # MACOS_INTEL_NIK_HOME darf keinen Schaden anrichten.
    if [ -e \"\$dst\" ]; then
      real=\$(cd -P \"\$dst\" && pwd -P)
      case \"\$src\" in \"\$real\"|\"\$real\"/*) echo '    FEHLER: Ziel ist die Quelle selbst'; exit 1 ;; esac
      [ -f \"\$dst/release\" ] || { echo '    FEHLER: Ziel existiert und ist keine JDK-Kopie'; exit 1; }
      rm -rf \"\$dst\"
    fi

    s=\$(date +%s)
    ditto --noqtn \"\$src\" \"\$dst\"
    echo \"    kopiert: \$(du -sh \"\$dst\" | cut -f1) in \$(( \$(date +%s) - s )) s\"

    # Probe mit Zeitgrenze: so sieht man sofort, ob es diesmal geht, statt
    # dass erst der Build haengt.
    '$MACOS_INTEL_NIK_HOME/bin/native-image' --version > /tmp/drivemount-nik-probe 2>&1 &
    p=\$!
    for i in \$(seq 1 60); do kill -0 \$p 2>/dev/null || break; sleep 1; done
    if kill -0 \$p 2>/dev/null; then
      kill -9 \$p 2>/dev/null
      echo '    FEHLER: native-image startet auch aus der Kopie nicht (60 s)'
      exit 1
    fi
    wait \$p
    echo \"    Probe  : \$(head -1 /tmp/drivemount-nik-probe) (\${i} s)\"
    rm -f /tmp/drivemount-nik-probe
  "
  exit $?
fi

# ------------------------------------------------------------------ --check
if [[ -n "$CHECK_ONLY" ]]; then
  echo "==> Preflight"
  ssh_intel "
    printf '    NIK            : '; [ -x '$MACOS_INTEL_NIK_HOME/bin/native-image' ] && echo '$MACOS_INTEL_NIK_HOME' || echo 'FEHLT (--setup)'
    printf '    LibericaFX     : '; [ -e '$MACOS_INTEL_NIK_HOME/jmods/javafx.controls.jmod' ] && echo ok || echo 'FEHLT (Full-Variante noetig)'
    printf '    Bundle-Huelle  : '; if $IN_BUNDLE; then echo 'JA - Siegel gebrochen, startet ueber ssh nicht (--setup)'; else echo 'nein (gut)'; fi
    printf '    Quarantaene    : '; if $QUARANTINED; then echo 'GESETZT (--setup)'; else echo 'frei'; fi
    printf '    C-Toolchain    : '; xcrun --find clang >/dev/null 2>&1 && echo ok \
      || { [ -x /Library/Developer/CommandLineTools/usr/bin/clang ] && echo 'ok (Command Line Tools)' || echo 'FEHLT (xcode-select --install)'; }
    printf '    Maven          : '; command -v mvn >/dev/null && echo mvn || echo 'Wrapper (mvnw)'
    printf '    Maven Central  : '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 https://repo.maven.apache.org/maven2/ || echo 'nicht erreichbar'
    printf '    Projektstand   : '; [ -e '$MACOS_INTEL_PROJECT/pom.xml' ] && echo ok || echo 'fehlt (ohne --no-sync starten)'
    printf '    Platz          : '; df -h / | tail -1 | awk '{print \$4\" frei\"}'
  "
  exit 0
fi

if [[ -n "$FETCH_ONLY" ]]; then
  fetch_binary
  exit 0
fi

# Vor dem Kopieren und Bauen: beide Fallen fuehren sonst zu einem Build, der
# minutenlang stumm steht und dann ohne Erklaerung stirbt.
if ! ssh_intel "[ -x '$MACOS_INTEL_NIK_HOME/bin/native-image' ]"; then
  echo "FEHLER: keine NIK unter $MACOS_INTEL_NIK_HOME - einmalig:" >&2
  echo "          $(basename "$0") --setup" >&2
  exit 1
fi
if ssh_intel "$IN_BUNDLE" || ssh_intel "$QUARANTINED"; then
  echo "FEHLER: $MACOS_INTEL_NIK_HOME liegt in einer Bundle-Huelle oder unter" >&2
  echo "        Quarantaene - java und native-image starten ueber ssh dann nicht" >&2
  echo "        (siehe Kopf dieses Skripts). Einmalig beheben mit:" >&2
  echo "          $(basename "$0") --setup" >&2
  exit 1
fi

if [[ -n "$SYNC" ]]; then
  echo "==> Quellen uebertragen nach $MACOS_INTEL_HOST:$MACOS_INTEL_PROJECT"
  # target/ und .git/ bleiben draussen. application.yaml und
  # assets/secret.bin muessen dagegen mit: sie sind zwar gitignored, liegen
  # aber unter src/ und werden ins Binary eingebaut - ohne sie entsteht ein
  # Binary, das sich nicht anmelden kann.
  TARBALL="$(mktemp -t drivemount-src).tgz"
  trap 'rm -f "$TARBALL"' EXIT
  # COPYFILE_DISABLE: sonst packt macOS-tar erweiterte Attribute als
  # ._-Dateien mit ein.
  COPYFILE_DISABLE=1 tar czf "$TARBALL" \
    --exclude='./target' --exclude='./.git' --exclude='./.idea' \
    --exclude='.DS_Store' \
    ./pom.xml ./mvnw ./mvnw.cmd ./.mvn \
    ./src ./packaging ./*.sh ./*.ps1 ./*.md ./LICENSE 2>/dev/null
  echo "    $(du -h "$TARBALL" | cut -f1)"

  scp -q "$TARBALL" "$MACOS_INTEL_HOST:/tmp/drivemount-src.tgz"
  ssh_intel "
    set -e
    mkdir -p '$MACOS_INTEL_PROJECT'
    cd '$MACOS_INTEL_PROJECT'
    rm -rf src
    tar xzf /tmp/drivemount-src.tgz
    rm -f /tmp/drivemount-src.tgz
    chmod +x ./*.sh ./mvnw
    echo \"    entpackt: \$(find src -type f | wc -l | tr -d ' ') Quelldateien\"
  "
fi

echo "==> Build starten"
# Drueben laeuft dasselbe BUILD_NATIVE_MACOS.sh - ohne --create-app, denn
# signiert wird hier. NIK_HOME durchreichen; eine .env gibt es drueben nicht.
ssh_intel "cd '$MACOS_INTEL_PROJECT' && NIK_HOME='$MACOS_INTEL_NIK_HOME' ./BUILD_NATIVE_MACOS.sh ${BUILD_ARGS[*]:-}"

fetch_binary

# ---------------------------------------------------------------- App-Bundle
# Verpacken, signieren und notarisieren uebernimmt BUILD_NATIVE_MACOS.sh
# selbst - mit --package-binary baut es nicht, sondern nimmt das geholte
# Binary. Dieselbe Info.plist, dieselbe Signatur, dieselbe Notarisierung wie
# beim Apple-Silicon-Paket; nur der Ablageort traegt die Architektur.
if [[ -n "$CREATE_APP" ]]; then
  echo
  ./BUILD_NATIVE_MACOS.sh "--package-binary=$PROJECT_DIR/$LOCAL_BINARY" \
    ${PACKAGE_ARGS[@]+"${PACKAGE_ARGS[@]}"}
fi
