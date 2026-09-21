/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import l9g.app.drivemount.model.SmbShare;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux-Mount ueber GVfs, also den Umweg ueber den Kindprozess
 * {@code gio mount smb://...}.
 *
 * <p><b>Warum ein Prozess und kein nativer Aufruf?</b> Anders als Windows und
 * macOS hat Linux keine System-API fuer einen SMB-Mount im Benutzerkontext.
 * Der Kernel-Mount ({@code mount.cifs}) braucht root. GVfs mountet dagegen im
 * Userspace, ohne besondere Rechte, und genau das will die Anwendung. Der
 * Preis ist die Prozessgrenze - und dass {@code gio} ein Werkzeug fuer
 * Menschen ist und sich auch so verhaelt.</p>
 *
 * <h2>Die Anmeldedaten gehen ueber stdin</h2>
 *
 * <p>{@code gio mount} fragt Benutzer, Domaene und Passwort interaktiv ab.
 * Genau dort werden sie hineingeschrieben - <b>niemals</b> als
 * Prozessargument, das stuende sonst in der Prozessliste fuer jeden lesbar.
 * Die Reihenfolge ist gegen gio 2.80 (Ubuntu 24.04) am realen Fileserver
 * <b>verifiziert</b>:</p>
 *
 * <pre>
 *   User [&lt;user&gt;]: Domain [WORKGROUP]: Password:
 * </pre>
 *
 * <p>Also Benutzer, Domaene, Passwort - in dieser Folge werden die drei
 * Zeilen geschrieben. Die eckigen Klammern sind Vorgaben, die gio
 * uebernaehme, wenn man nur Enter schickt; hier wird jede Zeile
 * ausgeschrieben. Eine leere Domaenenzeile ist erlaubt und bedeutet
 * "Vorgabe".</p>
 *
 * <h2>gio meldet Fehler nicht als Fehler</h2>
 *
 * <p>Das ist die unangenehmste Eigenschaft und der Grund fuer
 * {@link #errorMessage(int, String)}: bei <b>abgelehnten Anmeldedaten</b>
 * schreibt gio keine Fehlermeldung, sondern <i>fragt erneut</i> - so lange,
 * bis stdin geschlossen ist. Uebrig bleibt der komplette Abfrageblock und
 * Exit-Code 2. Nur echte Transportfehler kommen als eine Zeile mit
 * {@code gio:}-Praefix. Beides ist am realen Fileserver gemessen.</p>
 *
 * <p>Der Mountpunkt liegt anschliessend unter
 * {@code /run/user/$UID/gvfs/} und erscheint in den Dateimanagern der
 * GNOME-Welt. Voraussetzung zur Laufzeit ist {@code gvfs} samt Backends
 * ({@code gvfs-backends}); fehlt es, scheitert schon das Starten des
 * Prozesses und die Meldung weist darauf hin.</p>
 *
 * <p>TODO (Claude Code):</p>
 * <ul>
 * <li>Prompt-Reihenfolge auf weiteren Distributionen gegenpruefen</li>
 * <li>kio-Fallback fuer KDE ({@code kioclient}) ergaenzen</li>
 * <li>{@code gio mount --unmount} fuer eine "Trennen"-Funktion</li>
 * </ul>
 */
public class LinuxGioMounter implements Mounter
{
  private static final Logger LOG
    = LoggerFactory.getLogger(LinuxGioMounter.class);

  /**
   * Nichts vorzubereiten - anders als {@link MacosMounter} loest diese
   * Implementierung keine nativen Symbole auf. Ob {@code gio} vorhanden ist,
   * zeigt sich erst beim ersten Mount.
   */
  public LinuxGioMounter()
  {
  }

  /**
   * {@inheritDoc}
   *
   * <p>Ablauf: Prozess starten, die drei Antwortzeilen auf stdin schreiben,
   * stdin schliessen, warten, Ausgabe auswerten. Das Schliessen von stdin ist
   * nicht optional - gio fragt sonst weiter und der Prozess laeuft in den
   * Timeout.</p>
   */
  @Override
  public MountResult mount(SmbShare share, String username, char[] password,
    String domain)
  {
    LOG.info("Mount '{}': {}", share.label(), share.url());

    try
    {
      // redirectErrorStream: gio schreibt die Abfragen nach stdout, echte
      // Fehler nach stderr. Zusammengelegt bleibt die Reihenfolge erhalten
      // und es gibt nur einen Strom zu lesen.
      Process process = new ProcessBuilder("gio", "mount", share.url())
        .redirectErrorStream(true)
        .start();

      // try-with-resources schliesst stdin am Ende des Blocks - erst dadurch
      // hoert gio auf zu fragen und beendet sich.
      try (var stdin = process.getOutputStream())
      {
        // Reihenfolge: Benutzer, Domaene, Passwort (siehe Klassendoku).
        // Eine leere Zeile fuer die Domaene uebernimmt gios Vorgabe.
        String answers = username + "\n"
          + (domain == null ? "" : domain) + "\n"
          + new String(password) + "\n";
        stdin.write(answers.getBytes(StandardCharsets.UTF_8));
      }

      if (!process.waitFor(30, TimeUnit.SECONDS))
      {
        // Kann passieren, wenn der Server gar nicht antwortet: gio haengt
        // dann im Verbindungsaufbau, nicht in einer Abfrage.
        process.destroyForcibly();
        return MountResult.failed(share, "gio-Timeout");
      }

      // Erst nach waitFor lesen ist nur zulaessig, weil gio sehr wenig
      // ausgibt - ein paar Zeilen Abfragetext. Fuellte ein Kindprozess die
      // Pipe (ueblich sind 64 KiB), blockierte es und wir warteten
      // gegenseitig aufeinander.
      String output = new String(process.getInputStream().readAllBytes(),
        StandardCharsets.UTF_8).trim();
      int exitCode = process.exitValue();
      LOG.info("gio mount liefert {} fuer {}", exitCode, share.url());
      // Zeilenumbrueche zu '|': die Abfragen kommen sonst als mehrzeiliger
      // Block ins Log und zerreissen jede Zeilenorientierung.
      LOG.debug("gio-Ausgabe: {}", output.replace('\n', '|'));

      if (exitCode == 0)
      {
        return MountResult.ok(share, share.url() + " via gvfs verbunden");
      }
      // Ein bereits gemounteter Share ist kein Fehlschlag, sondern der
      // Zustand, den der Benutzer haben wollte - etwa beim zweiten Start.
      if (output.contains("already mounted"))
      {
        return MountResult.ok(share, share.url() + " war bereits verbunden");
      }
      return MountResult.failed(share, errorMessage(exitCode, output));
    }
    catch (InterruptedException e)
    {
      // Nur hier gehoert das Flag wiederhergestellt - vorher stand der
      // interrupt() im allgemeinen Exception-Zweig und markierte den Thread
      // auch bei einem fehlenden gio-Binary als unterbrochen.
      Thread.currentThread().interrupt();
      return MountResult.failed(share, "Mount abgebrochen");
    }
    catch (Exception e)
    {
      // Haeufigster Fall hier: gio gibt es gar nicht (IOException beim
      // Starten). Deshalb der Hinweis auf gvfs in der Meldung.
      return MountResult.failed(share, "Mount fehlgeschlagen: "
        + e.getMessage() + " (ist gvfs installiert?)");
    }
  }

  /**
   * Macht aus dem, was gio hinterlaesst, eine brauchbare Meldung.
   *
   * <p>Die Reihenfolge der drei Versuche ist Absicht und folgt der
   * Verlaesslichkeit der Quellen:</p>
   *
   * <ol>
   * <li><b>Eine Zeile mit {@code gio:}-Praefix</b> ist eine echte
   *     Fehlermeldung, etwa
   *     {@code gio: smb://...: Failed to mount Windows share: Connection
   *     refused}. Davon wird alles bis zum zweiten Doppelpunkt abgeschnitten,
   *     uebrig bleibt der eigentliche Grund.</li>
   * <li><b>{@code Authentication Required} im Text</b> heisst: gio hat erneut
   *     gefragt, die Anmeldedaten wurden also abgelehnt. Das ist der einzige
   *     Hinweis, den es in diesem Fall gibt - einen Fehlertext schreibt gio
   *     nicht.</li>
   * <li>Bleibt nur der <b>Exit-Code</b>, der fuer sich genommen wenig
   *     aussagt, aber besser ist als der rohe Abfrageblock.</li>
   * </ol>
   *
   * <p>Ohne diese Filterung landeten die sechs Zeilen Abfragetext
   * ungeschnitten in der Ergebnisliste der Oberflaeche - so ist es beim
   * ersten Lauf gegen den echten Fileserver auch passiert.</p>
   *
   * <p>Paketsichtbar und nicht privat, damit {@code LinuxGioMounterTest} die
   * Faelle ohne installiertes gio pruefen kann.</p>
   *
   * @param exitCode Exit-Code des gio-Prozesses, ungleich 0
   * @param output   die zusammengefuehrte Ausgabe, darf {@code null} sein
   * @return deutscher Klartext fuer die Oberflaeche
   */
  static String errorMessage(int exitCode, String output)
  {
    if (output != null)
    {
      // Echte Fehlermeldung, falls vorhanden: "gio: <url>: <grund>"
      for (String line : output.lines().toList())
      {
        if (line.startsWith("gio:"))
        {
          // Zweiter Doppelpunkt trennt die URL vom Grund; fehlt er, bleibt
          // die Zeile ohne das Praefix stehen.
          int colon = line.indexOf(": ", "gio:".length());
          return colon < 0 ? line.substring(4).trim()
            : line.substring(colon + 2).trim();
        }
      }
      // Sonst: wiederholte Abfrage bedeutet abgelehnte Anmeldedaten.
      if (output.contains("Authentication Required"))
      {
        return "Anmeldung abgelehnt: Benutzername, Domaene oder Passwort"
          + " falsch";
      }
    }
    return "gio-Exit-Code " + exitCode;
  }
}
