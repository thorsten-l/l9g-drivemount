/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount;

import de.l9g.crypto.core.AppSecretKey;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Die Spring-Boot-Wurzel.
 *
 * <p>Im normalen Betrieb wird diese Klasse <b>nicht</b> gestartet, sondern
 * von {@link DrivemountApplication#init()} nur als Quelle des Kontexts
 * benutzt. Einstiegspunkt ist {@link Launcher}.</p>
 *
 * <p>Die {@code main}-Methode existiert ausschliesslich fuer die
 * AOT-Verarbeitung ({@code spring-boot:process-aot}) des Native Builds: der
 * AOT-Prozessor ruft die konfigurierte Hauptklasse auf, um den Kontext
 * einmal zu bauen und daraus Bean-Definitionen zu erzeugen. Ueber
 * {@link Launcher} darf das nicht laufen - der wuerde JavaFX starten, und
 * der Build bliebe am offenen Login-Fenster haengen. Genau das ist einmal
 * passiert; {@code native-image} wurde nie erreicht. Der Builder unten ist
 * deshalb aufgebaut wie der in {@code DrivemountApplication#init()}, damit
 * die erzeugten Metadaten zum spaeteren Laufzeit-Kontext passen.</p>
 *
 * <p>Eine dritte Besonderheit betrifft ebenfalls das Native Image: Spring
 * registriert den generierten
 * {@code DrivemountSpring__ApplicationContextInitializer} <i>bedingt</i>,
 * unter {@code typeReached} auf diese Klasse. Bei einer gewoehnlichen
 * Boot-Anwendung ist die Hauptklasse zugleich Einstiegspunkt und damit immer
 * initialisiert - hier nicht. {@code Launcher} stoesst die Initialisierung
 * deshalb ausdruecklich an, siehe dort.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DrivemountSpring
{
  /** Nur als Kontext-Quelle gedacht; Spring instanziiert die Klasse selbst. */
  public DrivemountSpring()
  {
  }

  /**
   * Verweist l9g-crypto auf den mitgelieferten Schluessel unter
   * {@code assets/secret.bin}.
   *
   * <p>Muss vor dem <b>ersten</b> Zugriff auf l9g-crypto laufen:
   * {@code AppSecretKey} ist ein Singleton, das beim ersten
   * {@code getInstance()} den Schluessel aufloest. Ohne diesen Hinweis sucht
   * es eine Datei {@code data/secret.bin} im Arbeitsverzeichnis, findet
   * keine, erzeugt stillschweigend eine neue - und jede Entschluesselung
   * scheitert danach an "Tag mismatch".</p>
   *
   * <p>Als System-Property und nicht als Umgebungsvariable, weil ein per
   * Doppelklick gestarteter Prozess seine eigene Umgebung nicht setzen kann.
   * Ein gesetztes {@code SECRET_PATH} behaelt Vorrang - der Schluessel
   * laesst sich also von aussen umbiegen.</p>
   *
   * <p>Aufzurufen aus <b>allen drei</b> Einstiegspunkten:
   * {@link Launcher} zur Laufzeit, {@link #main(String[])} waehrend
   * {@code spring-boot:process-aot} und {@code UiPreview} fuer das
   * Vorschauwerkzeug.</p>
   */
  public static void pointToBundledSecret()
  {
    if(System.getProperty(AppSecretKey.SECRET_PATH_PROPERTY_NAME) == null)
    {
      System.setProperty(AppSecretKey.SECRET_PATH_PROPERTY_NAME,
        AppSecretKey.CLASSPATH_PREFIX + "assets/secret.bin");
    }
  }

  /**
   * Kein -e/-h wie im {@link Launcher}: Diese main sieht niemals
   * Kommandozeilen-Argumente. spring-boot:process-aot startet nicht sie,
   * sondern org.springframework.boot.SpringApplicationAotProcessor; der
   * verbraucht sechs eigene Argumente (Application-Klasse, drei
   * Ausgabeverzeichnisse, groupId, artifactId) und reicht per
   * Arrays.copyOfRange(args, 6, ...) nur weiter, was in der
   * process-aot-Execution unter "arguments" konfiguriert ist - hier nichts.
   * Ein System.exit() waere an dieser Stelle sogar schaedlich: es wuerde den
   * Build abbrechen.
   *
   * @param args vom AOT-Prozessor durchgereichte Argumente - in dieser
   *             Konfiguration immer leer
   */
  public static void main(String[] args)
  {
    pointToBundledSecret();

    new SpringApplicationBuilder(DrivemountSpring.class)
      .web(WebApplicationType.NONE)
      .run(args)
      .close();
  }

}
