/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import l9g.app.drivemount.model.SmbShare;

/**
 * Ergebnis eines einzelnen Mount-Versuchs.
 *
 * <p>Bewusst kein Exception-basiertes Modell: es wird eine Liste von Shares
 * nacheinander verbunden, und ein Fehlschlag beim ersten darf die weiteren
 * nicht verhindern. Der {@code LoginController} sammelt deshalb je Share ein
 * {@code MountResult} ein und zeigt alle zusammen an. Nur wenn <i>jeder</i>
 * Eintrag {@link #success()} meldet, schliesst sich das Fenster nach der
 * konfigurierten Wartezeit von selbst; andernfalls bleibt es offen, damit die
 * Meldungen lesbar bleiben.</p>
 *
 * <p>{@link #message()} ist fuer den Benutzer bestimmt, nicht fuer das Log:
 * deutscher Klartext, bei Fehlern mit dem nativen Rueckgabecode in Klammern,
 * damit eine Rueckfrage eindeutig bleibt ("Anmeldedaten abgelehnt (86)").
 * Zusammengesetzt wird sie von der jeweiligen {@code errorMessage}-Methode
 * des Mounters.</p>
 *
 * @param share   der Share, auf den sich das Ergebnis bezieht
 * @param success {@code true}, wenn der Share verbunden wurde
 * @param message Meldung fuer die Oberflaeche, deutsch und ohne Geheimnisse
 */
public record MountResult(SmbShare share, boolean success, String message)
{
  /**
   * Erfolgreiches Ergebnis.
   *
   * @param share   der verbundene Share
   * @param message Meldung fuer die Oberflaeche, etwa der Mountpoint, den das
   *                Betriebssystem zurueckgemeldet hat
   * @return Ergebnis mit {@code success == true}
   */
  public static MountResult ok(SmbShare share, String message)
  {
    return new MountResult(share, true, message);
  }

  /**
   * Fehlgeschlagenes Ergebnis.
   *
   * @param share   der nicht verbundene Share
   * @param message Grund im Klartext, moeglichst mit dem nativen
   *                Rueckgabecode - nie mit Passwort oder Token
   * @return Ergebnis mit {@code success == false}
   */
  public static MountResult failed(SmbShare share, String message)
  {
    return new MountResult(share, false, message);
  }
}
