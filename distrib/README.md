# DriveMount — Anwendungspakete

Fertige, startbare Anwendungen je Plattform. Erzeugt von den drei
`BUILD_NATIVE*`-Skripten mit `--create-app` und hier eingesammelt von
`../DISTRIB.sh`. `SHA256SUMS` entsteht dabei mit.

GraalVM kann nicht cross-compilieren — jedes Paket stammt von einem Rechner
der jeweiligen Plattform.

| Datei | Gebaut auf | Inhalt |
|---|---|---|
| `DriveMount-<ver>-macos-arm64.zip` | macOS, Apple Silicon | `DriveMount.app` |
| `DriveMount-<ver>-windows-x86_64.zip` | Windows 11 | Ordner mit `drivemount.exe` + JDK-DLLs |
| `DriveMount-<ver>-linux-x86_64.tar.gz` | Ubuntu 24.04 | Binary, Symbole, `.desktop`-Starter, `install.sh` |

## Installieren

**macOS** — entpacken, `DriveMount.app` nach `/Applications` ziehen, fertig.

Das Bundle ist mit dem *Developer ID Application*-Zertifikat des Maintainers
signiert, läuft mit **Hardened Runtime** und ist von Apple **notarisiert**; das
Ticket ist ins Bundle geheftet, gilt also auch offline. Gatekeeper sagt dazu
`accepted / source=Notarized Developer ID` — es braucht keinen
`xattr`-Handgriff mehr.

**Windows** — entpacken und `drivemount.exe` starten.

Der Ordner muss zusammenbleiben: `native-image` legt neben die EXE neun
JDK-Bibliotheken (`awt.dll`, `fontmanager.dll`, `freetype.dll` …), die zur
Laufzeit nachgeladen werden. Die EXE allein läuft nicht. Sie ist **nicht
signiert**, SmartScreen meldet sich daher beim ersten Start.

**Linux** — entpacken und `install.sh` ausführen:

```bash
tar xzf DriveMount-<ver>-linux-x86_64.tar.gz
./DriveMount/install.sh
```

Installiert ohne root nach `~/.local`: Binary nach `~/.local/bin`, Symbole in
den hicolor-Theme, Starter nach `~/.local/share/applications`. Danach steht
DriveMount in der Anwendungsübersicht und lässt sich über *Zu Favoriten
hinzufügen* in die Leiste legen. `./install.sh --uninstall` entfernt alles
wieder.

Voraussetzung zur Laufzeit ist `gvfs` samt Backends (`gvfs-backends`) — der
Mount läuft über `gio mount`.

## Bedienung

Benutzername, Passwort und Einmalkennwort eingeben — die Anwendung meldet
sich an, verbindet die für die eigene Domäne hinterlegten Laufwerke und
schließt sich von selbst, sobald alle stehen. Bleibt eines offen, bleibt auch
das Fenster offen und nennt den Grund.

**Strg+Alt+L** (unter macOS Ctrl+Option+L) zeigt Copyright und den
vollständigen Lizenztext; `Esc` schließt das Fenster wieder.

## Lizenz

Apache-2.0 — © 2026 Thorsten Ludewig (t.ludewig@gmail.com). Der Lizenztext
steckt in jedem Paket und ist über die Tastenkombination oben einsehbar.

## Was noch fehlt

- **macOS Intel (x86_64)**: bisher nur Apple Silicon. Braucht einen
  Intel-Mac zum Bauen.
- **Windows-Code-Signing**: nicht eingerichtet, SmartScreen meldet sich daher
  beim ersten Start. Dafür braucht es ein Zertifikat einer kommerziellen CA;
  der Apple-Account hilft dort nicht.
