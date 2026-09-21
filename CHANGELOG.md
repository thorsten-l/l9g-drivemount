# Changelog

Alle nennenswerten Änderungen an DriveMount. Das Format folgt
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionierung
[Semantic Versioning](https://semver.org/lang/de/).

Die Zeilen sind aus dem Projektstand und den gebauten Paketen rekonstruiert —
das Repository wurde erst am 21.09.2026 angelegt und hat für die Zeit davor
keine Historie.

## [Unveröffentlicht]

Noch nichts.

## [1.0.2] — 2026-09-21

Die Versionsnummer wurde allein deshalb erhöht, damit sich die
ausgelieferten Binaries auseinanderhalten lassen. Geändert hat sich die
produktive `application.yaml` — Konfiguration, kein Code. Die Datei ist
nicht versioniert (siehe 1.0.1, *Sicherheit*), die Änderung lässt sich hier
also nicht nachvollziehen; nachlesbar ist sie nur in der Konfiguration
selbst.

Ein Versionssprung ohne Codeänderung ist in diesem Projekt deshalb normal:
Konfiguration und Binary sind untrennbar, weil die Konfiguration ins
Artefakt eingebaut wird.

### Geändert

- `DISTRIB.sh` setzt die eingesammelten Pakete auf Modus 644. Das
  Windows-Paket kam per `scp` mit den Rechten des Build-Rechners an (0600)
  und wäre nur für den Eigentümer lesbar gewesen.

### Hinzugefügt

- Diese Datei.

## [1.0.1] — 2026-09-21

Keine Änderung an der Anwendung selbst: diese Version räumt auf, damit das
Projekt veröffentlicht werden kann. Produktivdaten sind aus Repository,
Dokumentation und Tests verschwunden, die Einrichtung ist beschrieben und
skriptgestützt.

### Hinzugefügt

- `application.yaml.sample` — dieselbe Struktur wie die echte Konfiguration,
  mit Platzhaltern und kommentierten Feldern. Wird nicht ins Artefakt
  gepackt (`**/*.sample` ist in den Maven-Ressourcen ausgeschlossen).
- `PREBUILD.sh` — macht eine frische Arbeitskopie baubereit: Vorlage
  kopieren, bauen, Schlüssel anlegen, neu packen. Jeder Schritt fasst
  Vorhandenes nicht an.
- `BUILD_ALL_APPS.sh` — baut alle drei Plattformen in der einzig
  funktionierenden Reihenfolge und sammelt sie ein. Meldet je Schritt Dauer,
  reine `native-image`-Zeit und Größe des Ergebnisses; ein Fehlschlag bricht
  den Lauf nicht ab, sondern taucht in der Schlusstabelle auf.
- `.env`, `env.sample` und `load-env.sh` — Rechnernamen, Pfade und
  Signaturangaben liegen nicht mehr in den Skripten. Bereits gesetzte
  Umgebungsvariablen haben Vorrang vor der Datei.
- `.gitignore` — hält `application.yaml`, `assets/secret.bin`, `.env`, die
  fertigen Pakete und die üblichen System- und IDE-Dateien draußen.
- Launcher-Option `-i <pfad/secret.bin>` legt einen neuen 32-Byte-Schlüssel
  an. Verweigert das Überschreiben eines vorhandenen Schlüssels — ein neuer
  entwertet jeden bereits verschlüsselten `{AES256}`-Wert.
- Javadoc in allen Klassen. Im Paket `l9g.app.drivemount.mount` ausführlich:
  Layout und Offsets von `NETRESOURCEW`, die Create- und Get-Regeln von
  CoreFoundation, warum `SymbolLookup.libraryLookup(Path)` bei
  macOS-Frameworks scheitert, und welche Fehlercodes am realen Fileserver
  gemessen statt aus der Dokumentation übernommen wurden.

### Geändert

- `BUILD_NATIVE.sh` heißt jetzt `BUILD_NATIVE_MACOS.sh`. Der Name führt auf
  dem Linux-Weg in die Irre — das Skript verzweigt über `uname -s` und deckt
  Darwin *und* Linux ab; macOS-spezifisch sind nur Signatur, Notarisierung
  und App-Bundle.
- `WIN_HOST` und `LINUX_HOST` haben keinen eingebauten Vorgabewert mehr. Ohne
  sie brechen die Remote-Builds mit Exit 2 ab, statt auf einen fremden
  Rechner zu zeigen.
- `CompanyName` in den Versionsinformationen der EXE kommt aus
  `WIN_COMPANY_NAME`. Fehlt der Wert, bleibt das Feld leer statt einen
  erfundenen Herausgeber zu tragen.
- Dokumentation und Testfixtures verwenden Beispieldaten
  (`fileserver.example.org`, `idp.example.org`, Domäne `example`) statt der
  produktiven Namen.
- Der Kommentar über `client-secret` in der `application.yaml` beschrieb
  einen überholten Stand: das Secret ist nicht mehr im Klartext, und
  `DRIVEMOUNT_CLIENT_SECRET` übersteuert es seit dem Wegfall des Platzhalters
  nicht mehr.

### Behoben

- `-i` scheiterte an einer noch nicht vorhandenen Datei mit
  `NoSuchFileException`. `Files.write` hatte nur `StandardOpenOption.WRITE`
  bekommen, was die Standardliste ersetzt und damit `CREATE` verliert.
- `-i` wird ausgeführt, bevor `CryptoHandler.getInstance()` läuft. Dieser
  Aufruf löst den Schlüssel auf und erzeugt, wenn keiner da ist, still einen
  eigenen — ausgerechnet der Befehl zum Anlegen darf das nicht auslösen.

### Sicherheit

- `src/main/resources/application.yaml` und `src/main/resources/assets/secret.bin`
  sind nicht mehr Teil des Repositories. Beide gehören zusammen: der
  Schlüssel entschlüsselt genau den `{AES256}`-Wert in dieser einen Datei.
  Eine frische Arbeitskopie ist deshalb nicht ohne Weiteres baubereit, siehe
  `PREBUILD.sh`.

## [1.0.0] — 2026-09-20

Erste Veröffentlichung. Auf allen drei Plattformen mit echten Anmeldedaten
gegen den produktiven Fileserver verifiziert, einschließlich der Negativtests
unter macOS und Windows.

### Hinzugefügt

- **Anmeldung** über Keycloak Direct Grant mit Passwort und Einmalkennwort
  (TOTP). Die AD-Domäne wird aus dem Mail-Claim des Tokens abgeleitet, gilt
  also pro Benutzer; ein optionaler Claim kann eine benutzerspezifische
  Share-Liste liefern. Das Access Token wird nur ausgelesen und sofort
  verworfen.
- **SMB-Mount** auf drei Wegen, jeweils ohne das Passwort als
  Prozessargument: `WNetAddConnection2W` über FFM (Windows),
  `NetFSMountURLSync` über FFM (macOS), `gio mount` mit Antworten auf stdin
  (Linux). Native Fehlercodes werden in deutschen Klartext übersetzt.
- **Oberfläche** in JavaFX im Design des Keycloak-Themes. Sind alle Laufwerke
  verbunden, schließt sich das Fenster nach einer konfigurierbaren Wartezeit;
  bei jedem Fehler bleibt es offen.
- **Native Images** über GraalVM mit Liberica NIK Full für macOS, Windows und
  Linux, samt handgepflegter Reachability-Metadaten für FXML, Reflexion und
  die FFM-Downcalls.
- **Anwendungspakete** (`--create-app`): `DriveMount.app` mit Developer-ID
  signiert, notarisiert und gestapelt (macOS); EXE mit Icon und
  Versionsinformationen im Ordner mit ihren DLLs (Windows);
  `.desktop`-Starter mit Symbolen im hicolor-Theme und `install.sh` nach
  `~/.local` (Linux).
- `DISTRIB.sh` sammelt die drei Pakete mit Version, Plattform und
  Architektur im Namen nach `distrib/` und schreibt `SHA256SUMS`.
- Verschlüsseltes Client-Secret (`{AES256}` über l9g-crypto) samt
  Launcher-Option `-e`. Das ist Verschleierung, kein Schutz — Chiffrat und
  Schlüssel liegen beide im Artefakt; abgesichert wird die Anmeldung durch
  Passwort und Einmalkennwort.
- 46 Tests für die reinen Funktionen: UNC-Umwandlung, Auswertung des
  Token-Payloads, die Fehlertabellen aller drei Mounter und die
  TOTP-Eingaberegel. Sie brauchen weder Bildschirm noch Netz und laufen bei
  jedem Build mit.
- `LICENSE` (Apache-2.0).
