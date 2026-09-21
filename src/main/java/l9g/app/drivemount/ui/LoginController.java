/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import l9g.app.drivemount.auth.KeycloakAuthService;
import l9g.app.drivemount.config.DrivemountProperties;
import l9g.app.drivemount.model.AuthResult;
import l9g.app.drivemount.mount.MountResult;
import l9g.app.drivemount.mount.Mounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Der Login-Dialog - und zugleich der gesamte Ablauf der Anwendung.
 *
 * <p>Ein Klick auf "Verbinden" loest beides nacheinander aus: die
 * Authentifizierung per Keycloak Direct Grant (Passwort und Einmalkennwort)
 * und anschliessend das Verbinden aller Shares mit <i>denselben</i>
 * Anmeldedaten. Sind am Ende alle verbunden, schliesst sich das Fenster nach
 * der konfigurierten Wartezeit von selbst; bleibt auch nur einer offen,
 * bleibt es stehen, damit die Meldungen lesbar sind.</p>
 *
 * <h2>Nebenlaeufigkeit</h2>
 *
 * <p>Anmeldung und Mounts laufen in einem JavaFX-{@link Task} auf einem
 * eigenen Daemon-Thread, nie auf dem Application Thread - beides blockiert,
 * und eine eingefrorene Oberflaeche waehrend eines 15-Sekunden-Timeouts waere
 * unzumutbar. Rueckmeldungen gehen ueber
 * {@code updateMessage}/{@code messageProperty}, die JavaFX selbst auf den
 * richtigen Thread bringt.</p>
 *
 * <p>Eine Falle steckt in dieser Bindung: {@code statusLabel.textProperty()}
 * ist waehrend des Laufs an die Task gebunden, und eine gebundene Property
 * laesst sich nicht mehr setzen. Deshalb steht in
 * {@code setOnSucceeded} <b>und</b> in {@code setOnFailed} als erste Zeile
 * ein {@code unbind()} - fehlt es, fliegt der naechste {@code setText}.</p>
 *
 * <h2>Umgang mit dem Passwort</h2>
 *
 * <p>Das Passwort wird als {@code char[]} gefuehrt und im {@code finally} der
 * Task ueberschrieben, auch wenn unterwegs etwas schiefgeht. Der Umweg ueber
 * einen {@link String} passiert nur dort, wo eine API ihn erzwingt - im
 * HTTP-Formular und in den Mountern.</p>
 */
@Component
public class LoginController
{
  private static final Logger LOG
    = LoggerFactory.getLogger(LoginController.class);

  private final KeycloakAuthService authService;

  private final Mounter mounter;

  private final DrivemountProperties props;

  private final ObjectProvider<BuildProperties> buildProperties;

  @FXML
  private TextField usernameField;

  @FXML
  private PasswordField passwordField;

  @FXML
  private TextField totpField;

  @FXML
  private Button loginButton;

  @FXML
  private Label statusLabel;

  @FXML
  private Label versionLabel;

  @FXML
  private ListView<String> resultList;

  @FXML
  private VBox card;

  /**
   * Konstruktorinjektion durch Spring - moeglich, weil der FXMLLoader seine
   * Controller ueber {@code setControllerFactory} aus dem Kontext bezieht.
   *
   * @param authService     Anmeldung gegen Keycloak
   * @param mounter         die zum Betriebssystem passende Implementierung
   * @param props           Konfiguration, hier vor allem die Wartezeit bis
   *                        zum Schliessen
   * @param buildProperties optional; liefert die Versionsnummer fuer die
   *                        Anzeige unten links
   */
  public LoginController(KeycloakAuthService authService, Mounter mounter,
    DrivemountProperties props,
    ObjectProvider<BuildProperties> buildProperties)
  {
    this.authService = authService;
    this.mounter = mounter;
    this.props = props;
    this.buildProperties = buildProperties;
  }

  /**
   * Wird vom FXMLLoader reflektiv aufgerufen, nachdem die
   * {@code @FXML}-Felder gesetzt sind.
   *
   * <p>Fuer das Native Image bedeutet "reflektiv", dass die Methode
   * registriert sein muss - das erledigt die pauschale Registrierung der
   * Klasse in der handgepflegten Metadatendatei.</p>
   */
  @FXML
  private void initialize()
  {
    // TOTP: nur Ziffern, hoechstens sechs. Als TextFormatter und nicht als
    // Tasten-Listener, denn der Filter greift auf jeder Aenderung - also
    // auch beim Einfuegen aus der Zwischenablage, beim Ersetzen einer
    // Auswahl und beim Widerrufen. Ein abgelehnter Change gibt null zurueck,
    // der Feldinhalt bleibt dann unveraendert.
    totpField.setTextFormatter(new TextFormatter<>(
      change -> isAcceptedTotpInput(change.getControlNewText())
        ? change : null));

    // ObjectProvider statt direkter Injektion: BuildProperties existiert nur,
    // wenn META-INF/build-info.properties vorliegt. Beim Maven-Ziel
    // spring-boot:build-info ist das der Fall, bei einem Start aus der IDE
    // ohne vorherigen Maven-Lauf nicht - dann bleibt die Zeile eben leer,
    // statt den Start scheitern zu lassen.
    BuildProperties build = buildProperties.getIfAvailable();
    versionLabel.setText(build != null ? "v" + build.getVersion() : "");

    addAccountSecurityLink();
  }

  /**
   * Haengt den Verweis "Kontosicherheit verwalten" unter das TOTP-Feld.
   *
   * <p>Er fuehrt zur Selbstverwaltung des IDP
   * ({@code drivemount.account-security-url}), wo sich etwa ein neuer
   * Authenticator einrichten laesst - die haeufigste Frage, wenn das
   * Einmalkennwort nicht mehr passt. Geoeffnet wird im Standardbrowser des
   * Betriebssystems, siehe {@link BrowserLauncher}.</p>
   *
   * <p>Ein Tooltip erklaert, wofuer man dort hin will: wer noch kein
   * Einmalkennwort eingerichtet hat, legt es unter "Authenticator-Anwendung
   * einrichten" an.</p>
   *
   * <p>Ist keine Adresse konfiguriert, entsteht der Knoten gar nicht erst:
   * ein Verweis ins Leere waere schlimmer als keiner. Deshalb steht er auch
   * nicht im FXML - dort waere er immer da, und ein {@link Hyperlink} als
   * neuer Typ braeuchte ausserdem eine Registrierung in den
   * Reachability-Metadaten. Im Code erzeugte Knoten brauchen keine.</p>
   */
  private void addAccountSecurityLink()
  {
    String url = props.accountSecurityUrl();
    if (url == null || url.isBlank())
    {
      return;
    }

    Hyperlink link = new Hyperlink("Kontosicherheit verwalten");
    link.getStyleClass().add("account-link");
    link.setOnAction(event -> BrowserLauncher.open(url));

    // Der Hinweis beantwortet die Frage, die hinter dem Verweis steckt:
    // "Ich habe gar keinen TOTP-Code." Genau dafuer gibt es die
    // Selbstverwaltung des IDP; der Name des dortigen Eintrags steht mit
    // drin, damit niemand auf der Seite suchen muss.
    Tooltip hint = new Tooltip("Solltest du noch keinen TOTP Code haben,"
      + " erstelle dir einen unter 'Authenticator-Anwendung einrichten'");
    // Die Voreinstellung sind 1000 ms; das fuehlt sich nach Verzoegerung an,
    // wenn jemand kurz ueber den Verweis faehrt.
    hint.setShowDelay(Duration.millis(400));
    // Lange URLs sonst als eine endlose Zeile ueber den halben Bildschirm.
    hint.setWrapText(true);
    hint.setMaxWidth(420);
    // Inline und nicht in sonia.css: ein Tooltip ist ein eigenes Fenster mit
    // eigener Szene, und die bekommt das Stylesheet der Login-Maske nicht
    // zwangslaeufig mit. Die Groesse ist die einzige Abweichung vom
    // JavaFX-Standardaussehen, der Rest darf bleiben, wie das System es
    // gewohnt ist.
    hint.setStyle("-fx-font-size: 14px;");
    link.setTooltip(hint);

    // Direkt hinter das TOTP-Feld, nicht ans Ende: der Verweis gehoert zum
    // Einmalkennwort und nicht zur Schaltflaeche darunter.
    card.getChildren().add(
      card.getChildren().indexOf(totpField) + 1, link);
  }

  /**
   * Die Regel hinter dem Filter fuer das Einmalkennwort: bis zu sechs
   * Ziffern, sonst nichts.
   *
   * <p>Die leere Eingabe ist ausdruecklich erlaubt - sonst liesse sich das
   * Feld nicht mehr leeren.</p>
   *
   * <p>Als benannte Methode herausgezogen, damit {@code TotpInputTest} sie
   * ohne laufendes JavaFX-Toolkit pruefen kann - ein {@link TextFormatter}
   * braucht ein echtes Control und damit einen Bildschirm.</p>
   *
   * @param text der vollstaendige Feldinhalt <i>nach</i> der Aenderung
   * @return {@code true}, wenn die Eingabe uebernommen werden darf
   */
  static boolean isAcceptedTotpInput(String text)
  {
    return text != null && text.matches("[0-9]{0,6}");
  }

  /**
   * Fuehrt Anmeldung und Mounts aus - der Weg vom Klick bis zum verbundenen
   * Laufwerk.
   *
   * <p>Ablauf: Eingaben pruefen, Oberflaeche sperren, Task starten. Der Task
   * meldet sich ueber {@code updateMessage} zurueck; ausgewertet wird in
   * {@code setOnSucceeded} beziehungsweise {@code setOnFailed}, die beide
   * wieder auf dem Application Thread laufen.</p>
   *
   * <p>Der Name dieser Methode steht im FXML und wird reflektiv aufgeloest.
   * Genau das ist im Native Image einmal auf die Nase gefallen: ein
   * Agent-Lauf, der nur den Start aufzeichnete, kannte die
   * {@code @FXML}-Felder, aber nicht diese Methode - das Image baute
   * durch und starb beim <i>ersten Klick</i> mit
   * {@code MissingReflectionRegistrationError}. Seitdem ist die Klasse
   * pauschal registriert.</p>
   */
  @FXML
  private void onLogin()
  {
    String username = usernameField.getText().trim();
    // JavaFX PasswordField liefert leider nur String; ab hier als char[]
    // fuehren und nach Gebrauch ueberschreiben.
    char[] password = passwordField.getText().toCharArray();
    String totp = totpField.getText().trim();

    if (username.isEmpty() || password.length == 0 || totp.isEmpty())
    {
      statusLabel.setText("Bitte Benutzername, Passwort und TOTP eingeben.");
      Arrays.fill(password, '\0');
      return;
    }

    loginButton.setDisable(true);
    statusLabel.setText("Anmeldung läuft ...");
    resultList.getItems().clear();

    Task<List<MountResult>> task = new Task<>()
    {
      @Override
      protected List<MountResult> call() throws Exception
      {
        try
        {
          AuthResult auth = authService.authenticate(username, password, totp);
          // Leere Liste heisst hier fast immer: fuer diese Domaene ist
          // nichts konfiguriert. Den Namen mit anzeigen, sonst sucht
          // niemand an der richtigen Stelle.
          updateMessage(auth.shares().isEmpty()
            ? "Angemeldet - für die Domäne \"" + auth.smbDomain()
            + "\" sind keine Laufwerke hinterlegt."
            : "Angemeldet - verbinde " + auth.shares().size()
            + " Laufwerk(e) ...");

          List<MountResult> results = new ArrayList<>();
          for (var share : auth.shares())
          {
            results.add(mounter.mount(share, username, password,
              auth.smbDomain()));
          }
          return results;
        }
        finally
        {
          Arrays.fill(password, '\0');
        }
      }
    };

    statusLabel.textProperty().bind(task.messageProperty());

    task.setOnSucceeded(event ->
    {
      statusLabel.textProperty().unbind();
      List<MountResult> results = task.getValue();
      long ok = results.stream().filter(MountResult::success).count();
      results.forEach(result ->
      {
        LOG.info("{} {}: {}", result.success() ? "[OK]" : "[FEHLER]",
          result.share().label(), result.message());
        resultList.getItems().add(
          (result.success() ? "[OK] " : "[FEHLER] ")
          + result.share().label() + " - " + result.message());
      });
      LOG.info("{} von {} Laufwerk(en) verbunden", ok, results.size());
      passwordField.clear();
      totpField.clear();

      if (!results.isEmpty() && ok == results.size())
      {
        // Alles verbunden - die Anwendung hat ihren Zweck erfuellt und
        // beendet sich. Der Button bleibt gesperrt, ein zweiter Durchlauf
        // waere sinnlos. Platform.exit() loest Application.stop() aus,
        // das den Spring-Kontext schliesst.
        statusLabel.setText(ok
          + " Laufwerk(e) verbunden - Fenster schliesst sich");
        // java.time.Duration aus der Konfiguration -> javafx.util.Duration
        PauseTransition beenden = new PauseTransition(
          Duration.millis(props.closeDelay().toMillis()));
        beenden.setOnFinished(finished -> Platform.exit());
        beenden.play();
        return;
      }

      // Teilweise oder gar nicht verbunden: Fenster offen lassen, sonst
      // sieht niemand die Fehlermeldungen.
      statusLabel.setText(ok + " von " + results.size()
        + " Laufwerk(en) verbunden.");
      loginButton.setDisable(false);
    });

    task.setOnFailed(event ->
    {
      statusLabel.textProperty().unbind();
      Throwable e = task.getException();
      LOG.error("Anmeldung/Mount abgebrochen", e);
      statusLabel.setText(e != null ? e.getMessage() : "Unbekannter Fehler");
      passwordField.clear();
      totpField.clear();
      loginButton.setDisable(false);
    });

    Thread thread = new Thread(task, "auth-mount");
    thread.setDaemon(true);
    thread.start();
  }
}
