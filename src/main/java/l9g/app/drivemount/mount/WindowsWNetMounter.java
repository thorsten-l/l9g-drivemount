/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import l9g.app.drivemount.model.SmbShare;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-Mount ueber die Win32-Funktion {@code WNetAddConnection2W} aus der
 * Mpr.dll, aufgerufen per Java-FFM (Panama).
 *
 * <p><b>Warum FFM und kein {@code net use}?</b> Ein Kindprozess bekaeme das
 * Passwort entweder als Argument (fuer jeden im Task-Manager sichtbar) oder
 * ueber stdin, wo {@code net use} es nur in bestimmten Konstellationen
 * annimmt. Die API nimmt es als Zeiger auf einen Speicherbereich entgegen,
 * den wir selbst anlegen und nach dem Aufruf wieder ueberschreiben. Das ist
 * der einzige Weg, der die Regel aus {@link Mounter} sauber einhaelt.</p>
 *
 * <h2>Die Struktur NETRESOURCEW</h2>
 *
 * <p>Die API erwartet einen Zeiger auf eine {@code NETRESOURCEW}. Es gibt
 * keinen Struct-Generator im Spiel, das Layout wird von Hand gelegt - unter
 * x64 sind das <b>48 Byte</b>: vier {@code DWORD} zu je 4 Byte, dann vier
 * {@code LPWSTR} zu je 8 Byte. Die Zeiger beginnen bei Offset 16, weil der
 * Compiler sie auf 8 ausrichtet und die vier DWORDs zusammen genau 16 Byte
 * fuellen - es entsteht also kein Padding.</p>
 *
 * <pre>
 *  Offset  Typ       Feld             hier belegt mit
 *  ------  --------  ---------------  --------------------------------------
 *       0  DWORD     dwScope          0 (bei einer Verbindung ignoriert)
 *       4  DWORD     dwType           RESOURCETYPE_DISK
 *       8  DWORD     dwDisplayType    0 (ignoriert)
 *      12  DWORD     dwUsage          0 (ignoriert)
 *      16  LPWSTR    lpLocalName      "H:" oder NULL (ohne Laufwerksbuchstabe)
 *      24  LPWSTR    lpRemoteName     UNC-Pfad, etwa Backslash-Backslash-Server
 *      32  LPWSTR    lpComment        NULL
 *      40  LPWSTR    lpProvider       NULL (Windows waehlt den Provider)
 * </pre>
 *
 * <p>Alle Zeichenketten sind {@code LPCWSTR}, also null-terminiertes
 * <b>UTF-16LE</b> - daher das {@code W} am Ende des Funktionsnamens und
 * {@link #wstr(Arena, String)} als einzige Stelle, die Strings erzeugt.</p>
 *
 * <h2>CONNECT_TEMPORARY</h2>
 *
 * <p>Die Verbindung wird ohne Persistenz angelegt. Sie gilt damit nur fuer
 * die aktuelle Anmeldesitzung und ueberlebt keinen Neustart. Das hat eine
 * Konsequenz, die beim Testen schon Zeit gekostet hat: <b>ein {@code net use}
 * in einer ssh-Sitzung zeigt nichts an</b>, auch wenn der interaktive Lauf
 * perfekt funktioniert hat - Windows bindet Laufwerkszuordnungen an die
 * Logon-Session, und die ssh-Sitzung ist eine andere. Nachweis ist der
 * Rueckgabecode im Log, nicht die Ausgabe von {@code net use}.</p>
 *
 * <h2>Native Image</h2>
 *
 * <p>GraalVM erzeugt die Stubs fuer FFM-Downcalls <b>zur Bauzeit</b>. Ein
 * {@code Linker#downcallHandle} zur Laufzeit findet dann keinen passenden
 * Stub und wirft {@code MissingForeignRegistrationError} - genau das ist beim
 * ersten echten Lauf der {@code drivemount.exe} passiert, nach erfolgreichem
 * Login und auf beiden Shares. Der hier verwendete Deskriptor muss deshalb im
 * Abschnitt {@code foreign.downcalls} der handgepflegten
 * {@code reachability-metadata.json} stehen; aendert sich die Signatur unten,
 * muss die Datei mitgezogen werden. Die Bauausgabe nennt die Zahl der
 * registrierten Downcalls und ist die schnellste Kontrolle.</p>
 *
 * <p>TODO (Claude Code):</p>
 * <ul>
 * <li>CONNECT_TEMPORARY vs. persistente Verbindung konfigurierbar machen</li>
 * <li>{@code WNetCancelConnection2W} fuer eine "Trennen"-Funktion ergaenzen</li>
 * </ul>
 */
public class WindowsWNetMounter implements Mounter
{
  private static final Logger LOG
    = LoggerFactory.getLogger(WindowsWNetMounter.class);

  /**
   * Nichts vorzubereiten: Symbolsuche und Downcall-Handle entstehen pro
   * Aufruf in {@link #mount}, weil beides an der Arena haengt.
   */
  public WindowsWNetMounter()
  {
  }

  /** {@code dwType}: es geht um ein Laufwerk, nicht um einen Drucker. */
  private static final int RESOURCETYPE_DISK = 0x00000001;

  /**
   * {@code dwFlags}: Verbindung nur fuer diese Anmeldesitzung, nichts wird
   * im Profil gespeichert. Siehe Klassendoku - deshalb sieht eine andere
   * Sitzung die Zuordnung nicht.
   */
  private static final int CONNECT_TEMPORARY = 0x00000004;

  /** Groesse von NETRESOURCEW unter x64 in Byte (4x DWORD + 4x LPWSTR). */
  private static final int NETRESOURCEW_SIZE = 48;

  /** Ausrichtung der Struktur: die Zeigerfelder verlangen 8 Byte. */
  private static final int NETRESOURCEW_ALIGN = 8;

  /**
   * {@inheritDoc}
   *
   * <p>Der gesamte native Speicher haengt an einer
   * {@link Arena#ofConfined() confined Arena}: sie gehoert diesem einen
   * Thread und gibt beim Verlassen des try-Blocks alles wieder frei. Das
   * Passwort wird vorher noch von Hand ueberschrieben - Freigeben allein
   * loescht nichts, der Inhalt bliebe im Heap des Prozesses stehen, bis ihn
   * jemand anders ueberschreibt.</p>
   */
  @Override
  public MountResult mount(SmbShare share, String username, char[] password,
    String domain)
  {
    LOG.info("Mount '{}': {} -> {}", share.label(), share.uncPath(),
      share.mount() != null ? share.mount() + ":" : "ohne Laufwerksbuchstabe");

    try (Arena arena = Arena.ofConfined())
    {
      Linker linker = Linker.nativeLinker();

      // Mpr.dll ohne Pfad und ohne Endung: Windows sucht sie ueber die
      // uebliche DLL-Suchreihenfolge, sie ist Teil des Systems.
      SymbolLookup mpr = SymbolLookup.libraryLookup("Mpr", arena);

      // Signatur laut Win32-Doku:
      //   DWORD WNetAddConnection2W(LPNETRESOURCEW, LPCWSTR lpPassword,
      //                             LPCWSTR lpUserName, DWORD dwFlags)
      // Genau diese Form (int <- void*, void*, void*, int) steht im
      // foreign-Abschnitt der reachability-metadata.json.
      MethodHandle wNetAddConnection2W = linker.downcallHandle(
        mpr.find("WNetAddConnection2W").orElseThrow(
          () -> new IllegalStateException("WNetAddConnection2W not found")),
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,   // LPNETRESOURCEW
          ValueLayout.ADDRESS,   // LPCWSTR lpPassword
          ValueLayout.ADDRESS,   // LPCWSTR lpUserName
          ValueLayout.JAVA_INT));// DWORD dwFlags

      // --- NETRESOURCEW von Hand fuellen, Offsets siehe Klassendoku -------
      MemorySegment netResource
        = arena.allocate(NETRESOURCEW_SIZE, NETRESOURCEW_ALIGN);
      netResource.set(ValueLayout.JAVA_INT, 0, 0);                 // dwScope
      netResource.set(ValueLayout.JAVA_INT, 4, RESOURCETYPE_DISK); // dwType
      netResource.set(ValueLayout.JAVA_INT, 8, 0);                 // dwDisplayType
      netResource.set(ValueLayout.JAVA_INT, 12, 0);                // dwUsage

      // Ohne Laufwerksbuchstaben wird lpLocalName NULL - Windows verbindet
      // den Share dann ohne Zuordnung ("deviceless connection"). Genau das
      // passiert bei Shares, die aus dem optionalen Token-Claim kommen und
      // kein "mount" mitbringen.
      boolean hasDriveLetter = share.mount() != null
        && !share.mount().isBlank();
      MemorySegment localName = hasDriveLetter
        ? wstr(arena, share.mount().charAt(0) + ":")
        : MemorySegment.NULL;
      netResource.set(ValueLayout.ADDRESS, 16, localName);           // lpLocalName
      netResource.set(ValueLayout.ADDRESS, 24,
        wstr(arena, share.uncPath()));                               // lpRemoteName
      netResource.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);  // lpComment
      netResource.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);  // lpProvider

      // Anmeldename in der Form Domaene-Backslash-Benutzer. Die Domaene
      // stammt aus dem Mail-Claim des Tokens, nicht aus der Konfiguration,
      // ist also pro Benutzer richtig. Fehlt sie, geht der blanke
      // Benutzername raus und Windows nimmt seine eigene Vorgabe an.
      String user = (domain != null && !domain.isBlank())
        ? domain + "\\" + username
        : username;

      // new String(password) ist unvermeidbar, weil allocateFrom einen String
      // erwartet. Die Lebensdauer bleibt auf diesen Block beschraenkt; die
      // native Kopie wird unten ueberschrieben.
      MemorySegment pwSegment = wstr(arena, new String(password));

      // Geloggt werden Benutzer und Flags - niemals das Passwort.
      LOG.debug("WNetAddConnection2W: Benutzer '{}', Flags 0x{}",
        user, Integer.toHexString(CONNECT_TEMPORARY));

      int rc = (int) wNetAddConnection2W.invokeExact(
        netResource, pwSegment, wstr(arena, user), CONNECT_TEMPORARY);
      LOG.info("WNetAddConnection2W liefert {} fuer {}", rc, share.uncPath());

      // Passwort-Kopie im nativen Speicher ueberschreiben, bevor die
      // Arena freigegeben wird. Freigeben loescht nicht.
      pwSegment.fill((byte) 0);

      return switch (rc)
      {
        case 0 -> MountResult.ok(share,
          share.uncPath() + " verbunden"
          + (hasDriveLetter ? " (" + share.mount().charAt(0) + ":)" : ""));
        default -> MountResult.failed(share, errorMessage(rc, share));
      };
    }
    catch (Throwable t)
    {
      // Throwable statt Exception: invokeExact deklariert Throwable, und ein
      // fehlender FFM-Stub kommt als Error, nicht als Exception.
      //
      // Vollstaendig protokollieren: im Native Image steckt hier der
      // Unterschied zwischen einem echten Win32-Fehler und einem nicht
      // registrierten FFM-Downcall, und getMessage() allein sagt das nicht.
      LOG.error("FFM-Aufruf WNetAddConnection2W fehlgeschlagen fuer {}",
        share.uncPath(), t);
      // Nur die erste Zeile in die UI: GraalVMs
      // MissingForeignRegistrationError bringt einen mehrzeiligen Text mit,
      // der die Ergebnisliste sprengt. Vollstaendig steht er im Log.
      String message = t.getMessage() == null
        ? "" : t.getMessage().lines().findFirst().orElse("");
      return MountResult.failed(share, "FFM-Aufruf fehlgeschlagen: "
        + t.getClass().getSimpleName() + ": " + message);
    }
  }

  /**
   * Klartext zu den Win32-Fehlercodes, die {@code WNetAddConnection2W} in der
   * Praxis liefert.
   *
   * <p>Die nackte Zahl hilft niemandem weiter. Zwei Eintraege sind am realen
   * Fileserver <b>gemessen</b> und nicht aus der Doku abgeschrieben, weil die
   * Doku in beiden Faellen in die Irre fuehrt:</p>
   *
   * <ul>
   * <li><b>67</b> ({@code ERROR_BAD_NET_NAME}) liest sich wie ein Fehler der
   *     Anwendung, ist aber fast immer Namensaufloesung: der Servername steht
   *     in keinem DNS, das VPN ist unten, oder der Eintrag fehlt in der
   *     hosts-Datei. Deshalb nennt die Meldung den Hostnamen und verweist
   *     ausdruecklich auf DNS und VPN.</li>
   * <li><b>86</b> ({@code ERROR_INVALID_PASSWORD}) kommt auch dann, wenn es
   *     den <i>Benutzernamen</i> gar nicht gibt - nicht 1326, wie man
   *     erwarten wuerde. Die Meldung nennt deshalb alle drei Moeglichkeiten
   *     (Benutzer, Domaene, Passwort) und zeigt nicht auf das Passwort
   *     allein.</li>
   * </ul>
   *
   * <p>Paketsichtbar und nicht privat, damit {@code WindowsWNetMounterTest}
   * die Tabelle auch auf anderen Plattformen pruefen kann - es ist reine
   * Stringlogik ohne nativen Anteil. Reflexion im Test waere die Alternative
   * gewesen, die beim Umbenennen still zerbricht statt den Compiler zu
   * stoeren.</p>
   *
   * @param rc    Rueckgabewert von {@code WNetAddConnection2W}, ungleich 0
   * @param share der betroffene Share, liefert UNC-Pfad und Laufwerk fuer die
   *              Meldung
   * @return deutscher Klartext mit dem Code in Klammern
   */
  static String errorMessage(int rc, SmbShare share)
  {
    String host = serverName(share.uncPath());
    return switch (rc)
    {
      case 5 -> "Zugriff verweigert (5)";
      case 53 -> "Netzwerkpfad nicht gefunden (53): " + share.uncPath()
        + " - Server erreichbar, aber die Freigabe gibt es dort nicht";
      case 67 -> "Netzwerkname nicht gefunden (67): '" + host
        + "' laesst sich nicht aufloesen oder hat keine solche Freigabe"
        + " - DNS bzw. VPN pruefen";
      case 85 -> "Laufwerksbuchstabe bereits belegt (85)";
      case 86 -> "Anmeldedaten abgelehnt (86): Benutzername, Domaene oder Passwort falsch";
      case 1200 -> "Ungueltiger Geraetename (1200): "
        + (share.mount() != null ? share.mount() : "?");
      case 1202 -> "Laufwerksbuchstabe ist bereits dauerhaft zugeordnet (1202)";
      case 1219 -> "Credential-Konflikt (1219): bestehende Verbindung zu '"
        + host + "' mit anderen Anmeldedaten - erst trennen"
        + " (net use * /delete)";
      case 1326 -> "Anmeldung am Fileserver fehlgeschlagen (1326):"
        + " Benutzername, Domaene oder Passwort falsch";
      case 1330 -> "Passwort abgelaufen (1330)";
      case 2250 -> "Keine Netzwerkverbindung (2250)";
      default -> "WNet-Fehlercode " + rc;
    };
  }

  /**
   * Schneidet den Servernamen aus einem UNC-Pfad heraus, also den Teil
   * zwischen den fuehrenden Backslashes und dem naechsten Backslash.
   *
   * <p>Wird nur fuer Fehlermeldungen gebraucht: bei 67 und 1219 ist der
   * Rechnername die eigentliche Information.</p>
   *
   * @param uncPath UNC-Pfad, mit oder ohne fuehrende Backslashes
   * @return der Servername, oder der unveraenderte Rest, wenn kein weiterer
   *         Trenner folgt
   */
  private static String serverName(String uncPath)
  {
    String s = uncPath.startsWith("\\\\") ? uncPath.substring(2) : uncPath;
    int slash = s.indexOf('\\');
    return slash > 0 ? s.substring(0, slash) : s;
  }

  /**
   * Legt einen null-terminierten UTF-16LE-String ({@code LPCWSTR}) in der
   * Arena ab.
   *
   * <p>Einzige Stelle, an der Zeichenketten fuer die API entstehen - die
   * Kodierung ist nicht verhandelbar, die W-Variante der Win32-Funktionen
   * erwartet UTF-16LE. {@code allocateFrom} haengt die Null-Terminierung
   * selbst an.</p>
   *
   * @param arena Arena, die den Speicher besitzt
   * @param value Zeichenkette, auch leer erlaubt
   * @return Segment mit dem kodierten String
   */
  private static MemorySegment wstr(Arena arena, String value)
  {
    return arena.allocateFrom(value, StandardCharsets.UTF_16LE);
  }
}
