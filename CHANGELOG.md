# Changelog

Alle nennenswerten Änderungen an DriveMount. Das Format folgt
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionierung
[Semantic Versioning](https://semver.org/lang/de/).

Die Zeilen sind aus dem Projektstand und den gebauten Paketen rekonstruiert —
das Repository wurde erst am 21.09.2026 angelegt und hat für die Zeit davor
keine Historie.

## [1.1.2] — 2026-09-21

Kleine Nachbesserungen an der Login-Maske aus 1.1.1, dazu zwei Korrekturen
am Windows-Build. Keine Änderung an der Konfiguration.

### Hinzugefügt

- Tooltip am Verweis „Kontosicherheit verwalten“: „Solltest du noch keinen
  TOTP Code haben, erstelle dir einen unter 'Authenticator-Anwendung
  einrichten'“. Er beantwortet die Frage, die hinter dem Verweis steckt —
  wer noch gar kein Einmalkennwort hat, findet dort den Weg dorthin.

### Geändert

- Der Platzhalter im TOTP-Feld heißt „TOTP Code (6-stellig)“ statt bisher
  „TOTP (6-stellig)“.

### Behoben

- `BUILD_NATIVE_WINDOWS.sh` brach mit leerem `WIN_COMPANY_NAME` ab:
  `-CompanyName ''` verliert auf dem Weg durch ssh und PowerShell seine
  Anführungszeichen, übrig bleibt ein Parameter ohne Wert und damit
  „Fehlendes Argument für den Parameter CompanyName“. Der Parameter wird
  jetzt ganz weggelassen, wenn kein Wert gesetzt ist — das Skript auf dem
  Windows-Rechner hat ohnehin die leere Zeichenkette als Vorgabe.
- `BUILD_NATIVE_WINDOWS.sh` übertrug `LICENSE` nicht mit. Seit 1.1.0 kopiert
  Maven die Datei als `assets/LICENSE` ins Artefakt, für das Lizenzfenster —
  auf dem Windows-Rechner fehlte sie und das Paket hätte dort nur den
  Ersatztext gezeigt. Aufgefallen ist es durch `LicenseResourceTest`, der
  genau dafür da ist; das Linux-Skript hatte die Datei bereits dabei.

## [1.1.1] — 2026-09-21

Eine Ergänzung in der Login-Maske, sonst unverändert gegenüber 1.1.0. Die
Konfiguration bleibt kompatibel: `drivemount.account-security-url` ist
optional, ohne den Eintrag sieht die Maske aus wie bisher.

### Hinzugefügt

- **Verweis „Kontosicherheit verwalten“** unter dem TOTP-Feld. Ein Klick
  öffnet `drivemount.account-security-url` im **Standardbrowser des
  Betriebssystems** — bei Keycloak also die Selbstverwaltung, in der sich
  etwa ein neuer Authenticator einrichten lässt, wenn das Einmalkennwort
  nicht mehr passt. Etwas kleinerer Zeichensatz als die Felder darüber
  (17px gegen 20px), damit der Verweis die Anmeldung nicht überlagert.
  Fehlt die Einstellung oder ist sie leer, erscheint er gar nicht.
- Neue Einstellung `drivemount.account-security-url`, dokumentiert in
  `application.yaml.sample`.

## [1.1.0] — 2026-09-21

Die erste Version, die mit einer Installation **mehrere AD-Domänen** bedient
— daher der Sprung auf 1.1.0 und nicht auf 1.0.3. Aus dem Token kommt nur
noch die Domäne; welche Laufwerke sie bekommt, steht ausschließlich in der
Konfiguration.

**Umstieg von 1.0.x:** `drivemount.shares` muss umgeschrieben werden, aus der
flachen Liste wird eine Zuordnung *Domäne → Liste* (Beispiel in
`application.yaml.sample` und in der README unter *Shares*). Wird die alte
Form stehengelassen, scheitert
schon das Binden der Konfiguration. Ein neues Binary ist ohnehin nötig: die
`application.yaml` wird ins Artefakt eingebaut.

Dazu ein sichtbarer Zusatz — das Lizenzfenster auf Strg+Alt+L — und ein
behobener Fehler im macOS-Binary, der bei jeder Trackpad-Geste Stacktraces
auf die Konsole schrieb.

### Hinzugefügt

- **Shares je AD-Domäne.** `drivemount.shares` ist jetzt eine Zuordnung
  *Domäne → Liste von Shares* statt einer flachen Liste. Welcher Block gilt,
  entscheidet die aus dem Mail-Claim abgeleitete Domäne (`a@example-zwei.de`
  → `example-zwei`). Damit bedient eine Installation mehrere Mandanten, ohne
  dass je Domäne ein eigenes Binary nötig wäre. Die Schlüssel sind
  groß-/kleinschreibungsunabhängig; zwei Schlüssel, die sich nur darin
  unterscheiden, brechen den Start ab, statt stillschweigend einer Domäne
  die falschen Laufwerke zu geben. Beim Start protokolliert die Anwendung
  `Konfigurierte Domaenen: [example (2), …]`.

  ⚠️ **Konfigurationsänderung**: eine `application.yaml` im alten Format
  (Shares als flache Liste) bindet nicht mehr. Siehe
  `application.yaml.sample`.
- **Lizenzfenster.** Bei sichtbarer Login-Maske öffnet **Strg+Alt+L**
  (unter macOS Ctrl+Option+L) ein Fenster mit Copyright und dem
  vollständigen, scrollbaren Text der Apache License 2.0; `Esc` oder
  „Schließen“ beendet es. Der Text stammt aus `assets/LICENSE` im Artefakt —
  einer Kopie der Datei `LICENSE`, die Maven beim Bauen anlegt, damit es die
  Lizenz nur einmal gibt. Ohne Schaltfläche in der Maske, damit die Karte
  weiterhin nur die Anmeldung zeigt.
- `./PREVIEW.sh --license` rendert dieses Fenster in eine PNG-Datei, wie die
  Vorschau es schon für die Login-Maske tut.
- `application.yaml.sample` zeigt die neue Struktur mit zwei Beispieldomänen
  und erklärt die Ableitungsregel an einem Namen mit Bindestrich.
- Acht weitere Tests: die Domänen-Zuordnung (Schreibweise, Bindestrich,
  unbekannte Domäne, doppelte Schlüssel, unveränderliche Listen) und ein
  Wächter dafür, dass `assets/LICENSE` wirklich im Klassenpfad liegt —
  ohne ihn zeigte das Lizenzfenster lautlos nur den Ersatztext. Insgesamt
  54, weiterhin ohne Bildschirm und ohne Netz.

### Geändert

- Meldet sich jemand aus einer Domäne an, für die nichts konfiguriert ist,
  nennt die Statuszeile jetzt diese Domäne („Angemeldet – für die Domäne
  „x“ sind keine Laufwerke hinterlegt.“) und das Log die bekannten
  Schlüssel. Vorher stand dort nur „keine Laufwerke im Profil hinterlegt“ —
  richtig, aber niemand wusste, wo zu suchen war.
- Der Linux-Starter führt `Ostfalia` nicht mehr als Suchbegriff
  (`Keywords=` in `drivemount.desktop`), und `WIN_COMPANY_NAME` ist in
  `env.sample` leer vorgegeben: die EXE ist nicht signiert, ein
  Herausgebername in den Dateieigenschaften wäre eine Behauptung, die
  niemand prüfen kann. Damit steht in den veröffentlichten Dateien kein
  Organisationsname mehr.
- `distrib/README.md` ist wieder versioniert. Die `.gitignore` schloss
  `distrib/` als ganzes Verzeichnis aus; in ein ausgeschlossenes Verzeichnis
  steigt git gar nicht erst hinab, die Ausnahme `!/distrib/README.md` lief
  deshalb ins Leere. Die Anwendungspakete bleiben ausgeschlossen.

### Entfernt

- Der optionale Token-Claim `smbShares` samt Einstellung `shares-claim`. Er
  war nie scharfgeschaltet, hätte im Native Image eine eigene
  Jackson-Registrierung gebraucht (`treeToValue` kam in keinem Agent-Lauf
  vor) und ist durch die Zuordnung je Domäne überflüssig geworden. `SmbShare`
  verliert damit auch seine Jackson-Annotation und wird nur noch vom
  Spring-Konfigurationsbinder gefüllt.
- Der GitHub-Workflow `.github/workflows/native-build.yml`. Er ist nie
  erfolgreich durchgelaufen: `setup-graalvm` installiert mit
  `distribution: liberica` die Standard-Variante ohne LibericaFX, worauf
  `native-image` mit `Module javafx.fxml not found` abbricht. Gebaut wird
  weiterhin auf eigenen Rechnern über `./BUILD_ALL_APPS.sh`. Was ein neuer
  Anlauf beachten müsste, steht in CLAUDE.md unter „Kein CI“.
  `BUILD_NATIVE_WINDOWS.sh` und `BUILD_NATIVE_LINUX.sh` packen `./.github`
  nicht mehr mit ein — der fehlende Pfad ließ `tar` mit Exit 1 abbrechen und
  damit den ganzen Remote-Build, wegen `2>/dev/null` ohne jede Meldung.

### Behoben

- Unter macOS warf das Native Image bei jeder Trackpad-Geste über dem
  Fenster eine `ClassNotFoundException:
  com.sun.glass.ui.mac.MacGestureSupport` auf den JavaFX Application Thread
  — sichtbar als Stapel von Stacktraces auf der Konsole, ohne dass die
  Anwendung selbst gestört war (Anmeldung und Mounts liefen weiter). Die
  Glass-Bibliothek lädt diese Klasse aus nativem Code per Namen; im Image
  muss sie dafür registriert sein. Nachgetragen in der handgepflegten
  Metadatendatei. Betrifft nur das native Binary, nicht den Start über die
  JVM.

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
