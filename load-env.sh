#!/usr/bin/env bash
#
# Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
# SPDX-License-Identifier: Apache-2.0
#
# Laedt ./.env in die Umgebung. Wird von den BUILD_*-Skripten eingebunden
# (source), nicht selbst aufgerufen.
#
# Die Datei .env haelt die Werte der eigenen Umgebung - Rechnernamen,
# Pfade, Signaturangaben. Sie steht in .gitignore und geht nicht mit ins
# Repository; env.sample zeigt daneben, welche Variablen es gibt.
#
# Zwei Regeln:
#
#   1. Bereits gesetzte Umgebungsvariablen gewinnen. "WIN_HOST=10.0.0.5
#      ./BUILD_NATIVE_WINDOWS.sh" sticht also den Eintrag in .env - sonst
#      waere ein einmaliges Uebersteuern nicht mehr moeglich.
#   2. Kommentare nur in eigenen Zeilen. Ein '#' mitten in einer Zeile
#      gehoert zum Wert; Windows-Pfade und Passphrasen sollen nicht an
#      einer Kommentarregel zerbrechen.
#
# Werte duerfen in einfache oder doppelte Anfuehrungszeichen gefasst sein,
# muessen es aber nicht. Es findet keine Expansion statt: $HOME bleibt der
# Text "$HOME".

# Laedt die Datei, falls vorhanden. Fehlt sie, passiert nichts - die
# Skripte pruefen selbst, ob ihnen etwas fehlt.
drivemount_load_env()
{
  local file="${1:-.env}"
  [[ -f "$file" ]] || return 0

  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    # Leerzeilen und Kommentarzeilen ueberspringen
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" == *=* ]] || continue

    key="${line%%=*}"
    value="${line#*=}"

    # Leerraum um den Namen entfernen, danach auf Gueltigkeit pruefen
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    # Leerraum um den Wert entfernen, dann ein Paar Anfuehrungszeichen
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ${#value} -ge 2 ]]; then
      case "$value" in
        \"*\") value="${value:1:${#value}-2}" ;;
        \'*\') value="${value:1:${#value}-2}" ;;
      esac
    fi

    # Regel 1: was schon gesetzt ist, bleibt.
    [[ -n "${!key:-}" ]] && continue

    printf -v "$key" '%s' "$value"
    export "${key?}"
  done < "$file"
}
