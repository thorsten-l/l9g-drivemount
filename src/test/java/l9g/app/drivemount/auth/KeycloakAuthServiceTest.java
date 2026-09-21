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
import java.util.Map;
import l9g.app.drivemount.config.DrivemountProperties;
import l9g.app.drivemount.model.SmbShare;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auswertung des Token-Payloads - ohne Netz. Aus dem Token wird nur noch die
 * AD-Domaene abgeleitet; sie waehlt dann den Share-Block aus der
 * Konfiguration aus.
 */
class KeycloakAuthServiceTest
{
  private static final List<SmbShare> EXAMPLE_SHARES = List.of(
    new SmbShare("User Home", "smb://fileserver.example.org/home", "H"),
    new SmbShare("Group Share", "smb://fileserver.example.org/group", "G"));

  private static final List<SmbShare> ZWEI_SHARES = List.of(
    new SmbShare("User Home", "smb://fileserver.example-zwei.org/home", "U"));

  /**
   * Zwei Domaenen und eine absichtlich gross geschriebene dritte - der
   * Schluessel soll beim Binden kleingeschrieben werden, damit die aus der
   * Mailadresse abgeleitete Domaene ihn trifft.
   */
  private static final Map<String, List<SmbShare>> SHARES_BY_DOMAIN = Map.of(
    "example", EXAMPLE_SHARES,
    "example-zwei", ZWEI_SHARES,
    "GROSS", List.of(new SmbShare("Gross", "smb://fs/gross", "X")));

  private static KeycloakAuthService service()
  {
    return new KeycloakAuthService(new DrivemountProperties(
      new DrivemountProperties.Keycloak("https://idp.example.org", "r", "c",
        "s"),
      "email", "fallbackdomain", Duration.ofSeconds(2),
      SHARES_BY_DOMAIN));
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
  @DisplayName("Die Shares der abgeleiteten Domaene gelten")
  void sharesFromDomain()
  {
    assertThat(service().sharesFor("example")).isEqualTo(EXAMPLE_SHARES);
  }

  @Test
  @DisplayName("Eine andere Domaene bekommt ihre eigenen Shares")
  void sharesOfSecondDomain()
  {
    assertThat(service().sharesFor("example-zwei")).isEqualTo(ZWEI_SHARES);
  }

  @Test
  @DisplayName("Unbekannte Domaene liefert nichts statt fremder Laufwerke")
  void sharesOfUnknownDomain()
  {
    assertThat(service().sharesFor("fremd")).isEmpty();
  }

  @Test
  @DisplayName("Domaenen-Schluessel treffen unabhaengig von der Schreibweise")
  void sharesDomainIsCaseInsensitive()
  {
    KeycloakAuthService service = service();
    assertThat(service.sharesFor("gross"))
      .isEqualTo(service.sharesFor("GROSS"))
      .isEqualTo(service.sharesFor("Gross"))
      .hasSize(1);
  }

  // -------------------------------------------------------------- Token
  @Test
  @DisplayName("Ein Token ohne Punkte liefert leere Claims statt einer Ausnahme")
  void claimsOfMalformedToken() throws IOException
  {
    assertThat(service().claims("keinjwt").isEmpty()).isTrue();
  }
}
