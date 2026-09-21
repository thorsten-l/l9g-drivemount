/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Waehlt die zum laufenden Betriebssystem passende {@link Mounter}-
 * Implementierung aus.
 *
 * <p>Die Auswahl passiert genau einmal, beim Hochfahren des Spring-Kontexts,
 * und nicht bei jedem Mount. Das ist wichtiger, als es aussieht: die beiden
 * FFM-basierten Mounter loesen in ihrem Konstruktor native Symbole auf, was
 * einmalig Arbeit kostet und - schlimmer - fehlschlagen kann. Passiert das
 * hier, faellt es beim Start auf und nicht erst beim Klick auf "Verbinden".</p>
 *
 * <p>Erkannt wird ueber {@code os.name}. Alles, was weder "win" noch "mac"
 * enthaelt, gilt als Linux; das schliesst BSD und Solaris mit ein, wo
 * {@code gio} zumindest existieren kann. Eine Ausnahme fuer unbekannte
 * Systeme zu werfen waere die Alternative, wuerde aber den Start verhindern,
 * statt den Fehler dort zu melden, wo er hingehoert - am einzelnen Share.</p>
 */
@Configuration
public class MounterConfig
{
  private static final Logger LOG
    = LoggerFactory.getLogger(MounterConfig.class);

  /** Konfigurationsklasse, von Spring instanziiert. */
  public MounterConfig()
  {
  }

  /**
   * Erzeugt den Mounter fuer das laufende Betriebssystem.
   *
   * <p>Die Log-Zeile am Ende ist die erste Stelle, an der sich eine
   * Fehlsuche festmachen laesst: sie nennt {@code os.name} im Original und
   * die tatsaechlich gewaehlte Klasse. Steht dort unter Windows
   * {@code LinuxGioMounter}, ist die Erkennung schuld und nicht der
   * Mount.</p>
   *
   * @return die passende Implementierung, nie {@code null}
   */
  @Bean
  public Mounter mounter()
  {
    String os = System.getProperty("os.name", "").toLowerCase();
    Mounter mounter;
    if (os.contains("win"))
    {
      mounter = new WindowsWNetMounter();
    }
    else if (os.contains("mac"))
    {
      mounter = new MacosMounter();
    }
    else
    {
      // Kein eigener Zweig fuer BSD/Solaris: dort ist gio die einzige
      // realistische Option, also faellt alles Uebrige hierher.
      mounter = new LinuxGioMounter();
    }
    LOG.info("Betriebssystem '{}' -> Mounter {}",
      System.getProperty("os.name"), mounter.getClass().getSimpleName());
    return mounter;
  }
}
