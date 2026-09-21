/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import l9g.app.drivemount.model.SmbShare;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bindet den {@code drivemount}-Abschnitt aus der {@code application.yaml}.
 *
 * <p>Ein Record und keine Bean mit Settern: die Konfiguration ist nach dem
 * Start unveraenderlich, und Spring bindet ueber den Konstruktor. Gefunden
 * wird der Typ ueber {@code @ConfigurationPropertiesScan}, deshalb steht
 * hier kein {@code @Component}.</p>
 *
 * @param keycloak    Keycloak-Verbindungsdaten (Direct-Grant-Client)
 * @param mailClaim   Name des Token-Claims mit der Mailadresse, aus der die
 *                    AD-Domaene abgeleitet wird (Keycloak-Standard: "email")
 * @param smbDomain   Fallback-Domaene, falls im Token keine Mailadresse steht
 * @param closeDelay  Wartezeit, bevor sich das Fenster nach erfolgreichem
 *                    Verbinden aller Laufwerke schliesst (z.B. "2s", "500ms",
 *                    "0" fuer sofort). Default: 2s.
 * @param shares      Statische Shares <b>je AD-Domaene</b>. Der Schluessel
 *                    ist die Domaene, wie sie
 *                    {@code KeycloakAuthService#extractDomain} aus der
 *                    Mailadresse ableitet: Teil hinter dem "@" ohne
 *                    Top-Level-Domain, aus {@code a@example-zwei.de} also
 *                    {@code example-zwei} - der Bindestrich gehoert dazu.
 *                    Zugriff ueber {@link #sharesFor}.
 */
@ConfigurationProperties(prefix = "drivemount")
public record DrivemountProperties(
  Keycloak keycloak,
  String mailClaim,
  String smbDomain,
  Duration closeDelay,
  Map<String, List<SmbShare>> shares)
{
  /**
   * Setzt Vorgaben und macht die Share-Zuordnung unveraenderlich.
   *
   * <p>{@code shares} darf in der Konfiguration fehlen - dann gibt es fuer
   * niemanden etwas zu verbinden, und eine leere Map ist richtiger als
   * {@code null}. {@code closeDelay} faellt auf zwei Sekunden zurueck, lang
   * genug, um die Ergebnisliste zu lesen.</p>
   *
   * <p>Die Domaenen-Schluessel werden hier einmal kleingeschrieben, damit
   * {@link #sharesFor} ohne Ruecksicht auf Gross-/Kleinschreibung nachsehen
   * kann. Zwei Schluessel, die sich nur darin unterscheiden, sind ein
   * Konfigurationsfehler und brechen den Start ab - stillschweigend einen
   * davon zu verwerfen hiesse, jemandem wortlos die falschen Laufwerke zu
   * geben.</p>
   *
   * @throws IllegalArgumentException wenn zwei Domaenen-Schluessel sich nur
   *                                  in der Gross-/Kleinschreibung
   *                                  unterscheiden
   */
  public DrivemountProperties
  {
    closeDelay = closeDelay == null ? Duration.ofSeconds(2) : closeDelay;

    if (shares == null)
    {
      shares = Map.of();
    }
    else
    {
      Map<String, List<SmbShare>> normalized = new LinkedHashMap<>();
      for (Map.Entry<String, List<SmbShare>> entry : shares.entrySet())
      {
        String domain = entry.getKey().toLowerCase(Locale.ROOT);
        List<SmbShare> list = entry.getValue() == null
          ? List.of() : List.copyOf(entry.getValue());
        if (normalized.put(domain, list) != null)
        {
          throw new IllegalArgumentException(
            "drivemount.shares: Domaene '" + domain
            + "' ist mehrfach konfiguriert");
        }
      }
      shares = Map.copyOf(normalized);
    }
  }

  /**
   * Liefert die konfigurierten Shares einer AD-Domaene.
   *
   * <p>Die Gross-/Kleinschreibung spielt keine Rolle: die Schluessel sind
   * beim Binden kleingeschrieben worden, der uebergebene Name wird es hier.
   * Aus dem Token kommt die Domaene zwar praktisch immer klein, aber sie
   * stammt aus einer Mailadresse und ist damit nichts, worauf man sich
   * verlassen sollte.</p>
   *
   * <p>Eine unbekannte Domaene ergibt eine <b>leere</b> Liste und keine
   * Ausnahme. Der Aufrufer zeigt das in der Oberflaeche an; das Fenster
   * bleibt offen, weil nichts verbunden wurde.</p>
   *
   * @param domain AD-Domaene, etwa {@code example}; darf {@code null} sein
   * @return die Shares dieser Domaene, sonst eine leere Liste
   */
  public List<SmbShare> sharesFor(String domain)
  {
    if (domain == null || domain.isBlank())
    {
      return List.of();
    }
    return shares.getOrDefault(domain.toLowerCase(Locale.ROOT), List.of());
  }

  /**
   * Verbindungsdaten des Keycloak-Clients fuer den Direct Grant.
   *
   * @param baseUrl      Basis-URL ohne {@code /realms/...}
   * @param realm        Name des Realms
   * @param clientId     Client-ID aus der Keycloak-Administration
   * @param clientSecret Secret des confidential Clients. Steht in der
   *                     Konfiguration als {@code {AES256}}-Wert und wird beim
   *                     Start von l9g-crypto entschluesselt. Das ist
   *                     <b>Verschleierung, kein Schutz</b> - der Schluessel
   *                     liegt als {@code assets/secret.bin} im selben
   *                     Artefakt. Abgesichert wird die Anmeldung durch
   *                     Passwort und Einmalkennwort, nicht hierdurch.
   */
  public record Keycloak(
    String baseUrl,
    String realm,
    String clientId,
    String clientSecret)
  {
    /**
     * Setzt den Token-Endpunkt aus Basis-URL und Realm zusammen.
     *
     * @return vollstaendige URL des {@code openid-connect/token}-Endpunkts
     */
    public String tokenEndpoint()
    {
      return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }
  }
}
