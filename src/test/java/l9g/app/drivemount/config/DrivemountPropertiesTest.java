/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import l9g.app.drivemount.model.SmbShare;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die Domaenen-Zuordnung aus {@code drivemount.shares} - reine Logik im
 * Kompaktkonstruktor und in {@code sharesFor}, ohne Spring-Kontext.
 */
class DrivemountPropertiesTest
{
  private static final SmbShare HOME
    = new SmbShare("User Home", "smb://fileserver.example.org/home", "U");

  private static DrivemountProperties props(
    Map<String, List<SmbShare>> shares)
  {
    return new DrivemountProperties(
      new DrivemountProperties.Keycloak("https://idp.example.org", "r", "c",
        "s"),
      "email", "fallbackdomain", Duration.ofSeconds(2), null, shares);
  }

  @Test
  @DisplayName("sharesFor findet die Liste der Domaene")
  void sharesForKnownDomain()
  {
    assertThat(props(Map.of("example", List.of(HOME))).sharesFor("example"))
      .containsExactly(HOME);
  }

  @Test
  @DisplayName("Schluessel und Abfrage sind unabhaengig von der Schreibweise")
  void sharesForIsCaseInsensitive()
  {
    DrivemountProperties p = props(Map.of("Example", List.of(HOME)));
    assertThat(p.shares()).containsOnlyKeys("example");
    assertThat(p.sharesFor("EXAMPLE")).containsExactly(HOME);
  }

  @Test
  @DisplayName("Ein Bindestrich im Domaenennamen bleibt erhalten")
  void sharesForHyphenatedDomain()
  {
    assertThat(props(Map.of("example-zwei", List.of(HOME))).sharesFor("example-zwei"))
      .containsExactly(HOME);
  }

  @Test
  @DisplayName("Unbekannte Domaene liefert eine leere Liste")
  void sharesForUnknownDomain()
  {
    assertThat(props(Map.of("example", List.of(HOME))).sharesFor("fremd"))
      .isEmpty();
  }

  @Test
  @DisplayName("null und Leerstring liefern eine leere Liste")
  void sharesForNullDomain()
  {
    DrivemountProperties p = props(Map.of("example", List.of(HOME)));
    assertThat(p.sharesFor(null)).isEmpty();
    assertThat(p.sharesFor("  ")).isEmpty();
  }

  @Test
  @DisplayName("Fehlendes shares ergibt eine leere Map statt null")
  void sharesMayBeAbsent()
  {
    assertThat(props(null).shares()).isEmpty();
    assertThat(props(null).sharesFor("example")).isEmpty();
  }

  @Test
  @DisplayName("Zwei Schluessel, die sich nur in der Schreibweise "
    + "unterscheiden, brechen den Start ab")
  void duplicateDomainKeyIsRejected()
  {
    Map<String, List<SmbShare>> doppelt = new LinkedHashMap<>();
    doppelt.put("example", List.of(HOME));
    doppelt.put("Example", List.of());

    assertThatThrownBy(() -> props(doppelt))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("example");
  }

  @Test
  @DisplayName("Die gebundene Map ist unveraenderlich")
  void sharesAreImmutable()
  {
    Map<String, List<SmbShare>> quelle = new LinkedHashMap<>();
    quelle.put("example", List.of(HOME));
    DrivemountProperties p = props(quelle);

    quelle.clear();

    assertThat(p.sharesFor("example")).containsExactly(HOME);
    assertThatThrownBy(() -> p.shares().clear())
      .isInstanceOf(UnsupportedOperationException.class);
  }
}
