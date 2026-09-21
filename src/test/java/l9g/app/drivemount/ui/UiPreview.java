/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import l9g.app.drivemount.DrivemountSpring;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Entwicklungswerkzeug: rendert login.fxml samt sonia.css in eine PNG-Datei,
 * ohne ein Fenster zu zeigen. Gedacht fuer die Styling-Schleife - ein Durchlauf
 * dauert wenige Sekunden, ein Native Build rund eine Minute.
 *
 * Aufruf ueber ./PREVIEW.sh, Ziel ueber -Dpreview.out steuerbar.
 *
 * Liegt bewusst unter src/test: so landet es nicht im Auslieferungsartefakt.
 * Es ist kein Test - JUnit wuerde hier nichts pruefen, was ein Blick auf das
 * Bild nicht besser zeigt.
 *
 * Die aeussere Klasse erweitert bewusst NICHT Application - sonst verlangt der
 * JavaFX-Starter die Module auf dem Module-Path ("JavaFX runtime components
 * are missing"). Gleicher Grund wie bei l9g.app.drivemount.Launcher.
 */
public final class UiPreview
{
  private UiPreview()
  {
  }

  public static void main(String[] args)
  {
    Application.launch(PreviewApp.class, args);
  }

  public static class PreviewApp extends Application
  {
  @Override
  public void start(Stage stage) throws Exception
  {
    // Dritter Einstiegspunkt neben Launcher und DrivemountSpring#main: auch
    // hier muss der mitgelieferte Schluessel gesetzt sein, sonst scheitert die
    // Entschluesselung der {AES256}-Werte beim Kontextstart.
    DrivemountSpring.pointToBundledSecret();

    var context = new SpringApplicationBuilder(DrivemountSpring.class)
      .web(WebApplicationType.NONE)
      .headless(false)
      .run();

    FXMLLoader loader = new FXMLLoader(
      getClass().getResource("/l9g/app/drivemount/ui/login.fxml"));
    loader.setControllerFactory(context::getBean);
    Region root = loader.load();

    if(!"false".equals(System.getProperty("preview.sample")))
    {
      fillSampleContent(loader);
    }

    Scene scene = new Scene(root);
    scene.getStylesheets().add(getClass()
      .getResource("/l9g/app/drivemount/ui/sonia.css").toExternalForm());
    // Ohne diese beiden Aufrufe kommt ein ungelayoutetes Bild heraus.
    root.applyCss();
    root.layout();

    File out = new File(System.getProperty("preview.out", "ui-preview.png"));
    ImageIO.write(toBufferedImage(scene.snapshot(null)), "png", out);
    System.out.println("Vorschau: " + out.getAbsolutePath());

    context.close();
    Platform.exit();
  }

  /**
   * Fuellt Statuszeile und Ergebnisliste mit Beispielinhalt, damit die
   * Vorschau den Zustand nach einem Login zeigt und nicht die leere Maske -
   * Ausrichtung und Zeilenumbrueche sieht man sonst nicht.
   * Abschaltbar mit -Dpreview.sample=false.
   *
   * Die Knoten kommen aus dem FXML-Namespace; die Felder im Controller sind
   * private.
   */
  @SuppressWarnings("unchecked")
  private static void fillSampleContent(FXMLLoader loader)
  {
    var namespace = loader.getNamespace();

    // -Dpreview.status="..." zum Pruefen langer/umbrechender Meldungen
    ((Label)namespace.get("statusLabel")).setText(
      System.getProperty("preview.status",
        "2 Laufwerk(e) verbunden - Fenster schliesst sich"));

    ((ListView<String>)namespace.get("resultList")).getItems().addAll(
      "[OK] User Home - \\\\fileserver.example.org\\home verbunden (H:)",
      "[OK] Group Share - \\\\fileserver.example.org\\group verbunden (G:)");
  }

  /**
   * SwingFXUtils waere der uebliche Weg, liegt aber im Modul javafx.swing -
   * keine Abhaengigkeit des Projekts. Pixel also von Hand kopieren.
   */
  private static BufferedImage toBufferedImage(WritableImage image)
  {
    int width = (int)image.getWidth();
    int height = (int)image.getHeight();
    BufferedImage target =
      new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    PixelReader pixels = image.getPixelReader();
    for(int y = 0; y < height; y++ )
    {
      for(int x = 0; x < width; x++ )
      {
        target.setRGB(x, y, pixels.getArgb(x, y));
      }
    }
    return target;
  }
  }
}
