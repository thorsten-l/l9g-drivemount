/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import l9g.app.drivemount.ui.LicenseDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Die JavaFX-Anwendung: startet den Spring-Kontext, laedt die Oberflaeche und
 * raeumt am Ende beides wieder ab.
 *
 * <p><b>Der Start ist JavaFX-zuerst, Spring-danach</b> - anders als bei einer
 * gewoehnlichen Boot-Anwendung. {@link Launcher} ruft
 * {@code Application.launch}, JavaFX ruft daraufhin {@link #init()}, und erst
 * dort entsteht der Spring-Kontext. Das hat einen praktischen Grund: JavaFX
 * will seinen eigenen Thread zuerst haben, und ein Kontext, der vorher
 * hochfaehrt, hilft niemandem.</p>
 *
 * <p>Die Verbindung zwischen beiden Welten ist eine einzige Zeile in
 * {@link #start(Stage)}: {@code loader.setControllerFactory(context::getBean)}
 * laesst den FXMLLoader seine Controller aus dem Spring-Kontext holen. Genau
 * deshalb sind die FXML-Controller {@code @Component}s und koennen ihre
 * Abhaengigkeiten ueber den Konstruktor bekommen.</p>
 *
 * <p>Der Kontext laeuft mit {@code WebApplicationType.NONE} - Spring liefert
 * hier nur Dependency Injection und Konfiguration, keinen Server.</p>
 */
public class DrivemountApplication extends Application
{
  private static final Logger LOG
    = LoggerFactory.getLogger(DrivemountApplication.class);

  private ConfigurableApplicationContext context;

  /** Von JavaFX per Reflexion aufgerufen; nichts zu tun vor {@link #init()}. */
  public DrivemountApplication()
  {
  }

  /**
   * Faehrt den Spring-Kontext hoch, bevor die Oberflaeche entsteht.
   *
   * <p>Laeuft auf dem JavaFX-Launcher-Thread, nicht auf dem Application
   * Thread - deshalb darf hier noch keine Oberflaeche angefasst werden. Die
   * Kommandozeilenargumente werden durchgereicht, damit Angaben wie
   * {@code --logging.file.name=...} Spring auch wirklich erreichen.</p>
   *
   * <p>{@code headless(false)} ist noetig, weil Spring Boot sonst
   * {@code java.awt.headless=true} setzt und JavaFX damit nicht arbeiten
   * kann.</p>
   */
  @Override
  public void init()
  {
    context = new SpringApplicationBuilder(DrivemountSpring.class)
      // Muss explizit gesetzt werden: Spring Boot leitet die Main-Klasse
      // sonst per Stack-Walk nach einer main-Methode her - die gibt es auf
      // dem JavaFX-Launcher-Thread nicht. Auf der JVM bleibt das folgenlos,
      // im Native Image bricht die AOT-Verarbeitung damit beim Start ab
      // ("mainApplicationClass not found").
      .main(DrivemountSpring.class)
      .web(WebApplicationType.NONE)
      .headless(false)
      .run(getParameters().getRaw().toArray(String[]::new));
  }

  /**
   * Baut das Login-Fenster auf.
   *
   * <p>Das Fenster ist quadratisch und nicht veraenderbar: mit
   * {@code setResizable(false)} nimmt es die bevorzugte Groesse des
   * Wurzelelements an, und die quadratische Form laesst den gestalteten
   * Hintergrund neben der Karte ueberhaupt erst sichtbar werden.</p>
   *
   * <p>Hier haengt auch die einzige versteckte Funktion der Maske:
   * {@link LicenseDialog#install(Scene, String)} legt Strg+Alt+L auf die
   * Szene, was das Lizenzfenster oeffnet.</p>
   *
   * @param stage das von JavaFX bereitgestellte Hauptfenster
   * @throws Exception wenn FXML oder Stylesheet nicht geladen werden koennen
   */
  @Override
  public void start(Stage stage) throws Exception
  {
    FXMLLoader loader = new FXMLLoader(
      getClass().getResource("/l9g/app/drivemount/ui/login.fxml"));
    loader.setControllerFactory(context::getBean);
    Scene scene = new Scene(loader.load());
    // Stylesheet hier statt per <stylesheets> im FXML: spart FXML einen
    // weiteren reflektiv aufgeloesten Typ (java.net.URL), der sonst im
    // Native Image registriert werden muesste.
    scene.getStylesheets().add(getClass()
      .getResource("/l9g/app/drivemount/ui/sonia.css").toExternalForm());

    // Strg+Alt+L (macOS: Ctrl+Option+L) oeffnet das Lizenzfenster. Die
    // Version kommt ueber einen ObjectProvider, weil BuildProperties nur
    // existiert, wenn META-INF/build-info.properties erzeugt wurde - ohne
    // Maven-Lauf bleibt die Zeile eben ohne Versionsnummer.
    BuildProperties build
      = context.getBeanProvider(BuildProperties.class).getIfAvailable();
    LicenseDialog.install(scene, build != null ? build.getVersion() : null);

    stage.setScene(scene);
    stage.setTitle("drivemount");
    stage.setResizable(false);
    stage.show();
    LOG.info("Login-Fenster geoeffnet (JavaFX {}, Java {})",
      System.getProperty("javafx.runtime.version", "?"),
      System.getProperty("java.version"));
  }

  /**
   * Schliesst den Spring-Kontext beim Beenden.
   *
   * <p>Wird von JavaFX gerufen, wenn das letzte Fenster zugeht oder
   * {@code Platform.exit()} lief - letzteres macht der
   * {@code LoginController}, wenn alle Mounts erfolgreich waren und die
   * Wartezeit abgelaufen ist.</p>
   */
  @Override
  public void stop()
  {
    LOG.info("Anwendung wird beendet");
    if (context != null)
    {
      context.close();
    }
    Platform.exit();
  }
}
