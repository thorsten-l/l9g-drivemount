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
# Macht eine frische Arbeitskopie baubereit. Beide Dateien, die dafuer fehlen,
# stehen in .gitignore und kommen nicht aus dem Repository:
#
#   src/main/resources/application.yaml        aus der .sample-Vorlage kopiert
#   src/main/resources/assets/secret.bin       hier neu erzeugt
#
# Ablauf:
#   1. Vorlage kopieren, falls noch keine application.yaml existiert
#   2. mvn package (ohne Tests) - liefert target/drivemount.jar
#   3. jar mit "-i" aufrufen, das legt einen neuen 32-Byte-Schluessel an
#   4. noch einmal packen, damit der Schluessel im Artefakt landet
#
#   ./PREBUILD.sh          Schritte ausfuehren, Vorhandenes bleibt unberuehrt
#   ./PREBUILD.sh --check  nur zeigen, was fehlt
#
# Jeder Schritt ist idempotent: eine vorhandene application.yaml wird nicht
# ueberschrieben, ein vorhandener secret.bin auch nicht. Das ist kein Komfort,
# sondern Absicht - ein neuer Schluessel entwertet jeden bereits
# verschluesselten {AES256}-Wert, allen voran das Client-Secret.
#
# Danach fehlt noch genau ein Schritt von Hand: das Client-Secret aus der
# Keycloak-Administration verschluesseln und in die application.yaml eintragen.
# Das Skript sagt am Ende, ob das noch aussteht.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

CONFIG="src/main/resources/application.yaml"
SAMPLE="$CONFIG.sample"
SECRET="src/main/resources/assets/secret.bin"
JAR="target/drivemount.jar"
PLACEHOLDER="HIER_DEN_VERSCHLUESSELTEN_WERT_EINTRAGEN"

CHECK_ONLY=""

for arg in "$@"; do
  case "$arg" in
    --check)   CHECK_ONLY=1 ;;
    -h|--help) sed -n '/^# Macht eine frische/,/^# Das Skript sagt/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
  esac
done

state()
{
  printf '    %-42s %s\n' "$1" "$2"
}

echo "==> Stand"
[[ -f "$CONFIG" ]] && state "$CONFIG" "vorhanden" || state "$CONFIG" "FEHLT"
[[ -f "$SECRET" ]] && state "$SECRET" "vorhanden" || state "$SECRET" "FEHLT"
[[ -f "$JAR"    ]] && state "$JAR"    "vorhanden" || state "$JAR"    "FEHLT"

if [[ -n "$CHECK_ONLY" ]]; then
  exit 0
fi

# Maven: bevorzugt der Wrapper, damit die Version zum Projekt passt.
if [[ -x ./mvnw ]]; then
  MVN="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  echo "FEHLER: weder ./mvnw noch mvn gefunden." >&2
  exit 1
fi

command -v java >/dev/null 2>&1 || { echo "FEHLER: java nicht gefunden." >&2; exit 1; }

# -------------------------------------------------------------- 1. Konfiguration
echo
echo "==> Konfiguration"

if [[ -f "$CONFIG" ]]; then
  echo "    bleibt unveraendert (existiert bereits)"
else
  [[ -f "$SAMPLE" ]] || { echo "FEHLER: $SAMPLE fehlt." >&2; exit 1; }
  cp "$SAMPLE" "$CONFIG"
  echo "    aus Vorlage kopiert: $SAMPLE -> $CONFIG"
fi

# -------------------------------------------------------------------- 2. Build
echo
echo "==> Build (ohne Tests)"
"$MVN" -q package -DskipTests
[[ -f "$JAR" ]] || { echo "FEHLER: $JAR wurde nicht erzeugt." >&2; exit 1; }
echo "    $JAR ($(du -h "$JAR" | cut -f1))"

# -------------------------------------------------------------- 3. Schluessel
echo
echo "==> Schluessel"

SECRET_CREATED=""

if [[ -f "$SECRET" ]]; then
  echo "    bleibt unveraendert (existiert bereits)"
else
  # "-i" weigert sich selbst, etwas zu ueberschreiben; die Abfrage oben spart
  # nur den Fehlerfall. Der Aufruf muss aus dem Projektverzeichnis kommen,
  # der Pfad ist relativ.
  java -jar "$JAR" -i "./$SECRET"
  SECRET_CREATED=1
fi

# ----------------------------------------------------- 4. Schluessel einpacken
# Der Schluessel ist eine Ressource. Ohne den zweiten Lauf steckt er zwar im
# Arbeitsverzeichnis, aber nicht im gerade gebauten Jar - und damit auch in
# keinem daraus abgeleiteten Native Image.
if [[ -n "$SECRET_CREATED" ]]; then
  echo
  echo "==> Neu packen, damit der Schluessel im Artefakt liegt"
  "$MVN" -q package -DskipTests
  echo "    $JAR neu gebaut"
fi

# ------------------------------------------------------------------ Abschluss
echo
if grep -q "$PLACEHOLDER" "$CONFIG" 2>/dev/null; then
  echo "==> Es fehlt noch das Client-Secret"
  echo
  echo "    Den Klartext liefert die Keycloak-Administration"
  echo "    (Clients -> <client-id> -> Credentials). Dann:"
  echo
  echo "      java -jar $JAR -e '<klartext-secret>'"
  echo
  echo "    Die Ausgabe ist der komplette {AES256}-String; er ersetzt in"
  echo "    der application.yaml den Platzhalter. Ebenso gehoeren dort"
  echo "    base-url, realm, client-id, smb-domain und die shares auf"
  echo "    echte Werte."
else
  echo "==> Baubereit"
  echo "    Naechster Schritt: ./BUILD_NATIVE_MACOS.sh"
fi
