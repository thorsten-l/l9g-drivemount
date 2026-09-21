# drivemount

Plattformübergreifender SMB-Laufwerks-Mounter (Linux, macOS, Windows) mit
MFA-Gate: Anmeldung per **Keycloak Direct Grant** (Passwort + TOTP). Aus der
Mailadresse im Access Token ergibt sich die **AD-Domäne**, und die wählt den
passenden Block aus `drivemount.shares`; gemountet wird mit denselben
Credentials über die jeweilige Plattform-API.

Stack: Java 25, Spring Boot (Kontext/DI/Config, ohne Web), JavaFX (UI),
GraalVM Native Image via **Liberica NIK Full** (enthält LibericaFX).

## Ablauf

1. Login-Dialog: Benutzername, Passwort, TOTP
2. `POST /realms/<realm>/protocol/openid-connect/token` mit
   `grant_type=password` + `totp` (Direct-Grant-Flow mit OTP=Required)
3. Bei 200: aus dem Access Token die Mailadresse (`email`) lesen und daraus
   die AD-Domäne ableiten, Token verwerfen
4. Zu dieser Domäne die konfigurierten Shares nachschlagen — steht sie nicht
   in `drivemount.shares`, gibt es nichts zu verbinden und das Fenster bleibt
   mit einem Hinweis offen
5. Shares mit `<Domäne>\<Benutzer>` und Passwort mounten
   (`WNetAddConnection2W` / `NetFSMountURLSync` / `gio mount`)
6. Sind **alle** Laufwerke verbunden, schließt sich das Fenster nach
   `drivemount.close-delay` (Default `2s`) von selbst. Bei Fehlern bleibt
   es offen, damit die Ergebnisliste lesbar bleibt.

## Keycloak-Voraussetzungen

1. **Client** `drivemount`: confidential, *Direct access grants* aktiviert,
   Standard Flow aus, Refresh Tokens deaktivieren/revoken, minimale
   Token-Lifespans.
2. **Direct-Grant-Flow** duplizieren: Username Validation → Password → OTP
   (`direct-grant-validate-otp`), alle *Required*. Im Client unter
   *Advanced → Authentication flow overrides → Direct grant flow* binden —
   der Realm-Default bleibt unangetastet.
3. **Claim**: Es genügt der Standard-Scope `email` — die App fordert ihn an
   und leitet daraus die AD-Domäne ab (Teil hinter dem `@` ohne TLD, also
   `vorname.nachname@example.org` → `example`). Ein eigener Protocol-Mapper ist
   dafür nicht nötig, und mehr als diesen einen Claim wertet die App nicht
   aus: welche Shares jemand bekommt, steht in der Konfiguration, nicht im
   Token.

Schnelltest ohne App — liefert die Mailadresse, aus der die Domäne entsteht:

```bash
curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=drivemount -d client_secret=$SECRET \
  -d scope="openid email" \
  --data-urlencode "username=$USER" --data-urlencode "password=$PASS" \
  -d totp=123456 | jq -r .access_token | cut -d. -f2 | base64 -d | jq .email
```

## Shares

Die Shares stehen in `application.yaml` unter `drivemount.shares`,
**gruppiert nach AD-Domäne**. Der Schlüssel ist genau das, was die App aus
der Mailadresse ableitet: der Teil hinter dem `@` ohne die letzte Endung —
aus `a@example.org` wird `example`, aus `b@example-zwei.de` wird
`example-zwei` — der Bindestrich gehört dazu, nur die letzte Endung fällt
weg. Groß-/Kleinschreibung spielt keine Rolle; die
Schlüssel werden beim Binden kleingeschrieben, und zwei Schlüssel, die sich
nur darin unterscheiden, brechen den Start ab, statt stillschweigend einer
Domäne die falschen Laufwerke zu geben.

```yaml
drivemount:
  shares:
    example:
      - label: User Home
        url: smb://fileserver.example.org/home
        mount: H
      - label: Group Share
        url: smb://fileserver.example.org/group
        mount: G
    example-zwei:
      - label: User Home
        url: smb://fileserver.example-zwei.org/home
        mount: H
```

Angemeldet wird mit `<Domäne>\<Benutzer>` — Benutzername aus dem
Login-Dialog, Domäne aus dem `email`-Claim. Für `muster` mit
`vorname.nachname@example.org` ergibt das `example\muster`, entspricht also
`smb://example\muster@fileserver.example.org/home`.

`mount` ist der Windows-Laufwerksbuchstabe und wird unter macOS/Linux
ignoriert — dort landen die Shares unter `/Volumes` bzw. im gvfs-Pfad.

Kommt jemand aus einer Domäne, die nicht konfiguriert ist, wird **nichts**
verbunden: die Statuszeile nennt die Domäne, das Fenster bleibt offen, und
im Log steht `Keine Shares fuer Domaene '<x>' konfiguriert - bekannt: [...]`.
Lieber gar keine Laufwerke als die einer fremden Domäne. Welche Domänen eine
Installation kennt, sagt die Anwendung außerdem beim Start:

```
Konfigurierte Domaenen: [example (2), example-zwei (1)]
```

`smb-domain` ist nur der Rückfall, wenn im Token gar keine Mailadresse
steht; auch dieser Name braucht dann einen eigenen Block unter `shares`.

`drivemount.close-delay` steuert, wie lange das Fenster nach erfolgreichem
Verbinden stehen bleibt (`2s`, `500ms`, `0` für sofort). Zum Ausprobieren
ohne Rebuild überschreibbar:

```bash
./target/drivemount --drivemount.close-delay=0
```

## Erscheinungsbild

Farben, Radien und Schriftgrößen stehen in
`src/main/resources/l9g/app/drivemount/ui/sonia.css` und sind aus dem
Keycloak-Theme übernommen (`sonia-theme-keycloak-v2`), damit Login im
Browser und Desktop-Anwendung zusammenpassen: `#004077` als Logo-/Primärblau,
Karte mit Radius 14 und blauem Kopfakzent.

Der Hintergrund ist `assets/bg-light.svg` des Themes in JavaFX-CSS nachgebaut
— radialer Schimmer `#dfe9f4` → `#f1f3f5` von links oben, dazu zwei blaue
Kreise (6 % / 5 % Deckkraft) unten rechts. Als Verlauf statt als Bild, weil
JavaFX kein SVG darstellt und ein PNG an eine Fenstergröße gebunden wäre.

Die Basis-Schriftgröße steuert `.root { -fx-font-size }` (20px = 1,5× des
JavaFX-Standards); alle Elemente erben sie.

Zum Ausprobieren nicht jedes Mal nativ bauen — `PREVIEW.sh` rendert die Maske
in rund drei Sekunden nach `target/ui-preview.png`, ohne ein Fenster zu
öffnen:

```bash
./PREVIEW.sh --open      # rendern und (macOS) gleich anzeigen
./PREVIEW.sh --license   # statt der Maske das Lizenzfenster
mvn javafx:run           # oder interaktiv ausprobieren
```

### Lizenzfenster

Solange die Login-Maske sichtbar ist, öffnet **Strg+Alt+L** (unter macOS
Ctrl+Option+L) ein Fenster mit Copyright und dem vollständigen, scrollbaren
Text der Apache License 2.0; `Esc` oder „Schließen“ beendet es wieder. Es
gibt bewusst keine Schaltfläche dafür in der Maske — die Karte soll die
Anmeldung zeigen.

Der Text kommt aus `assets/LICENSE` im Artefakt. Diese Ressource ist eine
Kopie der Datei `LICENSE` im Projektverzeichnis, die Maven beim Bauen anlegt
(`<resource>` mit `targetPath` in der `pom.xml`) — damit es die Lizenz genau
einmal gibt und der angezeigte Text nicht vom beiliegenden abweichen kann.
Fürs Native Image steht sie zusätzlich in der handgepflegten
Metadatendatei: `-H:IncludeResources` deckt nur `fxml|css|png|yaml|yml` ab,
und `LICENSE` hat nicht einmal eine Endung. `LicenseResourceTest` prüft, dass
sie tatsächlich im Klassenpfad liegt — fehlt sie, zeigt das Fenster sonst
lautlos nur einen Ersatztext mit der Adresse der Lizenz.

⚠️ Die JVM verzeiht fehlende Reflection-Metadaten, das Native Image nicht.
Nach FXML- oder CSS-Änderungen deshalb zum Abschluss einmal
`./BUILD_NATIVE_MACOS.sh --agent` und neu bauen — sonst startet das Binary nicht
oder zeichnet falsch.

## Entwicklung

```bash
mvn javafx:run          # Dev-Run (löst JavaFX-Module sauber auf)
```

Konfiguration in `src/main/resources/application.yaml`
(`drivemount.keycloak.*`, `mail-claim`, `smb-domain`, `shares`). Daneben liegt
`application.yaml.sample` — dieselbe Struktur mit Platzhaltern statt
produktiver Werte und mit kommentierten Feldern, als Startpunkt für eine eigene
Installation. Die Vorlage wird nicht ins Artefakt gepackt (`**/*.sample` ist in
den Maven-Ressourcen ausgeschlossen).

**Eine frische Arbeitskopie ist nicht baubereit.** `application.yaml` und
`src/main/resources/assets/secret.bin` sind in `.gitignore` und fehlen nach dem
Klonen. Beide gehören zusammen — der Schlüssel entschlüsselt genau den
`{AES256}`-Wert in dieser einen Datei. Entweder beide aus einer sicheren Quelle
dazulegen, oder neu aufsetzen — dafür gibt es `./PREBUILD.sh`:

```bash
./PREBUILD.sh           # Vorlage kopieren, bauen, Schlüssel anlegen, neu packen
./PREBUILD.sh --check   # nur zeigen, was fehlt
```

Das Skript fasst nichts Vorhandenes an und sagt am Ende, was noch von Hand
fehlt: das Client-Secret.

```bash
java -jar target/drivemount.jar -e "<client-secret im klartext>"   # -> {AES256}...
```

`-i` legt einen neuen 32-Byte-Schlüssel an und **weigert sich, einen
vorhandenen zu überschreiben** — ein neuer Schlüssel entwertet jeden bereits
verschlüsselten Wert. Den Klartext des Client-Secrets liefert die
Keycloak-Administration (Client → *Credentials*). Nach dem `-i` einmal neu
bauen, damit der Schlüssel im Artefakt landet.

> ⚠️ Das Client-Secret liegt dort als `{AES256}`-Wert und wird beim Start von
> `l9g-crypto` entschlüsselt. Der Schlüssel steckt allerdings als
> `assets/secret.bin` im selben Artefakt — das schützt gegen einen Blick in
> die Datei, nicht gegen jemanden, der das Binary hat. Wer beides hat, und
> das hat jeder mit dem Binary, kommt mechanisch an den Klartext.
>
> Was daran hängt: Das Secret authentifiziert die **Anwendung** gegenüber
> Keycloak. Wer es besitzt, kann sich von jedem Rechner aus als `drivemount`
> ausgeben; in den Realm-Logs sieht das aus wie die echte App. Und ein Tausch
> bedeutet neu bauen und auf jeden Arbeitsplatz ausliefern.
>
> Ein **public client** (ohne Secret) wäre für eine Desktop-Anwendung der
> passende Zuschnitt: Direct Grant funktioniert damit genauso, die
> Absicherung trägt ohnehin Passwort + TOTP. Das ist bewusst **nicht**
> umgesetzt — der Client bleibt confidential, das Restrisiko ist eine
> Entscheidung des Maintainers und kein offener Punkt.

### TLS

Der produktive IDP `idp.example.org` hat ein offizielles **HARICA**-Zertifikat;
die ausgelieferten JDK-cacerts reichen, ein CA-Import ist **nicht** nötig.

Der folgende Abschnitt gilt nur noch für den Entwicklungs-Realm
`idp.dev.example.org`, dessen Zertifikat von einer **internen Root CA** stammt.
Fehlt die im Truststore, scheitert dort jeder Login reproduzierbar mit:

```
Keycloak nicht erreichbar: (certificate_unknown) PKIX path building failed
```

Abhilfe: Root CA in den Truststore des JDK importieren, mit dem gebaut bzw.
gestartet wird.

```bash
keytool -importcert -noprompt -alias internal-ca -file internal-root.pem \
  -cacerts -storepass changeit
```

**Wichtig fuer den Native Build:** Native Image uebernimmt den Truststore
**zur Bauzeit** aus dem bauenden JDK und backt ihn ins Binary. Ein
nachtraegliches `-Djavax.net.ssl.trustStore=...` wird vom fertigen Binary
*nicht* akzeptiert (`UnsupportedFeatureError: Inaccessible trust store`) --
die CA muss also schon beim Bauen in `$NIK_HOME/lib/security/cacerts` liegen.
Verifiziert: mit importierter CA meldet das Binary bei falschen Zugangsdaten
`Invalid user credentials` (TLS stand also), ohne sie den PKIX-Fehler.

Konsequenz: bindet man wieder gegen einen Host mit privater CA, braucht
**jeder** Build-Rechner dieselbe CA in seinem JDK, sonst entsteht dort ein
Binary, das den Token-Endpoint nicht erreicht. Fuer `idp.example.org`
entfaellt das.

## Native Build

Voraussetzung: **Liberica NIK Full** (mit LibericaFX) als `JAVA_HOME`;
Windows zusätzlich Visual Studio Build Tools (`cl.exe` im Pfad). Kein
Cross-Compiling — pro Zielplattform bauen. Gebaut wird auf eigenen Rechnern,
es gibt **keine CI**; den Ablauf über alle drei Plattformen fasst
`./BUILD_ALL_APPS.sh` zusammen.

```bash
# 1. Reachability-Metadaten erzeugen: startet die App mit dem Tracing-Agent.
#    Alle UI-Pfade (Login ok, Login-Fehler, Mounts) einmal durchspielen,
#    dann Fenster schliessen. Ergebnis: src/main/resources/META-INF/native-image
./BUILD_NATIVE_MACOS.sh --agent

# 2. Native Image bauen -> target/drivemount
./BUILD_NATIVE_MACOS.sh      # bzw. --fast ohne Tests
```

`BUILD_NATIVE_MACOS.sh` setzt `JAVA_HOME` auf `NIK_HOME` (Default
`/opt/nik/25-full`) und prüft vorab, dass dort wirklich die **Full**-Variante
mit LibericaFX liegt — ohne `javafx.controls.jmod` bricht der Build sonst
erst nach Minuten im `native-image`-Schritt ab. Anderer Pfad:

```bash
NIK_HOME=/pfad/zur/nik-full ./BUILD_NATIVE_MACOS.sh
```

Intern läuft `mvn clean package -Pnative`. Wichtig dabei: `process-aot`
läuft über `DrivemountSpring` (`aot.main.class`), **nicht** über
`Launcher` — sonst startet die AOT-Verarbeitung JavaFX und der Build bleibt
am offenen Login-Fenster hängen.

### Linux

`BUILD_NATIVE_LINUX.sh` überträgt die Quellen per ssh und startet drüben
dasselbe `BUILD_NATIVE_MACOS.sh` wie auf dem Mac — ein eigenes Remote-Skript
braucht es nicht. Der Name trügt dort: das Skript verzweigt über `uname -s` und
deckt Darwin *und* Linux ab. Das Ergebnis kommt als `target/drivemount-linux` zurück, damit es das
macOS-Binary nicht überschreibt.

```bash
./BUILD_NATIVE_LINUX.sh --check    # Voraussetzungen prüfen
./BUILD_NATIVE_LINUX.sh --setup    # fehlende -dev-Pakete nachinstallieren
./BUILD_NATIVE_LINUX.sh --fast     # bauen -> target/drivemount-linux
```

Ziel über `LINUX_HOST`, `LINUX_PROJECT` und `LINUX_NIK_HOME` steuerbar; die
Werte kommen aus `./.env` (siehe **Umgebung**). Ohne `LINUX_HOST` bricht das
Skript ab, statt auf eine eingebaute Adresse zu zeigen.

> Zum Testrechner: WireGuard unter Linux braucht eine zusätzliche Host-Route,
> wenn der Tunnel-Endpoint selbst in den `AllowedIPs` liegt (hier
> `198.51.100.45` in `198.51.100.0/24`). Das Kernelmodul verschickt seine
> Handshake-Pakete über die normale Routing-Tabelle und schickt sie damit in
> den Tunnel, der dafür erst stehen müsste — Symptom: `wg show` meldet
> gesendete, aber null empfangene Bytes. macOS und Windows binden ihren
> Socket an die physische Schnittstelle und sind davon nicht betroffen, obwohl
> ihre Routing-Tabelle genauso aussieht. Abhilfe:
> `ip route add <endpoint>/32 via <gateway> dev <iface>`, dauerhaft als
> `PostUp` in der `wg1.conf` (das wertet nur `wg-quick` aus, NetworkManager
> ignoriert es).

Voraussetzung neben der NIK: die **Entwicklungspakete** von GTK, X11 und GL.
Auf einem Desktop liegt nur `libgtk-3.so.0`; der Linker braucht den Symlink
ohne Versionsnummer aus dem `-dev`-Paket. Fehlt er, läuft der Build komplett
durch und scheitert erst im letzten Schritt mit `/usr/bin/ld: cannot find
-lgtk-3`. `--setup` installiert `libgtk-3-dev libxtst-dev libgl1-mesa-dev
zlib1g-dev` (48 Pakete auf Ubuntu 24.04) und fragt dabei nach dem
sudo-Passwort — es braucht also ein Terminal.

### Windows

GraalVM kann nicht cross-compilieren; `drivemount.exe` muss auf Windows
gebaut werden. `BUILD_NATIVE_WINDOWS.sh` überträgt die Quellen per ssh,
stößt den Build an und holt das Binary zurück:

```bash
./BUILD_NATIVE_WINDOWS.sh --check    # nur Voraussetzungen prüfen
./BUILD_NATIVE_WINDOWS.sh --fast     # bauen -> target/drivemount.exe
./BUILD_NATIVE_WINDOWS.sh --fetch    # nur das fertige Binary holen
```

`de.l9g`-Abhängigkeiten kommen aus Maven Central. Liegt eine Version dort noch
nicht (frisch gebaut, noch nicht deployt oder noch nicht synchronisiert), muss
sie mitgeliefert werden — die pom deklariert kein `<repositories>`:

```bash
./BUILD_NATIVE_WINDOWS.sh --fast --local-deps
```

Ziel über `WIN_HOST`, `WIN_PROJECT`, `WIN_STAGING`, `WIN_NIK_HOME` und
`WIN_COMPANY_NAME` steuerbar; die Werte kommen aus `./.env` (siehe
**Umgebung**). Ohne `WIN_HOST` bricht das Skript ab. Die eigentliche Arbeit
erledigt `build-native-windows.ps1`, das mitübertragen wird.

Voraussetzungen drüben: Liberica NIK **Full** und die **Visual Studio Build
Tools mit C++-Workload** — ohne MSVC kann `native-image` nicht linken. Maven
wird nicht gebraucht, das Projekt bringt den Wrapper (`mvnw.cmd`) mit; in
`winget` gibt es ohnehin kein Apache-Maven-Paket. Fehlt etwas, bricht der
Preflight mit Klartext ab; nachinstallieren mit:

```bash
./BUILD_NATIVE_WINDOWS.sh --setup    # Build Tools, mehrere GB, Adminrechte
```

Der Preflight prüft MSVC über `vswhere -requires VC.Tools.x86.x64` und nicht
über die bloße Existenz von `vswhere.exe` — die liegt schon nach dem
Bootstrap des VS-Installers vor, auch ohne installiertes Toolset.

### Umgebung (`.env`)

Rechnernamen, Pfade und Signaturangaben stehen nicht in den Skripten, sondern
in `./.env`. Die Datei ist in `.gitignore`; `env.sample` daneben zeigt mit
Platzhaltern, welche Variablen es gibt, und ist der Startpunkt:

```bash
cp env.sample .env      # und die Platzhalter ersetzen
```

Gelesen wird sie von den `BUILD_*`-Skripten über `load-env.sh`. **Bereits
gesetzte Umgebungsvariablen gewinnen**, ein einmaliges

```bash
WIN_HOST=10.0.0.5 ./BUILD_NATIVE_WINDOWS.sh --check
```

sticht also den Eintrag in der Datei. Kommentare gehören in eigene Zeilen —
ein `#` mitten in einer Zeile ist Teil des Werts, damit Windows-Pfade nicht
an einer Kommentarregel zerbrechen.

| Variable | Bedeutung |
|---|---|
| `NIK_HOME` | Liberica NIK Full auf dem lokalen Rechner |
| `MACOS_SIGN_IDENTITY` | Signaturzertifikat; leer = selbst suchen |
| `MACOS_NOTARY_PROFILE` | Schlüsselbund-Profil für `notarytool` |
| `WIN_HOST` · `LINUX_HOST` | ssh-Ziele; **Pflicht** für den jeweiligen Build |
| `WIN_PROJECT` · `LINUX_PROJECT` | Projektverzeichnis auf dem Build-Rechner |
| `WIN_STAGING` | Ablage für übertragene Archive (mit Schrägstrichen) |
| `WIN_NIK_HOME` · `LINUX_NIK_HOME` | Liberica NIK Full drüben |
| `WIN_COMPANY_NAME` | `CompanyName` in den Versionsinfos der EXE; leer lassen, solange die EXE nicht signiert ist |

`.env` wird **nicht** auf die Build-Rechner übertragen — die Tarballs
enthalten nur Quellen, Skripte und Packaging. Drüben greifen deshalb die
Vorgaben, und `BUILD_NATIVE_LINUX.sh` reicht `NIK_HOME` ausdrücklich durch.

### Anwendungen bauen (`--create-app`)

Für alle drei Plattformen auf einmal gibt es `./BUILD_ALL_APPS.sh`. Das Skript
ruft die drei Build-Skripte und anschließend `./DISTRIB.sh` auf, meldet zu
jedem Schritt Startzeit, Dauer und Größe des Ergebnisses und schließt mit
einer Statustabelle. Ein fehlgeschlagener Schritt bricht den Lauf **nicht** ab
— ein ausgeschalteter Testrechner soll nicht die beiden anderen Pakete kosten
—, taucht aber in der Tabelle auf und macht den Exit-Code ungleich 0.

```bash
./BUILD_ALL_APPS.sh                  # alle drei bauen und nach distrib/ sammeln
./BUILD_ALL_APPS.sh --fast           # ohne Tests
./BUILD_ALL_APPS.sh --skip-windows   # einzelne Plattform auslassen
./BUILD_ALL_APPS.sh --no-notarize    # macOS ohne Notarisierung
```

Die Reihenfolge darin ist nicht beliebig: `BUILD_NATIVE_MACOS.sh` beginnt mit
`mvn clean` und räumt `target/` ab, muss also zuerst laufen. Die beiden
anderen bauen auf ihren eigenen Rechnern und legen ihr Ergebnis danach in
dasselbe Verzeichnis — umgekehrt wären die fertigen Pakete wieder weg.

Einzeln geht es weiterhin. Das nackte Binary lässt sich nur im Terminal
starten; `--create-app` macht daraus eine startbare Anwendung mit dem
SONIA-Logo als Symbol:

```bash
./BUILD_NATIVE_MACOS.sh --fast --create-app    # target/DriveMount.app + .zip
./BUILD_NATIVE_WINDOWS.sh --fast --create-app  # target/DriveMount-windows.zip
```

**macOS:** ein App-Bundle `DriveMount.app` mit `Info.plist`, `.icns` und
Ad-hoc-Signatur. Die Signatur ist nicht optional — auf Apple Silicon startet
ein unsigniertes Binary gar nicht, und das Kopieren ins Bundle macht die
Signatur des Linkers ungültig. Das ZIP entsteht mit `ditto`, damit die
Signatur den Transport übersteht.

**Windows:** Icon und Versionsinformationen werden per `rc.exe` in die EXE
gelinkt (sichtbar im Explorer und unter *Eigenschaften → Details*). Das
Ergebnis ist ein **Ordner**, keine einzelne EXE: `native-image` legt
`awt.dll`, `fontmanager.dll`, `freetype.dll` und weitere JDK-Bibliotheken
daneben, die zur Laufzeit nachgeladen werden. Standardmäßig wird die EXE als
GUI-Anwendung gelinkt, damit hinter dem Fenster keine Konsole aufgeht — zum
Fehlersuchen behält `--console` sie:

```bash
./BUILD_NATIVE_WINDOWS.sh --fast --create-app --console
```

Die Symbole liegen fertig im Projekt (`packaging/drivemount.icns`,
`packaging/drivemount.ico`). Neu erzeugen nur, wenn sich das Logo ändert:

```bash
./packaging/make-icons.sh    # braucht rsvg-convert (brew install librsvg)
```

## Sicherheitsmodell (ehrlich)

- TOTP schützt den **Zugang zum Werkzeug und zur Share-Liste** — nicht das
  SMB-Protokoll selbst. Der Mount läuft klassisch mit Username/Passwort;
  flankierend: SMB nur aus Campus/VPN, Signing/Encryption erzwingen.
- Passwort: nie als Prozessargument, nie loggen, als `char[]` führen und
  nach Gebrauch überschreiben (HTTP-Form und Plattform-APIs erzwingen
  punktuell Strings — Lebensdauer minimal halten).
- „Anmeldung merken“ nur über OS-Stores (Credential Manager/DPAPI,
  Keychain, libsecret) — nicht selbst persistieren.
- Das **Client-Secret ist bei dieser Verteilung kein Geheimnis**: Chiffrat
  und Schlüssel liegen beide im Artefakt, und das liegt auf jedem
  Arbeitsplatz. Die `{AES256}`-Verschlüsselung ist Verschleierung, kein
  Schutz. Sie schützt nichts, was Passwort + TOTP nicht ohnehin schützen —
  siehe den Hinweis unter „Entwicklung“.

## Offene Punkte (Fortsetzung in Claude Code)

- [x] `MacosMounter`: von osascript auf `NetFSMountURLSync` (FFM) umgestellt —
      kein Kindprozess, kein Passwort als AppleScript-String, echte Fehlercodes.
      Im nativen Binary verifiziert: Rückgabewert `0`, Mountpfade
      `/Volumes/home` und `/Volumes/group` von macOS zurückgemeldet
- [x] Negativfall auf beiden Plattformen geprüft, jeweils mit einem nicht
      existierenden Benutzernamen, damit kein echtes Konto Fehlversuche
      sammelt. macOS: nach 629 ms `Anmeldung am Fileserver fehlgeschlagen
      (EAUTH)`, ohne Systemdialog. Windows: nach 7,0 s `Anmeldedaten abgelehnt
      (86)` — der Fileserver antwortet mit `ERROR_INVALID_PASSWORD`, **nicht**
      mit 1326, auch wenn der Benutzername der Fehler war
- [x] Tests für die reinen Funktionen (uncPath, Domänenableitung aus dem
      Mail-Claim, Share-Zuordnung je Domäne, beide Fehlertabellen,
      TOTP-Regel, Lizenzressource); inzwischen 54 Stück, laufen im Build mit
- [x] Shares werden **je AD-Domäne** konfiguriert (`drivemount.shares` als
      Zuordnung Domäne → Liste). Damit bedient eine Installation mehrere
      Mandanten; eine unbekannte Domäne bekommt bewusst nichts
- [x] Der optionale Token-Claim `smbShares` ist entfallen — er war nie
      scharfgeschaltet, hätte im Native Image eine eigene
      Jackson-Registrierung gebraucht und wurde durch die Zuordnung je
      Domäne überflüssig
- [x] `WindowsWNetMounter`: Fehlercodes in Klartext (5, 53, 67, 85, 86, 1200,
      1202, 1219, 1326, 1330, 2250)
- [ ] `WindowsWNetMounter`: `WNetCancelConnection2W` (Trennen),
      Persistenz-Option
- [x] Linux-Build läuft: `drivemount` (95 MB) auf dem Testrechner (Ubuntu 24.04,
      Liberica NIK Full). `BUILD_NATIVE_LINUX.sh` überträgt und baut
- [x] `LinuxGioMounter`: Prompt-Reihenfolge **verifiziert** gegen gio 2.80
      (Ubuntu 24.04) am echten Fileserver: `User [<user>]: Domain [WORKGROUP]:
      Password:` — Benutzer, Domäne, Passwort, genau wie der Mounter schreibt.
      Dabei zeigte sich, dass gio im Fehlerfall keine Fehlermeldung liefert,
      sondern seine Eingabeaufforderungen; die landeten ungefiltert in der
      Ergebnisliste. `errorMessage` räumt das jetzt auf, mit Tests
- [x] **Linux verifiziert**: `gio mount` liefert `0` für beide Shares, 2 von 2
      verbunden, Fenster schließt nach den konfigurierten 2 s. Damit ist die
      Kette Login → Token → Domäne → Mount auf **allen drei** Plattformen
      durchgelaufen
- [ ] `LinuxGioMounter`: kio-Fallback (KDE), `gio mount --unmount` zum Trennen
- [ ] Unmount-/Status-Ansicht in der UI, Tray-Integration
- [ ] „Anmeldung merken“ über OS-Keystores (FFM)
- [x] Windows-Build läuft: `drivemount.exe` (91 MB) auf dem Testrechner,
      Liberica NIK Full + VS 2022 Build Tools
- [x] FFM-Downcall registriert (`foreign`-Abschnitt in der handgepflegten
      `reachability-metadata.json`). Vorher meldete jeder Build `0 downcalls
      and 0 upcalls registered for foreign access` und der erste echte Lauf
      scheiterte mit `MissingForeignRegistrationError` — jetzt `1 downcalls`
- [x] `drivemount.exe` gestartet: Login gegen `idp.example.org` erfolgreich
      (HTTP 200, Domäne aus dem Mail-Claim, beide Shares aus der Konfiguration)
- [x] **Mount unter Windows verifiziert**: `WNetAddConnection2W` liefert `0`
      für beide Shares, `H:` und `G:` verbunden, Fenster schließt sich nach
      den konfigurierten 2 s. Damit ist die Kette Login → Token → Domäne →
      Mount auf beiden Plattformen durchgelaufen.
      Stolperstein auf dem Weg dorthin: `fileserver.example.org` steht in keinem DNS,
      auf dem Mac nur in `/etc/hosts` (per IP). Ohne denselben
      Eintrag meldet Windows `67 = ERROR_BAD_NET_NAME` — das sieht nach einem
      Anwendungsfehler aus, ist aber keiner
- [x] Native-Build läuft durch; Binary startet und zeigt den Login-Dialog
      (macOS/aarch64, Liberica NIK 25 Full)
- [ ] Reachability-Metadaten decken Start, FXML und den Login-Klick ab.
      Erfolgreicher Login und Mounts fehlen weiterhin — Agent-Lauf mit
      echten Credentials nachholen (`./BUILD_NATIVE_MACOS.sh --agent`), ebenso je
      Zielplattform
- [x] Mount gegen `fileserver.example.org` erfolgreich (macOS, Anmeldung als
      `example\<user>`)
- [ ] Rollout: interne Root CA muss auf jedem Build-Host im JDK-Truststore
      liegen (siehe „TLS: interne CA“)
- [x] Client-Secret: verschlüsselt (`{AES256}`, Schlüssel als
      `assets/secret.bin` im Artefakt). Umstellung auf einen **public client**
      wurde geprüft und bewusst verworfen — der Client bleibt confidential,
      das Restrisiko ist akzeptiert. Falls die Abwägung später kippt: die
      Krypto-Bibliothek soll drin bleiben (für andere verschlüsselte Werte)
      und `client-secret` ein optionales Feld werden, damit beide Client-Typen
      per Konfiguration funktionieren
- [ ] Code-Signing unter Windows (macOS ist signiert und notarisiert)
- [x] GitHub-Workflow entfernt — er baute nie erfolgreich durch und stand
      dem Projekt eher im Weg. Was ein neuer Anlauf wissen muss, steht in
      CLAUDE.md unter „Kein CI“
- [x] LICENSE-Datei (Apache-2.0) ergänzt; die Anwendung zeigt sie über
      Strg+Alt+L im Lizenzfenster an — im nativen Binary geprüft (Fenster,
      Scrollen, Darstellung), es fehlen also keine Reachability-Metadaten
- [x] `com.sun.glass.ui.mac.MacGestureSupport` registriert: jede Trackpad-Geste
      über dem Fenster warf im nativen Binary sonst eine
      `ClassNotFoundException` auf die Konsole (Details in CLAUDE.md)

## Änderungen

Was sich je Version geändert hat, steht in [`CHANGELOG.md`](CHANGELOG.md).

## Lizenz

Apache-2.0 — © 2026 Thorsten Ludewig (t.ludewig@gmail.com)

Der vollständige Text steht in [`LICENSE`](LICENSE) und ist in der laufenden
Anwendung über **Strg+Alt+L** (macOS: Ctrl+Option+L) einsehbar.

Vollständiger Text in [`LICENSE`](LICENSE); jede Quelldatei trägt den
zugehörigen SPDX-Header.
