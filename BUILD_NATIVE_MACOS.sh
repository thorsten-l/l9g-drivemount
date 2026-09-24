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
# Baut drivemount (Cross-Platform SMB-Mounter) als natives Binary mit
# GraalVM Native Image (Liberica NIK *Full* — LibericaFX wird zwingend
# benoetigt, sonst schlaegt --add-modules javafx.* fehl).
#
#   ./BUILD_NATIVE_MACOS.sh                 JVM-Tests + Binary (Default)
#   ./BUILD_NATIVE_MACOS.sh --fast          nur Binary, keine Tests (schnelle Iteration)
#   ./BUILD_NATIVE_MACOS.sh --no-clean      ohne "mvn clean"
#   ./BUILD_NATIVE_MACOS.sh --offline       Maven offline (-o)
#   ./BUILD_NATIVE_MACOS.sh --agent         nur Reachability-Metadaten erzeugen
#                                           (App auf der JVM mit Tracing-Agent
#                                           starten; danach alle UI-Pfade einmal
#                                           durchspielen)
#   ./BUILD_NATIVE_MACOS.sh --create-app    zusaetzlich ein macOS-App-Bundle bauen
#                                           (target/DriveMount.app + .zip; Icon
#                                           aus packaging/drivemount.icns).
#                                           Signiert mit dem Developer-ID-
#                                           Zertifikat, falls eins im
#                                           Schluesselbund liegt, und notarisiert.
#   ./BUILD_NATIVE_MACOS.sh --no-notarize   Notarisierung ueberspringen (schneller)
#   ./BUILD_NATIVE_MACOS.sh --package-binary=PFAD
#                                           nicht bauen, sondern ein anderswo
#                                           gebautes macOS-Binary verpacken,
#                                           signieren und notarisieren (fuer
#                                           BUILD_NATIVE_MACOS_INTEL.sh: der
#                                           Intel-Mac baut, signiert wird hier,
#                                           wo Zertifikat und Notar-Profil
#                                           liegen). Ergebnis nach Architektur
#                                           benannt, z.B.
#                                           target/DriveMount-macos-x86_64.zip
#
# Umgebung: kommt aus ./.env (siehe env.sample), einzeln uebersteuerbar
# ueber die Umgebung.
#
#   NIK_HOME               Liberica NIK Full (Default: /opt/nik/25-full)
#   MACOS_SIGN_IDENTITY    Signaturzertifikat; leer = selbst suchen
#   MACOS_NOTARY_PROFILE   Schluesselbund-Profil fuer notarytool
#
# Ergebnis: target/drivemount  (mit --create-app zusaetzlich DriveMount.app)
#           --package-binary: target/app-macos-<arch>/DriveMount.app und
#                             target/DriveMount-macos-<arch>.zip

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Werte dieser Installation aus ./.env holen; bereits gesetzte
# Umgebungsvariablen bleiben unangetastet. Das Skript laeuft auch auf dem
# Linux-Build-Rechner - dort gibt es keine .env, dann greifen die Vorgaben.
# shellcheck source=load-env.sh
[[ -f "$PROJECT_DIR/load-env.sh" ]] && . "$PROJECT_DIR/load-env.sh" \
  && drivemount_load_env "$PROJECT_DIR/.env"

NIK_HOME="${NIK_HOME:-/opt/nik/25-full}"
AGENT_CONFIG_DIR="src/main/resources/META-INF/native-image"

CLEAN="clean"
AGENT_ONLY=""
CREATE_APP=""
MVN_ARGS=(-Pnative)

APP_NAME="DriveMount"
APP_ID="l9g.app.drivemount"

# Schluesselbund-Profil fuer xcrun notarytool, angelegt mit
#   xcrun notarytool store-credentials "drivemount" --apple-id ... --team-id ...
NOTARY_PROFILE="${MACOS_NOTARY_PROFILE:-drivemount}"
NO_NOTARIZE=""
PACKAGE_BINARY=""

for arg in "$@"; do
  case "$arg" in
    --fast)               MVN_ARGS+=(-DskipTests) ;;
    --no-clean)           CLEAN="" ;;
    --offline|-o)         MVN_ARGS+=(-o) ;;
    --agent)              AGENT_ONLY="1" ;;
    --create-app)         CREATE_APP="1" ;;
    --no-notarize)        NO_NOTARIZE="1" ;;
    --package-binary=*)   PACKAGE_BINARY="${arg#*=}"; CREATE_APP="1" ;;
    -h|--help)            sed -n '/^# Baut /,/^# Ergebnis/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

# macOS-Bundle-Layout (…/Contents/Home) tolerieren
if [[ ! -x "$NIK_HOME/bin/native-image" && -x "$NIK_HOME/Contents/Home/bin/native-image" ]]; then
  NIK_HOME="$NIK_HOME/Contents/Home"
fi
if [[ ! -x "$NIK_HOME/bin/native-image" ]]; then
  echo "FEHLER: native-image nicht gefunden unter $NIK_HOME/bin — NIK_HOME setzen." >&2
  exit 1
fi

# Plattform-Toolchain pruefen. native-image braucht einen C-Compiler/Linker;
# auf macOS meldet es sonst nur "Unable to detect supported DARWIN native
# software development toolchain" ohne die eigentliche Ursache.
case "$(uname -s)" in
  Darwin)
    # Haeufigster Stolperstein: nach einem Xcode-Update ist die Lizenz nur
    # fuer die alte Version bestaetigt, dann blockiert *jeder* clang-Aufruf.
    # Die Command Line Tools sind davon nicht betroffen - darauf ausweichen,
    # statt sudo zu verlangen.
    if ! xcrun --find clang >/dev/null 2>&1; then
      if [[ -x /Library/Developer/CommandLineTools/usr/bin/clang ]]; then
        export DEVELOPER_DIR=/Library/Developer/CommandLineTools
        echo "    HINWEIS: Xcode-Toolchain nicht nutzbar (Lizenz?), weiche auf"
        echo "             die Command Line Tools aus: DEVELOPER_DIR=$DEVELOPER_DIR"
      else
        echo "FEHLER: keine nutzbare C-Toolchain:" >&2
        xcrun --find clang 2>&1 | sed 's/^/       /' >&2
        echo "       Abhilfe: 'xcode-select --install' (Command Line Tools) oder" >&2
        echo "                'sudo xcodebuild -license accept' (Xcode)." >&2
        exit 1
      fi
    fi
    ;;
  Linux)
    command -v cc >/dev/null 2>&1 || {
      echo "FEHLER: kein C-Compiler gefunden (build-essential/gcc + zlib-devel noetig)." >&2
      exit 1
    }
    ;;
esac

# Full-Variante pruefen: ohne LibericaFX fehlen die javafx.*-Module und der
# Build bricht erst nach Minuten im native-image-Schritt ab.
if [[ ! -e "$NIK_HOME/jmods/javafx.controls.jmod" && ! -e "$NIK_HOME/lib/javafx.properties" ]]; then
  echo "FEHLER: $NIK_HOME enthaelt kein LibericaFX (javafx.controls fehlt)." >&2
  echo "       Die *Full*-Variante der Liberica NIK wird benoetigt, z.B. /opt/nik/25-full." >&2
  exit 1
fi

export JAVA_HOME="$NIK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_DIR"

# Maven: ein installiertes bevorzugen, sonst den Wrapper aus dem Projekt.
# Der Intel-Build-Mac hat kein Maven - wie der Windows-Rechner, der aus
# demselben Grund mvnw.cmd benutzt. Der Wrapper (only-script) laedt die in
# .mvn/wrapper/maven-wrapper.properties genannte Version beim ersten Aufruf
# selbst herunter.
if command -v mvn >/dev/null 2>&1; then
  MVN=(mvn)
else
  MVN=("$PROJECT_DIR/mvnw")
fi

# artifactId und version direkt aus der pom.xml lesen.
#
# Frueher stand hier "mvn help:evaluate" mit dem sed als Rueckfallebene. Das
# ging auf einem frisch aufgesetzten Rechner schief: Der Aufruf laeuft mit -o
# (offline), und wenn das maven-help-plugin noch nie geladen wurde, bricht er
# mit "No plugin found for prefix 'help'" ab - und schreibt diese Meldung nach
# *stdout*. Die Kommandosubstitution hat den Fehlertext und die Ausgabe der
# Rueckfallebene dann aneinandergehaengt, und der Build meldete am Ende
# "Binary nicht erzeugt: .../target/[ERROR] No plugin found ...", obwohl
# native-image laengst fertig war.
#
# Alles bis </parent> wird weggeschnitten, sonst erwischt man artifactId und
# version des Spring-Boot-Parents.
pom_value()
{
  sed -n '/<\/parent>/,$p' pom.xml \
    | sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" | head -1
}

ARTIFACT_ID="$(pom_value artifactId)"
if [[ -z "$ARTIFACT_ID" ]]; then
  echo "FEHLER: artifactId nicht aus pom.xml lesbar." >&2
  exit 1
fi
BINARY="$PROJECT_DIR/target/$ARTIFACT_ID"

# Standardablage des App-Bundles; --package-binary legt sie nach Architektur
# ab, damit ein Intel-Paket das Apple-Silicon-Paket nicht ueberschreibt.
APP_DIR="$PROJECT_DIR/target"
ZIP_NAME="DriveMount-macos.zip"

if [[ -n "$PACKAGE_BINARY" ]]; then
  if [[ ! -f "$PACKAGE_BINARY" ]]; then
    echo "FEHLER: --package-binary: $PACKAGE_BINARY gibt es nicht." >&2
    exit 1
  fi
  # Die Architektur steht im Mach-O-Kopf; lipo liest sie ohne Umweg. Ein
  # Universal-Binary ergaebe "x86_64 arm64" - das wird hier nicht gebaut.
  PACKAGE_ARCH="$(lipo -archs "$PACKAGE_BINARY" 2>/dev/null || true)"
  case "$PACKAGE_ARCH" in
    x86_64|arm64) ;;
    *) echo "FEHLER: $PACKAGE_BINARY ist kein macOS-Binary fuer eine" \
            "Architektur (lipo: '${PACKAGE_ARCH:-?}')." >&2
       exit 1 ;;
  esac
  BINARY="$PACKAGE_BINARY"
  APP_DIR="$PROJECT_DIR/target/app-macos-$PACKAGE_ARCH"
  ZIP_NAME="DriveMount-macos-$PACKAGE_ARCH.zip"
  echo "==> Nur verpacken: $BINARY ($PACKAGE_ARCH)"
fi

if [[ -z "$PACKAGE_BINARY" ]]; then

echo "==> Toolchain"
echo "    JAVA_HOME = $JAVA_HOME"
echo "    $(java -version 2>&1 | head -1)"
echo "    $(native-image --version 2>&1 | head -1)"
echo "    $("${MVN[@]}" -v 2>/dev/null | head -1)"

# --agent: App auf der JVM mit dem Tracing-Agent starten. Alle UI-Pfade
# (Login ok, Login-Fehler, Mounts) einmal durchspielen, dann Fenster schliessen.
if [[ -n "$AGENT_ONLY" ]]; then
  # Ohne <finalName> hiesse das Jar drivemount-1.0.0.jar; die pom setzt den
  # Namen aber auf ${project.artifactId}. Frueher stand hier die
  # Version-Variante - der Agent-Lauf waere an "Unable to access jarfile"
  # gescheitert.
  JAR="$PROJECT_DIR/target/$ARTIFACT_ID.jar"
  echo "==> mvn ${CLEAN:+$CLEAN }package -DskipTests"
  echo
  # shellcheck disable=SC2086
  "${MVN[@]}" $CLEAN package -DskipTests
  echo
  echo "==> Tracing-Agent — Metadaten nach $AGENT_CONFIG_DIR"
  echo "    Jetzt bitte ALLE UI-Pfade durchspielen: erfolgreicher Login,"
  echo "    fehlgeschlagener Login, mindestens ein Mount. Danach Fenster schliessen."
  echo
  java "-agentlib:native-image-agent=config-merge-dir=$AGENT_CONFIG_DIR" -jar "$JAR"
  echo
  echo "==> Metadaten aktualisiert:"
  ls -la "$AGENT_CONFIG_DIR"
  exit 0
fi

if [[ ! -d "$AGENT_CONFIG_DIR" ]]; then
  echo "    HINWEIS: $AGENT_CONFIG_DIR fehlt — noch keine Reachability-Metadaten."
  echo "             JavaFX/FXML-Reflection und die FFM-Downcalls brauchen sie."
  echo "             Einmalig erzeugen mit: ./BUILD_NATIVE_MACOS.sh --agent"
fi

echo "==> mvn ${CLEAN:+$CLEAN }package ${MVN_ARGS[*]}"
echo

START=$(date +%s)
# shellcheck disable=SC2086
"${MVN[@]}" $CLEAN package "${MVN_ARGS[@]}"
END=$(date +%s)

echo
if [[ -x "$BINARY" ]]; then
  SIZE=$(du -h "$BINARY" | cut -f1)
  echo "==> OK: $BINARY ($SIZE) in $((END - START)) s"
  echo "    Start: ./target/$ARTIFACT_ID   # Konfiguration: application.yaml (eingebettet) oder ./application.yaml"
else
  echo "==> FEHLER: Binary nicht erzeugt: $BINARY" >&2
  exit 1
fi

fi  # Ende: nicht --package-binary

# --------------------------------------------------------------- App-Bundle
# Das nackte Binary laesst sich nur im Terminal starten und erscheint im Dock
# namenlos. Ein .app-Bundle ist auf macOS nichts weiter als ein Verzeichnis
# mit fester Struktur - Binary nach Contents/MacOS, Icon nach
# Contents/Resources, Metadaten in die Info.plist. Damit ist es per
# Doppelklick startbar, traegt Namen und Icon und laesst sich weitergeben.
if [[ -n "$CREATE_APP" ]]; then
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "==> FEHLER: --create-app baut ein macOS-Bundle und laeuft nur auf macOS." >&2
    echo "            Fuer Windows: ./BUILD_NATIVE_WINDOWS.sh --create-app" >&2
    exit 1
  fi

  ICNS="$PROJECT_DIR/packaging/$ARTIFACT_ID.icns"
  if [[ ! -f "$ICNS" ]]; then
    echo "==> FEHLER: $ICNS fehlt — einmalig erzeugen mit ./packaging/make-icons.sh" >&2
    exit 1
  fi

  # Version bevorzugt aus build-info.properties: die hat der Build gerade
  # selbst geschrieben und sie ist identisch mit dem, was die UI unten links
  # anzeigt. mvn help:evaluate nur als Rueckfallebene (langsam).
  BUILD_INFO="$PROJECT_DIR/target/classes/META-INF/build-info.properties"
  if [[ -n "$PACKAGE_BINARY" ]]; then
    # Das Binary kommt von einem anderen Rechner, gebaut aus genau diesen
    # Quellen. Die build-info.properties hier in target/ gehoert dagegen zum
    # letzten *lokalen* Build und koennte eine andere Version tragen.
    VERSION="$(pom_value version)"
  elif [[ -f "$BUILD_INFO" ]]; then
    VERSION="$(sed -n 's/^build\.version=//p' "$BUILD_INFO" | head -1)"
  fi
  VERSION="${VERSION:-$("${MVN[@]}" -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)}"
  VERSION="${VERSION:-0.0.0}"

  APP="$APP_DIR/$APP_NAME.app"
  echo
  echo "==> App-Bundle: $APP"

  rm -rf "$APP"
  mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

  cp "$BINARY" "$APP/Contents/MacOS/$ARTIFACT_ID"
  cp "$ICNS"   "$APP/Contents/Resources/$ARTIFACT_ID.icns"

  # Legacy-Typkennung; manche Finder-/Launch-Services-Pfade schauen noch danach.
  printf 'APPL????' > "$APP/Contents/PkgInfo"

  cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>          <string>de</string>
  <key>CFBundleExecutable</key>                 <string>$ARTIFACT_ID</string>
  <key>CFBundleIconFile</key>                   <string>$ARTIFACT_ID</string>
  <key>CFBundleIdentifier</key>                 <string>$APP_ID</string>
  <key>CFBundleInfoDictionaryVersion</key>      <string>6.0</string>
  <key>CFBundleName</key>                       <string>$APP_NAME</string>
  <key>CFBundleDisplayName</key>                <string>$APP_NAME</string>
  <key>CFBundlePackageType</key>                <string>APPL</string>
  <key>CFBundleShortVersionString</key>         <string>$VERSION</string>
  <key>CFBundleVersion</key>                    <string>$VERSION</string>
  <key>LSApplicationCategoryType</key>          <string>public.app-category.utilities</string>
  <key>LSMinimumSystemVersion</key>             <string>11.0</string>
  <key>NSHighResolutionCapable</key>            <true/>
  <key>NSHumanReadableCopyright</key>           <string>Copyright 2026 Thorsten Ludewig</string>
  <!-- Ueberbleibsel aus der Zeit, als MacosMounter osascript mit
       "mount volume" aufrief; ohne den Eintrag verweigerte macOS die
       Automatisierung kommentarlos. Seit der Umstellung auf
       NetFSMountURLSync (FFM) verschickt die Anwendung keine Apple Events
       mehr - der Schluessel schadet nicht, kann aber beim naechsten
       Eingriff in die Info.plist entfallen (kostet Signatur und
       Notarisierung, deshalb nicht einzeln herausgenommen). -->
  <key>NSAppleEventsUsageDescription</key>
  <string>DriveMount verbindet die Netzlaufwerke ueber das Betriebssystem.</string>
</dict>
</plist>
PLIST

  # Signieren. Ohne gueltige Signatur beendet macOS auf Apple Silicon den
  # Prozess sofort mit SIGKILL - und das Kopieren ins Bundle invalidiert die
  # Signatur, die der Linker dem Binary mitgegeben hat. Es muss also in jedem
  # Fall neu signiert werden.
  #
  # Mit einem "Developer ID Application"-Zertifikat im Schluesselbund wird
  # richtig signiert, sonst ad-hoc. Ad-hoc genuegt auf dem Rechner, der
  # gebaut hat; weitergegeben blockiert Gatekeeper das Bundle, bis jemand von
  # Hand das Quarantaene-Attribut entfernt.
  SIGN_IDENTITY="${MACOS_SIGN_IDENTITY:-$(security find-identity -v -p codesigning 2>/dev/null \
    | awk -F'"' '/Developer ID Application/{print $2; exit}')}"

  ENTITLEMENTS="$PROJECT_DIR/packaging/macos/entitlements.plist"

  if [[ -n "$SIGN_IDENTITY" ]]; then
    echo "    Signatur: $SIGN_IDENTITY"
    # --options runtime (Hardened Runtime) und ein sicherer Zeitstempel sind
    # Pflicht, sonst weist Apple die Notarisierung zurueck.
    CODESIGN_ARGS=(--force --timestamp --options runtime --sign "$SIGN_IDENTITY")
    if [[ -f "$ENTITLEMENTS" ]]; then
      CODESIGN_ARGS+=(--entitlements "$ENTITLEMENTS")
      echo "    Entitlements: $ENTITLEMENTS"
    fi
    codesign "${CODESIGN_ARGS[@]}" "$APP"
    codesign --verify --strict --verbose=2 "$APP" 2>&1 | sed 's/^/    /'
  else
    echo "    HINWEIS: kein Developer-ID-Zertifikat gefunden, signiere ad-hoc."
    echo "             Das Bundle laeuft dann nur auf diesem Rechner ohne"
    echo "             Gatekeeper-Umweg."
    codesign --force --sign - --timestamp=none "$APP" \
      || echo "    WARNUNG: auch ad-hoc-Signieren fehlgeschlagen." >&2
  fi

  # Zum Weitergeben: ditto statt zip, das erhaelt Symlinks und die Signatur.
  ZIP="$PROJECT_DIR/target/$ZIP_NAME"
  rm -f "$ZIP"
  ditto -c -k --sequesterRsrc --keepParent "$APP" "$ZIP"

  # Notarisieren. Signieren allein genuegt nicht: Gatekeeper fragt auf einem
  # fremden Rechner bei Apple nach, und ohne Notarisierung gibt es kein
  # Ticket. Apple bekommt dafuer das ZIP, das Ticket wird anschliessend ins
  # Bundle geheftet ("stapled"), damit es auch offline gilt - deshalb muss
  # das ZIP danach neu gepackt werden.
  if [[ -n "$SIGN_IDENTITY" && -z "$NO_NOTARIZE" ]]; then
    echo
    echo "==> Notarisierung (Profil '$NOTARY_PROFILE') - das dauert einige Minuten"
    if xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" \
         --wait 2>&1 | sed 's/^/    /'; then
      xcrun stapler staple "$APP" 2>&1 | sed 's/^/    /'
      rm -f "$ZIP"
      ditto -c -k --sequesterRsrc --keepParent "$APP" "$ZIP"
      echo "    Ticket angeheftet, ZIP neu gepackt"
      # Die entscheidende Probe: so beurteilt Gatekeeper das Bundle wirklich.
      spctl --assess --type execute --verbose=4 "$APP" 2>&1 | sed 's/^/    /'
    else
      echo "    FEHLER: Notarisierung fehlgeschlagen. Details mit:" >&2
      echo "      xcrun notarytool log <submission-id> --keychain-profile $NOTARY_PROFILE" >&2
    fi
  fi

  echo "==> OK: $APP ($(du -sh "$APP" | cut -f1)), Version $VERSION"
  echo "        $ZIP ($(du -h "$ZIP" | cut -f1))"
  echo "    Start: open '$APP'"
fi
