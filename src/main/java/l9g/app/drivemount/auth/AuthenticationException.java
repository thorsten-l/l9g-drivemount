/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.auth;

/**
 * Die Anmeldung gegen Keycloak ist fehlgeschlagen.
 *
 * <p>Deckt beides ab: eine fachliche Ablehnung (falsches Passwort, falsches
 * Einmalkennwort) und einen technischen Fehlschlag (Endpunkt nicht
 * erreichbar, TLS-Problem). Unterschieden wird ueber die Meldung, die direkt
 * in der Oberflaeche landet und deshalb deutsch formuliert ist.</p>
 *
 * <p>Eine <i>geprueft</i>e Ausnahme mit Absicht: der Aufrufer soll den Fall
 * behandeln muessen, denn eine fehlgeschlagene Anmeldung ist hier der
 * Normalfall und kein Programmfehler.</p>
 *
 * <p>Keycloak liefert fuer ein falsches Passwort und ein falsches
 * Einmalkennwort <b>dieselbe</b> Antwort ("Invalid user credentials"). Der
 * Versuch, die beiden in der Oberflaeche zu unterscheiden, ist deshalb
 * aussichtslos - und waere auch nicht wuenschenswert.</p>
 */
public class AuthenticationException extends Exception
{
  /**
   * Fehlschlag ohne bekannte Ursache dahinter.
   *
   * @param message Grund im Klartext, wird dem Benutzer angezeigt
   */
  public AuthenticationException(String message)
  {
    super(message);
  }

  /**
   * Fehlschlag mit einer technischen Ursache.
   *
   * @param message Grund im Klartext, wird dem Benutzer angezeigt
   * @param cause   die zugrunde liegende Ausnahme, etwa ein
   *                Netzwerk- oder TLS-Fehler
   */
  public AuthenticationException(String message, Throwable cause)
  {
    super(message, cause);
  }
}
