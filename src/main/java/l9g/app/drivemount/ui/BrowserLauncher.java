/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import javafx.application.HostServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Oeffnet eine Adresse im <b>Standardbrowser des Betriebssystems</b>.
 *
 * <p>Zwei Wege, in dieser Reihenfolge:</p>
 *
 * <ol>
 * <li>JavaFX-{@link HostServices}. {@code showDocument} landet ueber Glass
 *     direkt bei der Plattform - unter macOS bei {@code LSOpenCFURLRef},
 *     also genau der Launch-Services-Schnittstelle, die auch
 *     {@code /usr/bin/open} benutzt; unter Windows bei
 *     {@code ShellExecute}, unter Linux bei {@code gtk_show_uri}. Kein
 *     Kindprozess, kein {@code java.awt.Desktop}, keine reflektive
 *     Aufloesung, die im Native Image eigene Metadaten braeuchte.</li>
 * <li>Schlaegt das fehl oder sind die HostServices gar nicht gesetzt (etwa
 *     im Vorschauwerkzeug {@code UiPreview}), folgt das
 *     Betriebssystem-Kommando: {@code open} unter macOS,
 *     {@code xdg-open} unter Linux, {@code rundll32
 *     url.dll,FileProtocolHandler} unter Windows. Dieselbe Wirkung, nur
 *     ueber einen Kindprozess.</li>
 * </ol>
 *
 * <p>Die {@code HostServices} gehoeren der JavaFX-{@code Application} und
 * sind damit erst zur Laufzeit zu haben, waehrend der Aufrufer ein
 * Spring-Bean ist. Statt sie durch den Kontext zu reichen, hinterlegt
 * {@code DrivemountApplication#start} sie hier einmalig - dasselbe Muster
 * wie bei {@link LicenseDialog#install}.</p>
 *
 * <p>Die geoeffnete Adresse stammt immer aus der eigenen Konfiguration
 * ({@code drivemount.account-security-url}), nie aus einer Eingabe. Das
 * Kommando wird trotzdem als Argumentliste uebergeben und nicht ueber eine
 * Shell zusammengesetzt - eine Shell im Spiel zu haben, waere eine Falle
 * fuer den naechsten, der hier eine andere Quelle anschliesst.</p>
 */
public final class BrowserLauncher
{
  private static final Logger LOG
    = LoggerFactory.getLogger(BrowserLauncher.class);

  /**
   * Von JavaFX bereitgestellt, von {@code DrivemountApplication} gesetzt.
   * {@code volatile}, weil Setzen und Lesen auf demselben Thread erfolgen
   * sollten, aber nichts das erzwingt.
   */
  private static volatile HostServices hostServices;

  private BrowserLauncher()
  {
  }

  /**
   * Hinterlegt die {@code HostServices} der laufenden Anwendung.
   *
   * @param services aus {@code Application#getHostServices()}
   */
  public static void install(HostServices services)
  {
    hostServices = services;
  }

  /**
   * Oeffnet die Adresse im Standardbrowser.
   *
   * <p>Wirft nichts: ein fehlgeschlagener Browserstart ist kein Grund, die
   * Anmeldemaske zu stoeren. Was schiefging, steht im Log.</p>
   *
   * @param url zu oeffnende Adresse; {@code null} oder leer wird ignoriert
   */
  public static void open(String url)
  {
    if (url == null || url.isBlank())
    {
      return;
    }

    LOG.info("Oeffne im Standardbrowser: {}", url);

    HostServices services = hostServices;
    if (services != null)
    {
      try
      {
        services.showDocument(url);
        return;
      }
      catch (RuntimeException e)
      {
        LOG.warn("HostServices konnten {} nicht oeffnen - weiter mit dem"
          + " Betriebssystem-Kommando", url, e);
      }
    }

    openWithCommand(url);
  }

  /**
   * Der Rueckfallweg: das Kommando des Betriebssystems starten.
   *
   * @param url zu oeffnende Adresse
   */
  private static void openWithCommand(String url)
  {
    List<String> command
      = browserCommand(System.getProperty("os.name", ""), url);

    if (command.isEmpty())
    {
      LOG.error("Unbekanntes Betriebssystem '{}' - {} bleibt ungeoeffnet",
        System.getProperty("os.name", ""), url);
      return;
    }

    try
    {
      // Kein waitFor: der Browser laeuft weiter, wenn drivemount laengst
      // beendet ist. Ein- und Ausgabe interessieren nicht.
      new ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    }
    catch (IOException e)
    {
      LOG.error("Kommando {} fehlgeschlagen", command, e);
    }
  }

  /**
   * Das Kommando, mit dem das jeweilige System eine Adresse an den
   * eingestellten Standardbrowser uebergibt.
   *
   * <p>Paketsichtbar und nicht privat, damit {@code BrowserLauncherTest} die
   * Zuordnung ohne laufendes System pruefen kann - denselben Grund haben die
   * paketsichtbaren Methoden in den Mountern.</p>
   *
   * <p>Unter Windows bewusst {@code rundll32 url.dll,FileProtocolHandler}
   * und nicht {@code cmd /c start}: letzteres braucht die Shell, die
   * kaufmaennische Und-Zeichen in einer URL als Trenner liest.</p>
   *
   * @param osName Inhalt der Systemeigenschaft {@code os.name}
   * @param url    zu oeffnende Adresse
   * @return die Argumentliste, oder eine leere Liste bei unbekanntem System
   */
  static List<String> browserCommand(String osName, String url)
  {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);

    if (os.contains("mac"))
    {
      return List.of("open", url);
    }
    if (os.contains("win"))
    {
      return List.of("rundll32", "url.dll,FileProtocolHandler", url);
    }
    if (os.contains("linux") || os.contains("nix") || os.contains("nux"))
    {
      return List.of("xdg-open", url);
    }
    return List.of();
  }
}
