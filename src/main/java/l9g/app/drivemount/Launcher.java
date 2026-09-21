/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount;

import de.l9g.crypto.core.AES256;
import de.l9g.crypto.core.CryptoHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import javafx.application.Application;

/**
 * Einstiegspunkt der Anwendung.
 *
 * <p><b>Warum eine eigene Klasse und nicht einfach
 * {@code DrivemountApplication.main}?</b> Startet man eine Klasse, die von
 * {@link Application} erbt, direkt als Hauptklasse, verlangt der
 * JavaFX-Launcher die {@code javafx.*}-Module auf dem Module Path und bricht
 * sonst mit "JavaFX runtime components are missing" ab. Eine Klasse, die
 * <i>nicht</i> davon erbt, umgeht das - und damit laeuft dieselbe Anwendung
 * vom Classpath, aus dem Spring-Boot-Jar und aus dem Native Image.</p>
 *
 * <p>Nebenbei dient die Klasse als kleines Kommandozeilenwerkzeug fuer die
 * Einrichtung: {@code -i} legt einen Schluessel an, {@code -e}
 * verschluesselt einen Wert dafuer. Ohne Argumente startet die
 * Oberflaeche.</p>
 */
public final class Launcher
{
  /** Schluessellaenge in Byte, vorgegeben von l9g-crypto (AES-256). */
  private static final int KEY_LEN = AES256.KEY_LEN_BYTES;

  /** Reine Utility-Klasse, wird nicht instanziiert. */
  private Launcher()
  {
  }

  /**
   * Startet die Anwendung oder fuehrt einen der Einrichtungsbefehle aus.
   *
   * <pre>
   *   drivemount                       Oberflaeche starten
   *   drivemount -i &lt;pfad/secret.bin&gt;   neuen Schluessel anlegen
   *   drivemount -e &lt;klartext&gt;          Wert verschluesseln
   *   drivemount -h                    Hilfe
   * </pre>
   *
   * <p>Alles, was keinem dieser Muster entspricht, faellt durch und wird an
   * JavaFX weitergereicht - so erreichen Angaben wie
   * {@code --logging.file.name=...} ueber
   * {@code Application.getParameters()} am Ende den Spring-Kontext.</p>
   *
   * @param args Kommandozeilenargumente, duerfen {@code null} sein
   * @throws IOException wenn das Schreiben des Schluessels fehlschlaegt
   */
  public static void main(String[] args) throws IOException
  {
    // Muss vor dem ersten CryptoHandler-Zugriff stehen: AppSecretKey ist ein
    // Singleton, der beim ersten getInstance() den Schluessel aufloest.
    DrivemountSpring.pointToBundledSecret();

    if(args != null)
    {
      // -i steht bewusst VOR getInstance(): der Schluessel soll hier erst
      // entstehen. Ein CryptoHandler.getInstance() wuerde AppSecretKey
      // aufloesen und - wenn nichts da ist - selbst eine data/secret.bin im
      // Arbeitsverzeichnis erzeugen.
      if(args.length == 2 && "-i".equals(args[0]))
      {
        createSecretFile(Path.of(args[1]));
        System.exit(0);
      }

      CryptoHandler cryptoHandler = CryptoHandler.getInstance();

      if(args.length == 2 && "-e".equals(args[0]))
      {
        System.out.println(args[1] + " = \"" + cryptoHandler.encrypt(args[1]) + "\"");
        System.exit(0);
      }

      if(args.length == 1 && "-h".equals(args[0]))
      {
        System.out.println("drivemount (launcher) [-i <path/secret.bin>] [-e clear text] [-h]");
        System.out.println("  -i : initialize secret.bin (nur wenn noch keine existiert)");
        System.out.println("  -e : encrypt clear text");
        System.out.println("  -h : this help");
        System.exit(0);
      }
    }

    initSpringRootForNativeImage();
    Application.launch(DrivemountApplication.class, args);
  }

  /**
   * Nur fuer das Native Image relevant. Die AOT-Verarbeitung registriert
   * DrivemountSpring__ApplicationContextInitializer bedingt, mit der
   * Bedingung "typeReached: DrivemountSpring". Bei einer gewoehnlichen
   * Boot-Anwendung ist die Main-Klasse zugleich Einstiegspunkt und damit
   * immer initialisiert - hier ist der Einstiegspunkt aber Launcher.
   * Spring sucht den Initializer jedoch schon in prepareContext(), also
   * bevor DrivemountSpring als Bean instanziiert wird; ohne dieses
   * explizite Initialisieren bleibt die Bedingung unerfuellt und der Start
   * bricht mit AotInitializerNotFoundException ab.
   *
   * Ein blosses Klassenliteral genuegt nicht - das loest keine
   * Initialisierung aus.
   */
  private static void initSpringRootForNativeImage()
  {
    try
    {
      Class.forName(DrivemountSpring.class.getName(), true,
        Launcher.class.getClassLoader());
    }
    catch(ClassNotFoundException e)
    {
      throw new IllegalStateException("DrivemountSpring nicht ladbar", e);
    }
  }

  /**
   * Erzeugt einen neuen Schluessel und legt ihn ab.
   *
   * <p>Bricht ab, wenn die Datei schon existiert: ein neuer Schluessel macht
   * jeden bereits verschluesselten {@code {AES256}}-Wert unbrauchbar - allen
   * voran das Client-Secret in der {@code application.yaml}. Loeschen muss
   * man also bewusst.</p>
   *
   * <p>Der Aufruf steht im {@code main} <b>vor</b>
   * {@code CryptoHandler.getInstance()}, und das ist kein Zufall: dieser
   * Aufruf wuerde den Schluessel aufloesen und, wenn keiner da ist, selbst
   * still einen erzeugen - ausgerechnet der Befehl zum Anlegen darf das
   * nicht ausloesen.</p>
   *
   * @param secretPath Zielpfad; fehlende Verzeichnisse werden angelegt
   * @throws IOException wenn das Schreiben fehlschlaegt
   */
  private static void createSecretFile(Path secretPath) throws IOException
  {
    if(Files.exists(secretPath))
    {
      System.err.printf("Existiert bereits, wird nicht ueberschrieben: %s%n",
        secretPath);
      System.err.println(
        "Ein neuer Schluessel entwertet alle vorhandenen {AES256}-Werte.");
      System.exit(1);
    }

    Path parent = secretPath.toAbsolutePath().getParent();

    if(parent != null)
    {
      Files.createDirectories(parent);
    }

    byte[] secretKey = new byte[KEY_LEN];
    new SecureRandom().nextBytes(secretKey);

    // Ohne Optionen gilt CREATE + TRUNCATE_EXISTING + WRITE. Ein blosses
    // WRITE wuerde die Standardliste ersetzen und damit CREATE verlieren -
    // der Aufruf scheitert dann an einer noch nicht vorhandenen Datei.
    Files.write(secretPath, secretKey);

    System.out.printf("Neuer Schluessel geschrieben: %s (%d Byte)%n",
      secretPath, KEY_LEN);
  }

}
