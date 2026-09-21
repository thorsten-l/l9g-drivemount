/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import l9g.app.drivemount.config.DrivemountProperties;
import l9g.app.drivemount.model.AuthResult;
import l9g.app.drivemount.model.SmbShare;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Authentifiziert per Keycloak Direct Grant ({@code grant_type=password})
 * mit Passwort und Einmalkennwort (TOTP).
 *
 * <p>Voraussetzung auf der Serverseite ist ein <b>duplizierter</b>
 * Direct-Grant-Flow mit {@code direct-grant-validate-otp} auf
 * <i>Required</i>, der ueber <i>Advanced -&gt; Authentication flow
 * overrides</i> an den Client gebunden wird. Der Realm-Default bleibt dabei
 * unberuehrt - sonst braeuchte jeder andere Client des Realms ebenfalls ein
 * Einmalkennwort. Der komplette Aufbau steht im README.</p>
 *
 * <h2>Warum keine Signaturpruefung</h2>
 *
 * <p>Das Access Token wird nur dekodiert, nicht verifiziert - und das ist
 * Absicht, kein vergessener Schritt. Eine Signaturpruefung schuetzt einen
 * <i>Resource Server</i> davor, ein untergeschobenes Token zu akzeptieren.
 * Hier ist die Anwendung aber der <b>OAuth-Client</b> selbst: sie hat das
 * Token soeben ueber TLS direkt vom Token-Endpunkt entgegengenommen. Wer an
 * dieser Stelle etwas faelschen koennte, haette bereits die TLS-Verbindung
 * gebrochen - dann hilft auch die Signatur nicht mehr.</p>
 *
 * <p>Das Token wird ausserdem nicht aufbewahrt. Aus dem Payload wird nur
 * die Domaene gelesen, danach ist es unbrauchbar und wird verworfen; im
 * {@link AuthResult} steht es nicht.</p>
 *
 * <h2>Was geloggt wird</h2>
 *
 * <p>Token-Endpunkt, Client-ID, Benutzername, HTTP-Status, die abgeleitete
 * Domaene und die Share-Bezeichnungen. <b>Nie</b> Passwort, Einmalkennwort,
 * Access Token oder Client-Secret. Aus demselben Grund darf das Log-Level
 * nicht global auf DEBUG stehen - Spring gaebe dann gebundene
 * Konfigurationswerte aus, darunter das entschluesselte Secret.</p>
 */
@Service
public class KeycloakAuthService
{
  private static final Logger LOG
    = LoggerFactory.getLogger(KeycloakAuthService.class);

  private final DrivemountProperties props;

  private final ObjectMapper mapper = new ObjectMapper();

  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();

  /**
   * Konstruktorinjektion durch Spring.
   *
   * @param props gebundene Konfiguration; liefert Endpunkt, Client-Daten,
   *              den Namen des Mail-Claims und die Shares je Domaene
   */
  public KeycloakAuthService(DrivemountProperties props)
  {
    this.props = props;
    // Einmal beim Start: welche Domaenen kennt diese Konfiguration? Steht
    // eine Domaene hier nicht drin, bekommt der Benutzer spaeter nichts
    // verbunden - dann ist das die erste Zeile, die man sehen will. Nur
    // Namen und Anzahl, keine Zugangsdaten.
    LOG.info("Konfigurierte Domaenen: {}",
      props.shares().entrySet().stream()
        .map(e -> e.getKey() + " (" + e.getValue().size() + ")")
        .toList());
  }

  /**
   * Meldet einen Benutzer an und leitet aus dem Token ab, was fuer den Mount
   * gebraucht wird.
   *
   * <p>Blockiert bis zur Antwort (Verbindungsaufbau 10 s, Anfrage 15 s) und
   * wird deshalb vom {@code LoginController} aus einem Hintergrund-Task
   * aufgerufen, nicht auf dem JavaFX-Thread.</p>
   *
   * @param username Benutzername aus dem Login-Dialog
   * @param password Passwort; wird nur fuer den Formularkoerper in einen
   *                 String umgesetzt und nicht aufbewahrt
   * @param totp     sechsstelliges Einmalkennwort
   * @return Benutzername, abgeleitete Domaene und die zu verbindenden Shares
   * @throws AuthenticationException bei abgelehnter Anmeldung ebenso wie bei
   *                                 einem nicht erreichbaren Endpunkt - die
   *                                 Meldung ist fuer die Oberflaeche
   *                                 gedacht
   */
  public AuthResult authenticate(String username, char[] password,
    String totp) throws AuthenticationException
  {
    // Hinweis: Auf HTTP-Ebene ist ein String unvermeidlich. Lebensdauer
    // kurz halten, niemals loggen.
    // scope=openid email ist noetig, damit der Standard-Claim "email"
    // ueberhaupt im Token landet - ohne ihn gaebe es keine Domaene.
    String form = "grant_type=password"
      + "&client_id=" + enc(props.keycloak().clientId())
      + "&client_secret=" + enc(props.keycloak().clientSecret())
      + "&username=" + enc(username)
      + "&password=" + enc(new String(password))
      + "&totp=" + enc(totp)
      + "&scope=openid email";

    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(props.keycloak().tokenEndpoint()))
      .timeout(Duration.ofSeconds(15))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .build();

    LOG.info("Direct Grant an {} (Client '{}', Benutzer '{}')",
      props.keycloak().tokenEndpoint(), props.keycloak().clientId(), username);

    HttpResponse<String> response;
    try
    {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    catch (IOException | InterruptedException e)
    {
      Thread.currentThread().interrupt();
      LOG.error("Token-Endpoint nicht erreichbar", e);
      throw new AuthenticationException(
        "Keycloak nicht erreichbar: " + e.getMessage(), e);
    }

    LOG.info("Token-Endpoint antwortet HTTP {}", response.statusCode());

    if (response.statusCode() != 200)
    {
      // Der Body enthaelt nur error/error_description, keine Credentials.
      LOG.warn("Anmeldung abgelehnt: {}", response.body());
      throw new AuthenticationException(errorMessage(response));
    }

    try
    {
      JsonNode body = mapper.readTree(response.body());
      String accessToken = body.path("access_token").asText();
      JsonNode claims = claims(accessToken);
      String domain = extractDomain(claims);
      List<SmbShare> shares = sharesFor(domain);
      LOG.info("Angemeldet: Domaene '{}', {} Share(s) - {}", domain,
        shares.size(), shares.stream().map(SmbShare::label).toList());
      return new AuthResult(username, domain, shares);
    }
    catch (IOException e)
    {
      throw new AuthenticationException(
        "Token-Antwort nicht lesbar: " + e.getMessage(), e);
    }
  }

  /**
   * Dekodiert den Payload eines JWT.
   *
   * <p>Keine Signaturpruefung - die Begruendung steht in der Klassendoku.
   * Ein JWT besteht aus drei base64url-kodierten Teilen; hier interessiert
   * nur der mittlere. Fehlt er, kommt ein leeres Objekt zurueck statt einer
   * Ausnahme: die Auswertung faellt dann sauber auf ihre Vorgaben
   * zurueck.</p>
   *
   * <p>Paketsichtbar und nicht privat, damit
   * {@code KeycloakAuthServiceTest} die Auswertung ohne Netz pruefen kann.
   * Gilt auch fuer {@link #extractDomain} und {@link #sharesFor}.</p>
   *
   * @param jwt das Access Token
   * @return die Claims, nie {@code null}
   * @throws IOException wenn der Payload kein gueltiges JSON ist
   */
  JsonNode claims(String jwt) throws IOException
  {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2)
    {
      return mapper.createObjectNode();
    }
    return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
  }

  /**
   * Leitet die AD-Domaene aus der Mailadresse im Token ab.
   *
   * <p>Regel: alles hinter dem letzten {@code @}, ohne die Top-Level-Domain -
   * aus {@code vorname.nachname@example.org} wird also {@code example}. Das
   * ist genau die Form, die der Fileserver als Domaenenanteil der Anmeldung
   * erwartet.</p>
   *
   * <p>Gesucht wird in drei Claims nacheinander: dem konfigurierten
   * {@code mail-claim}, dann {@code email}, dann {@code mail}. Keycloak
   * liefert standardmaessig {@code email}; {@code mail} kommt vor, wenn die
   * Adresse ueber einen eigenen LDAP-Mapper gesetzt wird. Traegt keiner der
   * drei eine brauchbare Adresse, gilt die konfigurierte Vorgabe
   * {@code smb-domain}.</p>
   *
   * <p>Die Domaene stammt damit <b>pro Benutzer</b> aus dem Token und nicht
   * aus der Konfiguration - wichtig, sobald Benutzer aus mehreren Domaenen
   * dieselbe Anwendung verwenden.</p>
   *
   * @param claims dekodierter Token-Payload
   * @return Domaenenname ohne Punkt, nie {@code null}
   */
  String extractDomain(JsonNode claims)
  {
    for (String name : new String[]
    {
      props.mailClaim(), "email", "mail"
    })
    {
      if (name == null || name.isBlank())
      {
        continue;
      }
      String mail = claims.path(name).asText("");
      int at = mail.lastIndexOf('@');
      if (at < 0)
      {
        continue;
      }
      String host = mail.substring(at + 1);
      int dot = host.lastIndexOf('.');
      String domain = dot > 0 ? host.substring(0, dot) : host;
      if (!domain.isBlank())
      {
        return domain;
      }
    }
    return props.smbDomain();
  }

  /**
   * Schlaegt die Shares der Domaene in der Konfiguration nach.
   *
   * <p>Die einzige Quelle. Frueher konnte ein optionaler Token-Claim
   * ({@code smbShares}) eine benutzerspezifische Liste mitbringen und die
   * Konfiguration ueberstimmen; der Weg ist entfallen, weil er nie
   * scharfgeschaltet wurde und im Native Image eine eigene
   * Jackson-Registrierung gebraucht haette.</p>
   *
   * <p>Eine unbekannte Domaene ergibt eine leere Liste - siehe
   * {@link DrivemountProperties#sharesFor}. Die Warnung hier nennt die
   * bekannten Schluessel, damit im Log steht, wonach vergeblich gesucht
   * wurde.</p>
   *
   * <p>Paketsichtbar und nicht privat, damit
   * {@code KeycloakAuthServiceTest} sie ohne Netz pruefen kann.</p>
   *
   * @param domain die aus dem Token abgeleitete AD-Domaene
   * @return die Shares dieser Domaene, sonst eine leere Liste
   */
  List<SmbShare> sharesFor(String domain)
  {
    List<SmbShare> configured = props.sharesFor(domain);
    if (configured.isEmpty())
    {
      LOG.warn("Keine Shares fuer Domaene '{}' konfiguriert - bekannt: {}",
        domain, props.shares().keySet());
    }
    return configured;
  }

  /**
   * Formt aus einer abgelehnten Antwort eine Meldung fuer die Oberflaeche.
   *
   * <p>Keycloak antwortet bei falschem Passwort <b>und</b> falschem
   * Einmalkennwort absichtlich identisch mit "Invalid user credentials" -
   * der Versuch, beides zu unterscheiden, ist deshalb zwecklos.</p>
   *
   * @param response die Antwort mit Status ungleich 200
   * @return Meldung im Klartext, notfalls nur mit dem HTTP-Status
   */
  private String errorMessage(HttpResponse<String> response)
  {
    try
    {
      JsonNode error = mapper.readTree(response.body());
      String description = error.path("error_description")
        .asText(error.path("error").asText("unbekannter Fehler"));
      // Keycloak antwortet bei falschem Passwort UND falschem OTP
      // absichtlich identisch mit "Invalid user credentials".
      return "Anmeldung fehlgeschlagen: " + description;
    }
    catch (IOException e)
    {
      return "Anmeldung fehlgeschlagen (HTTP " + response.statusCode() + ")";
    }
  }

  /**
   * URL-kodiert einen Wert fuer den Formularkoerper.
   *
   * @param value der Wert, auch Passwort oder Einmalkennwort
   * @return kodierte Form
   */
  private static String enc(String value)
  {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
