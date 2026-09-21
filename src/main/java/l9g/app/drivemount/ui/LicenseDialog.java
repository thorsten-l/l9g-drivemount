/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Das Lizenzfenster: Copyright-Zeile und der vollstaendige, scrollbare Text
 * der Apache License 2.0.
 *
 * <p>Erreichbar ueber <b>Strg+Alt+L</b> (unter macOS Ctrl+Option+L), solange
 * die Login-Maske sichtbar ist. Bewusst ohne Schaltflaeche in der Maske: die
 * Karte soll die Anmeldung zeigen und sonst nichts, und die Lizenz muss
 * einsehbar, aber nicht prominent sein. {@code Esc} schliesst wieder.</p>
 *
 * <h2>Woher der Text kommt</h2>
 *
 * <p>Aus {@code /assets/LICENSE} im Artefakt - derselben Datei, die als
 * {@code LICENSE} im Projektverzeichnis liegt. Das Kopieren erledigt Maven
 * ({@code <resource>} mit {@code targetPath} in der pom.xml), damit es die
 * Datei genau einmal gibt und der angezeigte Text nicht von dem abweichen
 * kann, der dem Quellcode beiliegt.</p>
 *
 * <p>Fuer das Native Image braucht die Datei einen Eintrag in der
 * handgepflegten Metadatendatei: {@code -H:IncludeResources} deckt nur
 * {@code fxml|css|png|yaml|yml} ab, und {@code LICENSE} hat nicht einmal eine
 * Endung. Fehlt der Eintrag, startet das Binary einwandfrei und zeigt hier
 * spaeter nur den Ersatztext - deshalb wird der Fehlschlag protokolliert.</p>
 *
 * <h2>Ereignisfilter statt Accelerator</h2>
 *
 * <p>Die Tastenkombination haengt als Filter auf {@code KEY_PRESSED} an der
 * Szene. Ein Filter laeuft in der Capturing-Phase, also bevor das fokussierte
 * Bedienelement den Anschlag ueberhaupt sieht - ein Textfeld kann ihn damit
 * nicht schlucken, und die Kombination taucht auch nicht als Eingabe im Feld
 * auf.</p>
 */
public final class LicenseDialog
{
  private static final Logger LOG
    = LoggerFactory.getLogger(LicenseDialog.class);

  /**
   * Strg+Alt+L. Unter macOS ist {@code ALT_DOWN} die Option-Taste, die
   * Kombination heisst dort also Ctrl+Option+L. Bewusst nicht
   * {@code SHORTCUT_DOWN}: das waere unter macOS Command und unter Windows
   * Strg - also zwei verschiedene Griffe statt eines gemeinsamen.
   */
  public static final KeyCombination SHORTCUT = new KeyCodeCombination(
    KeyCode.L, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN);

  /** Pfad der Lizenz im Artefakt; siehe Klassenkommentar. */
  static final String RESOURCE = "/assets/LICENSE";

  private static final String COPYRIGHT
    = "Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)";

  private LicenseDialog()
  {
  }

  /**
   * Haengt {@link #SHORTCUT} an eine Szene.
   *
   * @param scene   die Szene der Login-Maske
   * @param version Versionsnummer fuer die Kopfzeile, darf {@code null} sein
   *                (dann bleibt sie weg - genau wie die Versionsanzeige unten
   *                links, wenn kein Maven-Lauf stattgefunden hat)
   */
  public static void install(Scene scene, String version)
  {
    scene.addEventFilter(KeyEvent.KEY_PRESSED, event ->
    {
      if(SHORTCUT.match(event))
      {
        event.consume();
        show(scene.getWindow(), version);
      }
    });
  }

  /**
   * Oeffnet das Lizenzfenster.
   *
   * <p>Modal zum Besitzerfenster, was zwei Dinge erledigt: es liegt
   * zuverlaessig vorne, und ein zweiter Druck auf die Tastenkombination kann
   * kein zweites Fenster oeffnen, weil die Login-Maske waehrenddessen keine
   * Eingaben mehr bekommt. Es braucht deshalb auch keinen gemerkten Zustand.</p>
   *
   * <p>Anders als die Login-Maske ist dieses Fenster in der Groesse
   * veraenderbar - Lizenztext liest sich je nach Bildschirm unterschiedlich
   * gern.</p>
   *
   * @param owner   das Fenster, zu dem der Dialog modal ist
   * @param version Versionsnummer fuer die Kopfzeile, darf {@code null} sein
   */
  public static void show(Window owner, String version)
  {
    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.APPLICATION_MODAL);
    stage.setTitle("drivemount - Lizenz");

    Scene scene = new Scene(createContent(version, stage::close), 760, 700);
    // Dasselbe Stylesheet wie die Login-Maske; es haengt auch dort an der
    // Szene und nicht im FXML.
    scene.getStylesheets().add(LicenseDialog.class
      .getResource("/l9g/app/drivemount/ui/sonia.css").toExternalForm());

    stage.setScene(scene);
    stage.setMinWidth(560);
    stage.setMinHeight(420);
    stage.show();
    // Gleiche Spur wie "Login-Fenster geoeffnet": ohne sie laesst sich aus
    // einem Protokoll nicht ablesen, ob die Tastenkombination angekommen ist.
    LOG.info("Lizenzfenster geoeffnet");
  }

  /**
   * Baut den Inhalt des Fensters auf.
   *
   * <p>Paketsichtbar, damit {@code UiPreview} ihn ohne Fenster in eine
   * PNG-Datei rendern kann ({@code ./PREVIEW.sh --license}) - dieselbe
   * Styling-Schleife wie bei der Login-Maske.</p>
   *
   * @param version Versionsnummer oder {@code null}
   * @param onClose Aktion der Schaltflaeche "Schliessen" und der Esc-Taste
   * @return die Wurzel der Szene
   */
  static Region createContent(String version, Runnable onClose)
  {
    Label title = new Label("Lizenz");
    title.getStyleClass().add("title");

    StringBuilder meta = new StringBuilder("drivemount");
    if(version != null && !version.isBlank())
    {
      meta.append(" v").append(version);
    }
    meta.append('\n').append(COPYRIGHT)
      .append("\nLizenziert unter der Apache License, Version 2.0");

    Label subtitle = new Label(meta.toString());
    subtitle.getStyleClass().add("license-meta");
    subtitle.setWrapText(true);

    // TextArea und nicht Label im ScrollPane: das Scrollen per Rad, Tastatur
    // und Bildlaufleiste bringt sie mit, und der Text laesst sich markieren
    // und kopieren. Nicht editierbar, aber bewusst fokussierbar - sonst
    // reagiert sie auf Bild auf/ab nicht.
    TextArea text = new TextArea(loadLicenseText());
    text.getStyleClass().add("license-text");
    text.setEditable(false);
    // Die Datei ist bereits auf 77 Zeichen umbrochen und nutzt Einrueckungen;
    // Umbrechen lassen wir trotzdem zu, damit ein schmal gezogenes Fenster
    // keine waagerechte Bildlaufleiste braucht.
    text.setWrapText(true);
    VBox.setVgrow(text, Priority.ALWAYS);

    Button close = new Button("Schließen");
    close.setOnAction(event -> onClose.run());
    close.setDefaultButton(true);
    HBox buttons = new HBox(close);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    VBox card = new VBox(14, title, subtitle, text, buttons);
    card.getStyleClass().add("card");
    VBox.setVgrow(card, Priority.ALWAYS);

    VBox root = new VBox(card);
    root.setPadding(new Insets(26));

    // Esc schliesst. Als Filter, aus demselben Grund wie die
    // Tastenkombination oben: die TextArea soll den Anschlag nicht sehen.
    root.addEventFilter(KeyEvent.KEY_PRESSED, event ->
    {
      if(event.getCode() == KeyCode.ESCAPE)
      {
        event.consume();
        onClose.run();
      }
    });

    return root;
  }

  /**
   * Liest den Lizenztext aus dem Artefakt.
   *
   * @return der Text, oder ein Hinweis mit der Adresse der Lizenz, falls die
   *         Ressource fehlt - ein leeres Fenster waere die schlechtere Antwort
   */
  private static String loadLicenseText()
  {
    try(InputStream in = LicenseDialog.class.getResourceAsStream(RESOURCE))
    {
      if(in != null)
      {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      LOG.warn("Lizenztext {} nicht im Artefakt gefunden", RESOURCE);
    }
    catch(IOException e)
    {
      LOG.warn("Lizenztext {} nicht lesbar", RESOURCE, e);
    }

    return """
           Der Lizenztext konnte nicht geladen werden.

           Diese Software steht unter der Apache License, Version 2.0.
           Der vollständige Text steht unter

               http://www.apache.org/licenses/LICENSE-2.0

           und liegt der Quelldistribution als Datei LICENSE bei.
           """;
  }
}
