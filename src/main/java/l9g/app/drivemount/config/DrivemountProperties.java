/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.config;

import java.time.Duration;
import java.util.List;
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
 * @param sharesClaim Name des Token-Claims mit einer benutzerspezifischen
 *                    Share-Liste. Optional - fehlt der Claim, gelten die
 *                    statischen Shares aus {@code shares}.
 * @param mailClaim   Name des Token-Claims mit der Mailadresse, aus der die
 *                    AD-Domaene abgeleitet wird (Keycloak-Standard: "email")
 * @param smbDomain   Fallback-Domaene, falls im Token keine Mailadresse steht
 * @param closeDelay  Wartezeit, bevor sich das Fenster nach erfolgreichem
 *                    Verbinden aller Laufwerke schliesst (z.B. "2s", "500ms",
 *                    "0" fuer sofort). Default: 2s.
 * @param shares      Statische Shares, die fuer alle Benutzer gelten
 */
@ConfigurationProperties(prefix = "drivemount")
public record DrivemountProperties(
  Keycloak keycloak,
  String sharesClaim,
  String mailClaim,
  String smbDomain,
  Duration closeDelay,
  List<SmbShare> shares)
{
  /**
   * Setzt Vorgaben und macht die Share-Liste unveraenderlich.
   *
   * <p>{@code shares} darf in der Konfiguration fehlen - dann bleibt nur der
   * optionale Token-Claim als Quelle, und eine leere Liste ist richtiger als
   * {@code null}. {@code closeDelay} faellt auf zwei Sekunden zurueck, lang
   * genug, um die Ergebnisliste zu lesen.</p>
   */
  public DrivemountProperties
  {
    shares = shares == null ? List.of() : List.copyOf(shares);
    closeDelay = closeDelay == null ? Duration.ofSeconds(2) : closeDelay;
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
