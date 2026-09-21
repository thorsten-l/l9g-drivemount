/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import l9g.app.drivemount.model.SmbShare;

/**
 * Plattformabstraktion fuer das Verbinden eines SMB-Shares.
 *
 * <p>Es gibt genau drei Implementierungen, je eine pro Betriebssystem, und
 * sie haben ausser dieser Signatur nichts gemeinsam - die Wege zum Mount sind
 * technisch grundverschieden:</p>
 *
 * <table border="1">
 * <caption>Implementierungen</caption>
 * <tr><th>Plattform</th><th>Klasse</th><th>Mechanismus</th></tr>
 * <tr><td>Windows</td><td>{@link WindowsWNetMounter}</td>
 *     <td>{@code WNetAddConnection2W} aus der Mpr.dll, ueber FFM/Panama
 *         direkt aufgerufen</td></tr>
 * <tr><td>macOS</td><td>{@link MacosMounter}</td>
 *     <td>{@code NetFSMountURLSync} aus dem NetFS.framework, ebenfalls
 *         ueber FFM</td></tr>
 * <tr><td>Linux</td><td>{@link LinuxGioMounter}</td>
 *     <td>Kindprozess {@code gio mount}, dessen interaktive Abfragen ueber
 *         stdin beantwortet werden</td></tr>
 * </table>
 *
 * <p>Ausgewaehlt wird die passende Implementierung einmalig beim Start des
 * Spring-Kontexts, siehe {@link MounterConfig#mounter()}.</p>
 *
 * <p><b>Zwei Regeln gelten fuer jede Implementierung</b>, beide aus
 * Sicherheitsgruenden und beide ohne Ausnahme:</p>
 *
 * <ol>
 * <li>Das Passwort darf <b>niemals</b> als Prozessargument uebergeben werden.
 *     Argumente sind fuer jeden anderen Benutzer des Rechners sichtbar - unter
 *     Linux in {@code ps}, unter Windows im Task-Manager. Prozessbasierte
 *     Mounter schreiben es deshalb auf stdin des Kindprozesses, die
 *     FFM-basierten legen es in nativen Speicher, den sie selbst wieder
 *     ueberschreiben.</li>
 * <li>Das Passwort darf nicht geloggt werden, auch nicht auf DEBUG und auch
 *     nicht verkuerzt. Geloggt werden Rueckgabecodes, Benutzername, Domaene
 *     und Share - nie das Geheimnis selbst.</li>
 * </ol>
 *
 * <p>Der Aufrufer ({@code LoginController}) uebergibt das Passwort als
 * {@code char[]} und ueberschreibt das Array anschliessend selbst. Eine
 * Implementierung darf sich also keine Referenz darauf merken; sie muss den
 * Inhalt innerhalb von {@link #mount} verbrauchen.</p>
 */
public interface Mounter
{
  /**
   * Verbindet einen Share mit den uebergebenen Anmeldedaten.
   *
   * <p>Die Methode blockiert bis zum Ergebnis - der Aufrufer ruft sie
   * deshalb aus einem JavaFX-{@code Task} heraus auf und nicht auf dem
   * Application Thread. Sie wirft keine Exception fuer fachliche Fehler:
   * ein abgelehntes Passwort, ein unbekannter Servername oder ein
   * nicht erreichbarer Port kommen als {@link MountResult} mit
   * {@code success == false} und einer deutschen Klartextmeldung zurueck,
   * damit die Oberflaeche alle Shares gleich behandeln kann.</p>
   *
   * @param share    der zu verbindende Share, inklusive optionalem
   *                 Windows-Laufwerksbuchstaben
   * @param username Benutzername ohne Domaenenanteil, so wie er im
   *                 Login-Dialog eingegeben wurde
   * @param password Passwort im Klartext; die Implementierung verbraucht es
   *                 innerhalb des Aufrufs und merkt sich keine Referenz
   * @param domain   AD-Domaene, aus dem Mail-Claim des Tokens abgeleitet
   * @return Ergebnis mit Erfolgskennzeichen und einer Meldung fuer die
   *         Oberflaeche, nie {@code null}
   */
  MountResult mount(SmbShare share, String username, char[] password,
    String domain);
}
