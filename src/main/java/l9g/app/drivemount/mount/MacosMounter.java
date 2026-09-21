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
 * macOS-Mount ueber {@code NetFSMountURLSync} aus dem NetFS.framework,
 * aufgerufen per Java-FFM (Panama).
 *
 * <p>Die Anmeldedaten gehen als CFString direkt an die API - kein
 * Kindprozess, kein AppleScript, keine Kommandozeile. Das ist der Grund fuer
 * den Aufwand in dieser Klasse: jeder andere Weg (etwa {@code osascript} mit
 * {@code mount volume}) wuerde das Passwort durch fremde Haende reichen.</p>
 *
 * <h2>Die aufgerufene Funktion</h2>
 *
 * <pre>
 *   int NetFSMountURLSync(CFURLRef              url,
 *                         CFURLRef              mountpath,
 *                         CFStringRef           user,
 *                         CFStringRef           passwd,
 *                         CFMutableDictionaryRef open_options,
 *                         CFMutableDictionaryRef mount_options,
 *                         CFArrayRef           *mountpoints);
 * </pre>
 *
 * <p>{@code mountpath} bleibt NULL, dann waehlt macOS selbst - in der Praxis
 * {@code /Volumes/&lt;Freigabe&gt;}. Der tatsaechliche Pfad kommt ueber den
 * Ausgabeparameter {@code mountpoints} zurueck und wandert in die
 * Ergebnismeldung, damit der Benutzer sieht, wo der Share gelandet ist.</p>
 *
 * <p>Der Rueckgabewert ist dreigeteilt, siehe NetFS.h: <b>0</b> ist Erfolg,
 * <b>positive</b> Werte sind {@code errno}, <b>negative</b> sind
 * {@code OSStatus}. {@link #errorMessage(int, SmbShare)} deckt beide
 * Bereiche ab.</p>
 *
 * <h2>Warum System.load und nicht libraryLookup</h2>
 *
 * <p>Zwei Anlaeufe waren noetig, beide sind hier festgehalten, damit es
 * niemand erneut probiert. {@code SymbolLookup.libraryLookup(Path)} kann ein
 * macOS-Framework <b>nicht</b> laden: seit macOS 11 liegen die Binaries nur
 * noch im dyld-Shared-Cache, auf der Platte steht ein Symlink ins Leere.
 * {@code libraryLookup} ruft intern {@code toRealPath()} auf und scheitert
 * mit "Cannot open library". Was funktioniert - auf der JVM <i>und</i> im
 * Native Image - ist {@link System#load(String)} mit dem absoluten
 * Framework-Pfad, gefolgt von {@link SymbolLookup#loaderLookup()}.
 * {@code dlopen}/{@code dlsym} ueber {@code defaultLookup()} waere der
 * Rueckfallweg, sollte das je aufhoeren zu funktionieren.</p>
 *
 * <h2>Speicherverwaltung von CoreFoundation</h2>
 *
 * <p>CoreFoundation kennt zwei Regeln, und sie auseinanderzuhalten ist hier
 * der ganze Unterschied zwischen sauberem Lauf und Absturz:</p>
 *
 * <ul>
 * <li><b>Create-Regel</b> - wer ein Objekt aus einer Funktion mit
 *     {@code Create} im Namen bekommt, besitzt es und muss
 *     {@code CFRelease} aufrufen. Das betrifft hier URL, die beiden
 *     CFStrings, das Optionen-Dictionary und das zurueckgegebene
 *     mountpoints-Array.</li>
 * <li><b>Get-Regel</b> - wer ein Objekt aus einer Funktion mit {@code Get}
 *     im Namen bekommt, besitzt es <b>nicht</b> und darf es auf keinen Fall
 *     freigeben. Das betrifft das Element aus
 *     {@code CFArrayGetValueAtIndex} in
 *     {@link Natives#firstMountpoint(Arena, MemorySegment)}.</li>
 * </ul>
 *
 * <p>Als Allocator wird ueberall NULL uebergeben - das <i>ist</i>
 * {@code kCFAllocatorDefault}, ein eigenes Symbol braucht es dafuer
 * nicht.</p>
 *
 * <h2>Native Image</h2>
 *
 * <p>Die Symbole werden im <b>Konstruktor</b> aufgeloest, nicht in einem
 * statischen Initialisierer. Native Image darf statische Initialisierung zur
 * Bauzeit ausfuehren; dann landeten die Adressen des <i>Baurechners</i> im
 * Binary. Ein Fehlschlag dabei ist nicht toedlich: die Anwendung startet, und
 * jeder Mount meldet einzeln, warum er nicht laufen kann.</p>
 *
 * <p>Alle neun Downcalls unten muessen im Abschnitt
 * {@code foreign.downcalls} der handgepflegten
 * {@code reachability-metadata.json} stehen, sonst endet der erste Aufruf im
 * fertigen Binary mit {@code MissingForeignRegistrationError}. Achtung bei
 * den Typnamen in dieser Datei: {@code long} ist plattformabhaengig (8 Byte
 * auf macOS, 4 auf Windows), weshalb {@code CFIndex} dort als {@code size_t}
 * geschrieben ist - eine gemeinsame Metadatendatei fuer beide Plattformen
 * verlangt das.</p>
 *
 * <p>TODO (Claude Code):</p>
 * <ul>
 * <li>Kein eigenes Zeitlimit: {@code NetFSMountURLSync} blockiert, bis das
 *     System aufgibt. Der Aufruf laeuft im auth-mount-Thread, die Oberflaeche
 *     bleibt also bedienbar. Ein Abbruch braeuchte
 *     {@code NetFSMountURLAsync} samt Upcall.</li>
 * <li>{@code NetFSUnmount} fuer eine Trennen-Funktion ergaenzen</li>
 * </ul>
 */
public class MacosMounter implements Mounter
{
  private static final Logger LOG
    = LoggerFactory.getLogger(MacosMounter.class);

  /**
   * Die Framework-Binaries liegen seit macOS 11 nur im dyld-Shared-Cache; auf
   * der Platte steht ein Symlink ins Leere. SymbolLookup.libraryLookup(Path)
   * scheitert daran, weil es intern toRealPath() aufruft
   * ("Cannot open library"). System.load geht ueber dlopen und findet die
   * Bibliothek im Cache, danach loest loaderLookup() die Symbole auf.
   */
  private static final String FRAMEWORK_CORE_FOUNDATION
    = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

  /** Zweites Framework, liefert NetFSMountURLSync. Siehe oben. */
  private static final String FRAMEWORK_NETFS
    = "/System/Library/Frameworks/NetFS.framework/NetFS";

  /**
   * {@code kCFStringEncodingUTF8} aus CFString.h. Die Konstante ist ein
   * Aufzaehlungswert und kein exportiertes Symbol, steht also hier.
   */
  private static final int CF_STRING_ENCODING_UTF8 = 0x08000100;

  /** Puffer fuer den zurueckgemeldeten Mountpfad (POSIX-Pfad). */
  private static final int MOUNTPOINT_BUFFER = 1024;

  /** Aufgeloeste Symbole, oder {@code null}, wenn das fehlgeschlagen ist. */
  private final Natives natives;

  /** Grund des Fehlschlags fuer die Meldung an den Benutzer. */
  private final String initError;

  /**
   * Loest NetFS und CoreFoundation auf.
   *
   * <p>Bewusst hier und nicht in einem statischen Initialisierer - die
   * Begruendung steht in der Klassendoku unter "Native Image". Ein Fehler
   * wird protokolliert und gemerkt, aber nicht weitergereicht: eine
   * Anwendung, die gar nicht erst startet, hilft niemandem, und
   * {@link #mount} kann den Grund viel besser an der richtigen Stelle
   * anzeigen.</p>
   */
  public MacosMounter()
  {
    Natives resolved = null;
    String error = null;
    try
    {
      resolved = Natives.resolve();
      LOG.info("NetFS und CoreFoundation aufgeloest");
    }
    catch (Throwable t)
    {
      error = t.getClass().getSimpleName()
        + (t.getMessage() == null ? "" : ": " + t.getMessage());
      LOG.error("NetFS/CoreFoundation nicht aufloesbar -"
        + " Mounts sind nicht moeglich", t);
    }
    this.natives = resolved;
    this.initError = error;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Der Ablauf ist von der Speicherverwaltung diktiert: alle CF-Objekte
   * werden vor dem {@code try} deklariert und im {@code finally} freigegeben,
   * damit ein Fehler in der Mitte keine Referenz stehen laesst. Das Passwort
   * wird dort ebenfalls ueberschrieben - und zwar an der <i>Quelle</i>, dem
   * UTF-16-Puffer, denn der CFString haelt eine eigene Kopie, an die man
   * nicht herankommt.</p>
   */
  @Override
  public MountResult mount(SmbShare share, String username, char[] password,
    String domain)
  {
    if (natives == null)
    {
      return MountResult.failed(share,
        "NetFS nicht verfuegbar (" + initError + ")");
    }

    // AD-Domaene gehoert in den Benutzernamen (DOMAIN-Backslash-user);
    // ohne sie weist der Fileserver die Anmeldung zurueck.
    String user = (domain != null && !domain.isBlank())
      ? domain + "\\" + username
      : username;

    LOG.info("Mount '{}': {}", share.label(), share.url());

    try (Arena arena = Arena.ofConfined())
    {
      // Quelle des Passworts im nativen Speicher. Aus ihr entsteht gleich
      // ein CFString mit eigener Kopie - ueberschrieben wird trotzdem hier,
      // siehe finally.
      MemorySegment passwordSegment = utf16(arena, password);

      // Alle CF-Referenzen vorab auf NULL: das finally laeuft auch dann,
      // wenn schon die erste Erzeugung scheitert, und release() vertraegt
      // NULL.
      MemorySegment url = MemorySegment.NULL;
      MemorySegment userString = MemorySegment.NULL;
      MemorySegment passwordString = MemorySegment.NULL;
      MemorySegment openOptions = MemorySegment.NULL;
      MemorySegment mountpoints = MemorySegment.NULL;

      try
      {
        url = natives.urlFrom(arena, share.url());
        userString = natives.stringFrom(arena, user.toCharArray());
        passwordString = natives.string(passwordSegment, password.length);
        openOptions = natives.noUiOptions(arena);

        // Ausgabeparameter: ein Zeiger auf einen Zeiger. Die Arena haelt den
        // Platz, NetFS traegt dort die Adresse des Arrays ein.
        MemorySegment mountpointsOut = arena.allocate(ValueLayout.ADDRESS);

        // Benutzer ja, Passwort nie - auch nicht auf DEBUG.
        LOG.debug("NetFSMountURLSync: Benutzer '{}', ohne Oberflaeche", user);
        int rc = natives.mount(url, userString, passwordString, openOptions,
          mountpointsOut);
        LOG.info("NetFSMountURLSync liefert {} fuer {}", rc, share.url());

        if (rc != 0)
        {
          return MountResult.failed(share, errorMessage(rc, share));
        }

        // Ab hier gehoert uns das Array (Create-Regel): es wird unten
        // freigegeben. Das einzelne Element darin nicht - das folgt der
        // Get-Regel, siehe firstMountpoint.
        mountpoints = mountpointsOut.get(ValueLayout.ADDRESS, 0);
        String path = natives.firstMountpoint(arena, mountpoints);
        LOG.info("Mountpfad: {}", path);
        return MountResult.ok(share, path == null
          ? share.url() + " verbunden"
          : share.url() + " verbunden unter " + path);
      }
      finally
      {
        // Erst das Passwort im nativen Speicher ueberschreiben, dann die
        // CF-Objekte freigeben. CFRelease loescht den Inhalt nicht, die Kopie
        // im CFString laesst sich nicht selbst ausnullen - die Quelle schon.
        passwordSegment.fill((byte) 0);
        natives.release(mountpoints);
        natives.release(openOptions);
        natives.release(passwordString);
        natives.release(userString);
        natives.release(url);
      }
    }
    catch (Throwable t)
    {
      // Throwable, weil invokeExact es deklariert und ein fehlender
      // FFM-Stub als Error kommt, nicht als Exception.
      LOG.error("NetFSMountURLSync fehlgeschlagen fuer {}", share.url(), t);
      // Nur die erste Zeile in die Oberflaeche - GraalVMs
      // MissingForeignRegistrationError ist mehrzeilig und sprengt die
      // Ergebnisliste. Vollstaendig steht er im Log.
      String message = t.getMessage() == null
        ? "" : t.getMessage().lines().findFirst().orElse("");
      return MountResult.failed(share, "FFM-Aufruf fehlgeschlagen: "
        + t.getClass().getSimpleName() + ": " + message);
    }
  }

  /**
   * Klartext zu den Rueckgabewerten von {@code NetFSMountURLSync}.
   *
   * <p>Positive Werte sind {@code errno}, negative {@code OSStatus} - die
   * nackte Zahl hilft niemandem weiter. Die Tabelle deckt ab, was in der
   * Praxis vorkommt, nicht den vollstaendigen Wertebereich; alles Uebrige
   * faellt auf den Code selbst zurueck.</p>
   *
   * <p>Der wichtigste Eintrag ist <b>80 ({@code EAUTH})</b>: das ist die
   * Antwort auf falsche Anmeldedaten und im Negativtest gemessen - nach
   * 629 ms, also in derselben Groessenordnung wie ein erfolgreicher Mount.
   * Diese Zeit ist der Beleg dafuer, dass <i>kein</i> Systemdialog
   * aufgegangen ist; bei einem Dialog haette der Aufruf auf eine Eingabe
   * gewartet. Getestet wird so etwas mit einem <b>nicht existierenden</b>
   * Benutzernamen - wiederholte Fehlversuche gegen ein echtes AD-Konto
   * koennen eine Sperre ausloesen.</p>
   *
   * <p>Paketsichtbar und nicht privat, damit {@code MacosMounterTest} die
   * Tabelle auch ohne macOS pruefen kann - es ist reine Stringlogik.</p>
   *
   * @param rc    Rueckgabewert von {@code NetFSMountURLSync}, ungleich 0
   * @param share der betroffene Share, liefert die URL fuer die Meldung
   * @return deutscher Klartext
   */
  static String errorMessage(int rc, SmbShare share)
  {
    return switch (rc)
    {
      // errno
      case 1 -> "Keine Berechtigung (EPERM)";
      case 2 -> "Server oder Freigabe nicht gefunden (ENOENT): " + share.url();
      case 13 -> "Zugriff verweigert (EACCES)";
      case 16 -> "Ressource belegt (EBUSY) - vermutlich bereits verbunden";
      case 17 -> share.url() + " ist bereits verbunden (EEXIST)";
      case 60 -> "Zeitueberschreitung beim Verbinden (ETIMEDOUT)";
      case 64 -> "Server nicht erreichbar (EHOSTDOWN)";
      case 65 -> "Keine Route zum Server (EHOSTUNREACH) - DNS bzw. VPN pruefen";
      case 80 -> "Anmeldung am Fileserver fehlgeschlagen (EAUTH)";
      // OSStatus
      case -128 -> "Vom Benutzer abgebrochen";
      case -5045 -> "Passwort muss geaendert werden";
      case -5046 -> "Passwort entspricht nicht der Richtlinie";
      case -5996 -> "Keine unterstuetzte Protokollversion";
      case -5997 -> "Kein unterstuetzter Authentifizierungsmechanismus";
      case -5998 -> "Keine Freigaben verfuegbar";
      case -5999 -> "Konto gesperrt oder eingeschraenkt";
      case -6003 -> "Keine Freigaben verfuegbar";
      case -6004 -> "Gastzugang nicht unterstuetzt";
      case -6005 -> "Verbindung bereits geschlossen";
      case -6600 -> "Interner Fehler der Netzwerkanmeldung";
      case -6602 -> "Mount fehlgeschlagen";
      default -> "NetFS-Fehlercode " + rc;
    };
  }

  /**
   * Legt einen null-terminierten UTF-16-Puffer ({@code UniChar*}) in der
   * Arena ab.
   *
   * <p>{@code CFStringCreateWithCharacters} erwartet genau dieses Format.
   * Der Puffer ist ein {@code char} laenger als die Eingabe; frisch
   * allokierter Speicher ist genullt, die Terminierung steht also schon
   * da.</p>
   *
   * <p>Diese Methode ist der Grund, warum das Passwort nie durch einen
   * {@link String} muss: {@code char[]} geht direkt hier hinein und laesst
   * sich anschliessend ueberschreiben.</p>
   *
   * @param arena Arena, die den Speicher besitzt
   * @param value Zeichen, die kopiert werden
   * @return Segment mit den Zeichen und abschliessender Null
   */
  private static MemorySegment utf16(Arena arena, char[] value)
  {
    MemorySegment segment = arena.allocate(
      (long) value.length * Character.BYTES + Character.BYTES);
    for (int i = 0; i < value.length; i++)
    {
      segment.set(ValueLayout.JAVA_CHAR, (long) i * Character.BYTES, value[i]);
    }
    return segment;
  }

  /**
   * Die aufgeloesten Symbole von NetFS und CoreFoundation.
   *
   * <p>Bewusst im Konstruktor von {@link MacosMounter} erzeugt und nicht in
   * einem statischen Initialisierer: Native Image duerfte eine statische
   * Initialisierung zur Bauzeit ausfuehren, dann landeten die Adressen des
   * Baurechners im Binary.</p>
   *
   * <p>Die neun {@link MethodHandle} entsprechen eins zu eins den neun
   * Eintraegen im {@code foreign}-Abschnitt der Reachability-Metadaten. Wer
   * hier eine Signatur aendert oder einen Aufruf hinzufuegt, muss die
   * JSON-Datei mitziehen - sonst baut das Image durch und faellt erst beim
   * Aufruf um.</p>
   */
  private static final class Natives
  {
    private final MethodHandle netFSMountURLSync;

    private final MethodHandle cfURLCreateWithBytes;

    private final MethodHandle cfStringCreateWithCharacters;

    private final MethodHandle cfDictionaryCreateMutable;

    private final MethodHandle cfDictionarySetValue;

    private final MethodHandle cfArrayGetCount;

    private final MethodHandle cfArrayGetValueAtIndex;

    private final MethodHandle cfStringGetCString;

    private final MethodHandle cfRelease;

    /** Datensymbol {@code kCFTypeDictionaryKeyCallBacks}, siehe unten. */
    private final MemorySegment keyCallBacks;

    /** Datensymbol {@code kCFTypeDictionaryValueCallBacks}, siehe unten. */
    private final MemorySegment valueCallBacks;

    /**
     * Baut alle Downcall-Handles auf.
     *
     * @param lookup Symbolquelle, hier immer
     *               {@link SymbolLookup#loaderLookup()} nach zwei
     *               {@link System#load(String)}
     */
    private Natives(SymbolLookup lookup)
    {
      Linker linker = Linker.nativeLinker();

      netFSMountURLSync = linker.downcallHandle(find(lookup,
        "NetFSMountURLSync"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,   // CFURLRef url
          ValueLayout.ADDRESS,   // CFURLRef mountpath
          ValueLayout.ADDRESS,   // CFStringRef user
          ValueLayout.ADDRESS,   // CFStringRef passwd
          ValueLayout.ADDRESS,   // CFMutableDictionaryRef open_options
          ValueLayout.ADDRESS,   // CFMutableDictionaryRef mount_options
          ValueLayout.ADDRESS)); // CFArrayRef *mountpoints

      // CFURLRef CFURLCreateWithBytes(CFAllocatorRef, const UInt8 *,
      //                               CFIndex, CFStringEncoding, CFURLRef)
      // CFIndex ist ein long - in den Metadaten als size_t geschrieben,
      // weil "long" dort plattformabhaengig waere.
      cfURLCreateWithBytes = linker.downcallHandle(find(lookup,
        "CFURLCreateWithBytes"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

      // CFStringRef CFStringCreateWithCharacters(CFAllocatorRef,
      //                                          const UniChar *, CFIndex)
      cfStringCreateWithCharacters = linker.downcallHandle(find(lookup,
        "CFStringCreateWithCharacters"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

      // CFMutableDictionaryRef CFDictionaryCreateMutable(CFAllocatorRef,
      //     CFIndex capacity, const CFDictionaryKeyCallBacks *,
      //     const CFDictionaryValueCallBacks *)
      cfDictionaryCreateMutable = linker.downcallHandle(find(lookup,
        "CFDictionaryCreateMutable"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS));

      cfDictionarySetValue = linker.downcallHandle(find(lookup,
        "CFDictionarySetValue"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS));

      cfArrayGetCount = linker.downcallHandle(find(lookup, "CFArrayGetCount"),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

      cfArrayGetValueAtIndex = linker.downcallHandle(find(lookup,
        "CFArrayGetValueAtIndex"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.JAVA_LONG));

      // Rueckgabe ist ein Boolean, in CoreFoundation ein signed char.
      cfStringGetCString = linker.downcallHandle(find(lookup,
        "CFStringGetCString"),
        FunctionDescriptor.of(ValueLayout.JAVA_BYTE,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_INT));

      cfRelease = linker.downcallHandle(find(lookup, "CFRelease"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

      // Datensymbole: die Adresse ist die Struktur selbst, nicht ein Zeiger
      // darauf. Ein Allocator wird nicht gebraucht - NULL bedeutet in
      // CoreFoundation ueberall kCFAllocatorDefault.
      keyCallBacks = find(lookup, "kCFTypeDictionaryKeyCallBacks");
      valueCallBacks = find(lookup, "kCFTypeDictionaryValueCallBacks");
    }

    /**
     * Laedt beide Frameworks und liefert die aufgeloesten Symbole.
     *
     * <p>Die Reihenfolge ist nicht beliebig: CoreFoundation zuerst, NetFS
     * baut darauf auf. Warum {@link System#load(String)} und nicht
     * {@code libraryLookup}, steht in der Klassendoku.</p>
     *
     * @return neue, vollstaendig aufgeloeste Instanz
     */
    private static Natives resolve()
    {
      System.load(FRAMEWORK_CORE_FOUNDATION);
      System.load(FRAMEWORK_NETFS);
      return new Natives(SymbolLookup.loaderLookup());
    }

    /**
     * Sucht ein Symbol und scheitert laut, wenn es fehlt.
     *
     * @param lookup Symbolquelle
     * @param symbol Name des gesuchten Symbols
     * @return Adresse des Symbols
     * @throws IllegalStateException wenn das Symbol nicht existiert - der
     *                               Konstruktor faengt das und meldet es als
     *                               {@code initError}
     */
    private static MemorySegment find(SymbolLookup lookup, String symbol)
    {
      return lookup.find(symbol).orElseThrow(
        () -> new IllegalStateException("Symbol nicht gefunden: " + symbol));
    }

    /**
     * Ruft {@code NetFSMountURLSync} auf.
     *
     * <p>{@code mountpath} und {@code mount_options} bleiben NULL: der
     * Mountpunkt wird von macOS gewaehlt, Sonderoptionen gibt es keine.</p>
     *
     * @param url            CFURLRef der Freigabe
     * @param user           CFStringRef mit Domaene und Benutzername
     * @param password       CFStringRef mit dem Passwort
     * @param openOptions    Dictionary mit {@code UIOption=NoUI}
     * @param mountpointsOut Zeiger auf einen Zeiger, nimmt das Ergebnisarray
     *                       auf
     * @return 0 bei Erfolg, sonst errno (positiv) oder OSStatus (negativ)
     * @throws Throwable was der Downcall durchreicht
     */
    private int mount(MemorySegment url, MemorySegment user,
      MemorySegment password, MemorySegment openOptions,
      MemorySegment mountpointsOut) throws Throwable
    {
      return (int) netFSMountURLSync.invokeExact(url,
        MemorySegment.NULL,   // mountpath: NULL -> /Volumes/<Share>
        user, password, openOptions,
        MemorySegment.NULL,   // mount_options
        mountpointsOut);
    }

    /**
     * Erzeugt eine CFURLRef aus einer {@code smb://}-URL.
     *
     * <p>Create-Regel: der Aufrufer muss das Ergebnis freigeben.</p>
     *
     * @param arena Arena fuer den Zwischenpuffer
     * @param value URL als Text
     * @return neue CFURLRef
     * @throws Throwable was der Downcall durchreicht
     */
    private MemorySegment urlFrom(Arena arena, String value) throws Throwable
    {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      MemorySegment buffer = arena.allocate(bytes.length);
      MemorySegment.copy(bytes, 0, buffer, ValueLayout.JAVA_BYTE, 0,
        bytes.length);
      return (MemorySegment) cfURLCreateWithBytes.invokeExact(
        MemorySegment.NULL, buffer, (long) bytes.length,
        CF_STRING_ENCODING_UTF8, MemorySegment.NULL);
    }

    /**
     * Erzeugt eine CFStringRef aus Zeichen.
     *
     * <p>Create-Regel: der Aufrufer muss das Ergebnis freigeben.</p>
     *
     * @param arena Arena fuer den UTF-16-Puffer
     * @param value die Zeichen
     * @return neue CFStringRef
     * @throws Throwable was der Downcall durchreicht
     */
    private MemorySegment stringFrom(Arena arena, char[] value)
      throws Throwable
    {
      return string(utf16(arena, value), value.length);
    }

    /**
     * Erzeugt eine CFStringRef aus einem bereits vorhandenen UTF-16-Puffer.
     *
     * <p>Diese Variante gibt es wegen des Passworts: dessen Puffer soll
     * anschliessend ueberschrieben werden koennen, er darf also nicht
     * innerhalb dieser Methode entstehen.</p>
     *
     * @param chars  Zeiger auf {@code UniChar*}
     * @param length Anzahl der Zeichen, ohne Terminierung
     * @return neue CFStringRef
     * @throws Throwable was der Downcall durchreicht
     */
    private MemorySegment string(MemorySegment chars, int length)
      throws Throwable
    {
      return (MemorySegment) cfStringCreateWithCharacters.invokeExact(
        MemorySegment.NULL, chars, (long) length);
    }

    /**
     * Baut das {@code open_options}-Dictionary mit {@code UIOption=NoUI}.
     *
     * <p>Die Schluessel sind in NetFS.h {@code CFSTR}-Makros, also keine
     * exportierten Symbole - die CFStrings muessen selbst erzeugt werden.
     * Ohne diese Option zieht macOS bei einer fehlgeschlagenen Anmeldung
     * einen eigenen Dialog hoch, der hinter dem Login-Fenster haengt.</p>
     *
     * <p>Schluessel und Wert werden sofort wieder freigegeben: das
     * Dictionary haelt eigene Referenzen darauf, sobald sie gesetzt sind
     * (das leisten die {@code kCFTypeDictionary*CallBacks}).</p>
     *
     * @param arena Arena fuer die Zwischenpuffer
     * @return neues Dictionary, das der Aufrufer freigeben muss
     * @throws Throwable was die Downcalls durchreichen
     */
    private MemorySegment noUiOptions(Arena arena) throws Throwable
    {
      MemorySegment options = (MemorySegment) cfDictionaryCreateMutable
        .invokeExact(MemorySegment.NULL, 0L, keyCallBacks, valueCallBacks);

      MemorySegment key = stringFrom(arena, "UIOption".toCharArray());
      MemorySegment value = stringFrom(arena, "NoUI".toCharArray());
      try
      {
        cfDictionarySetValue.invokeExact(options, key, value);
      }
      finally
      {
        release(key);
        release(value);
      }
      return options;
    }

    /**
     * Liest den ersten POSIX-Pfad aus dem {@code mountpoints}-Array.
     *
     * <p>Das Array enthaelt in der Praxis genau einen Eintrag, etwa
     * {@code /Volumes/home}. Er dient nur der Anzeige - fuer das Ergebnis
     * des Mounts ist allein der Rueckgabecode massgeblich, ein fehlender
     * Pfad ist deshalb kein Fehler.</p>
     *
     * <p><b>Get-Regel:</b> das Element aus
     * {@code CFArrayGetValueAtIndex} gehoert uns nicht und wird nicht
     * freigegeben. Nur das Array selbst wird vom Aufrufer freigegeben.</p>
     *
     * @param arena Arena fuer den Zielpuffer
     * @param array das Array, darf NULL sein
     * @return der Pfad, oder {@code null} wenn es keinen gibt
     * @throws Throwable was die Downcalls durchreichen
     */
    private String firstMountpoint(Arena arena, MemorySegment array)
      throws Throwable
    {
      // MemorySegment.NULL.equals(...) traegt hier nicht - verglichen wird
      // die Adresse.
      if (array == null || array.address() == 0L)
      {
        return null;
      }
      long count = (long) cfArrayGetCount.invokeExact(array);
      if (count <= 0)
      {
        return null;
      }
      MemorySegment element
        = (MemorySegment) cfArrayGetValueAtIndex.invokeExact(array, 0L);
      MemorySegment buffer = arena.allocate(MOUNTPOINT_BUFFER);
      byte ok = (byte) cfStringGetCString.invokeExact(element, buffer,
        (long) MOUNTPOINT_BUFFER, CF_STRING_ENCODING_UTF8);
      return ok == 0 ? null : buffer.getString(0);
    }

    /**
     * Gibt eine CF-Referenz frei, wenn es etwas freizugeben gibt.
     *
     * <p>Nimmt NULL entgegen, weil die Aufrufstelle im {@code finally} nicht
     * wissen kann, wie weit sie vorher gekommen ist. Ein {@code CFRelease}
     * auf NULL waere ein Absturz.</p>
     *
     * @param reference die Referenz, darf {@code null} oder NULL sein
     */
    private void release(MemorySegment reference)
    {
      if (reference == null || reference.address() == 0L)
      {
        return;
      }
      try
      {
        cfRelease.invokeExact(reference);
      }
      catch (Throwable t)
      {
        // Im finally: ein Fehler beim Freigeben darf die eigentliche
        // Ausnahme nicht verdecken.
        LOG.warn("CFRelease fehlgeschlagen", t);
      }
    }
  }
}
