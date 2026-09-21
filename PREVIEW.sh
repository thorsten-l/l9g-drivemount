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
# Rendert den Login-Dialog (login.fxml + sonia.css) nach target/ui-preview.png,
# ohne ein Fenster zu oeffnen. Fuer die Styling-Schleife: wenige Sekunden statt
# rund einer Minute Native Build.
#
#   ./PREVIEW.sh                  rendern
#   ./PREVIEW.sh -o bild.png      anderes Ziel
#   ./PREVIEW.sh --open           danach im Vorschau-Programm oeffnen (macOS)
#
# ACHTUNG: Die JVM verzeiht fehlende Reflection-Metadaten, das Native Image
# nicht. Nach Aenderungen an FXML oder CSS deshalb einmal
# "./BUILD_NATIVE_MACOS.sh --agent" laufen lassen und neu bauen - sonst startet das
# Binary nicht oder zeichnet falsch.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$PROJECT_DIR/target/ui-preview.png"
OPEN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--out)   OUT="$2"; shift 2 ;;
    --open)     OPEN=1; shift ;;
    -h|--help)  sed -n '/^# Rendert /,/^# Binary/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unbekannte Option: $1 (siehe --help)" >&2; exit 2 ;;
  esac
done

cd "$PROJECT_DIR"

CP_FILE="target/preview-classpath.txt"
if [[ ! -s "$CP_FILE" || pom.xml -nt "$CP_FILE" ]]; then
  echo "==> Klassenpfad aufloesen"
  mvn -q -o dependency:build-classpath -Dmdep.outputFile="$CP_FILE" \
    -Dmdep.includeScope=test
fi

# test-compile deckt beides ab: Ressourcen (FXML/CSS) nach target/classes
# kopieren und UiPreview uebersetzen.
mvn -q -o test-compile

java "-Dpreview.out=$OUT" \
  -cp "target/test-classes:target/classes:$(cat "$CP_FILE")" \
  l9g.app.drivemount.ui.UiPreview

[[ -n "$OPEN" ]] && command -v open >/dev/null && open "$OUT"
