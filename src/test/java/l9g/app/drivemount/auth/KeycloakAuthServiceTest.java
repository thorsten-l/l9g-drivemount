/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import l9g.app.drivemount.config.DrivemountProperties;
import l9g.app.drivemount.model.SmbShare;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auswertung des Token-Payloads - ohne Netz. Geprueft werden die beiden
 * Stellen, an denen aus dem Token etwas abgeleitet wird: die AD-Domaene aus
 * der Mailadresse und die Share-Liste aus dem optionalen Claim.
 */
class KeycloakAuthServiceTest
{
  private static final List<SmbShare> STATIC_SHARES = List.of(
    new SmbShare("User Home", "smb://fileserver.example.org/home", "H"),
    new SmbShare("Group Share", "smb://fileserver.example.org/group", "G"));

  private static KeycloakAuthService service()
  {
    return new KeycloakAuthService(new DrivemountProperties(
      new DrivemountProperties.Keycloak("https://idp.example.org", "r", "c",
        "s"),
      "smbShares", "email", "fallbackdomain", Duration.ofSeconds(2),
      STATIC_SHARES));
  }

  /** JWT ohne gueltige Signatur - die wird bewusst nicht geprueft. */
  private static String jwt(String payload)
  {
    return "header." + Base64.getUrlEncoder().withoutPadding()
      .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
  }

  private static JsonNode claims(String payload) throws IOException
  {
    return service().claims(jwt(payload));
  }

  // ------------------------------------------------------------ Domaene
  @Test
  @DisplayName("Domaene ist der Host ohne Top-Level-Domain")
  void domainFromMail() throws IOException
  {
    assertThat(service().extractDomain(
      claims("{\"email\":\"vorname.nachname@example.org\"}")))
      .isEqualTo("example");
  }

  @Test
  @DisplayName("Nur die letzte Endung faellt weg")
  void domainKeepsInnerDots() throws IOException
  {
    assertThat(service().extractDomain(
      claims("{\"email\":\"jemand@mail.example.co.uk\"}")))
      .isEqualTo("mail.example.co");
  }

  @Test
  @DisplayName("Host ohne Punkt bleibt vollstaendig")
  void domainWithoutDot() throws IOException
  {
    assertThat(service().extractDomain(claims("{\"email\":\"a@intranet\"}")))
      .isEqualTo("intranet");
  }

  @Test
  @DisplayName("Ohne @ greift die konfigurierte Domaene")
  void domainFallbackWhenNoAtSign() throws IOException
  {
    assertThat(service().extractDomain(claims("{\"email\":\"kaputt\"}")))
      .isEqualTo("fallbackdomain");
  }

  @Test
  @DisplayName("Ohne Mail-Claim greift die konfigurierte Domaene")
  void domainFallbackWhenClaimMissing() throws IOException
  {
    assertThat(service().extractDomain(claims("{\"sub\":\"1234\"}")))
      .isEqualTo("fallbackdomain");
  }

  @Test
  @DisplayName("mail wird geprueft, wenn email fehlt")
  void domainFromAlternativeClaim() throws IOException
  {
    assertThat(service().extractDomain(
      claims("{\"mail\":\"nutzer@example.org\"}")))
      .isEqualTo("example");
  }

  // ------------------------------------------------------------- Shares
  @Test
  @DisplayName("Ohne Claim gelten die statischen Shares")
  void sharesFallbackWhenClaimMissing() throws IOException
  {
    assertThat(service().extractShares(claims("{\"sub\":\"1234\"}")))
      .isEqualTo(STATIC_SHARES);
  }

  @Test
  @DisplayName("Leerer Claim faellt auf die statischen Shares zurueck")
  void sharesFallbackWhenClaimEmpty() throws IOException
  {
    assertThat(service().extractShares(claims("{\"smbShares\":[]}")))
      .isEqualTo(STATIC_SHARES);
  }

  @Test
  @DisplayName("Claim als JSON-Array gewinnt gegen die Konfiguration")
  void sharesFromArrayClaim() throws IOException
  {
    List<SmbShare> shares = service().extractShares(claims(
      "{\"smbShares\":[{\"label\":\"Projekt\","
      + "\"url\":\"smb://fs/proj\",\"mount\":\"P\"}]}"));

    assertThat(shares).containsExactly(
      new SmbShare("Projekt", "smb://fs/proj", "P"));
  }

  @Test
  @DisplayName("Claim als JSON-String mit Array wird ebenfalls gelesen")
  void sharesFromStringClaim() throws IOException
  {
    List<SmbShare> shares = service().extractShares(claims(
      "{\"smbShares\":\"[{\\\"label\\\":\\\"Projekt\\\","
      + "\\\"url\\\":\\\"smb://fs/proj\\\"}]\"}"));

    assertThat(shares).containsExactly(
      new SmbShare("Projekt", "smb://fs/proj", null));
  }

  @Test
  @DisplayName("Unbekannte Felder im Claim stoeren nicht")
  void sharesIgnoreUnknownFields() throws IOException
  {
    List<SmbShare> shares = service().extractShares(claims(
      "{\"smbShares\":[{\"label\":\"A\",\"url\":\"smb://fs/a\","
      + "\"unbekannt\":42}]}"));

    assertThat(shares).containsExactly(new SmbShare("A", "smb://fs/a", null));
  }

  // -------------------------------------------------------------- Token
  @Test
  @DisplayName("Ein Token ohne Punkte liefert leere Claims statt einer Ausnahme")
  void claimsOfMalformedToken() throws IOException
  {
    assertThat(service().claims("keinjwt").isEmpty()).isTrue();
  }
}
