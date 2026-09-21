/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * gio schreibt bei einem Fehlschlag keine Fehlermeldung, sondern seine
 * Eingabeaufforderungen. Die Vorlagen hier stammen wortwoertlich aus Laeufen
 * gegen den echten Fileserver mit gio 2.80 (Ubuntu 24.04); Rechnername und
 * Benutzer sind durch Beispielwerte ersetzt, die Struktur ist unveraendert.
 */
class LinuxGioMounterTest
{
  /** Abgelehnte Anmeldedaten: gio fragt einfach erneut, bis stdin zu ist. */
  private static final String REJECTED = """
    Authentication Required
    Enter user and password for share “home” on “fileserver.example.org”:
    User [muster]: Domain [WORKGROUP]: Password:\s
    Authentication Required
    Enter user and password for share “home” on “fileserver.example.org”:
    User [muster]:\s""";

  /** Echter Fehler - einzige Form, in der gio etwas Verwertbares sagt. */
  private static final String REFUSED
    = "gio: smb://127.0.0.1/test/: Failed to mount Windows share:"
    + " Connection refused";

  @Test
  @DisplayName("Abgelehnte Anmeldedaten werden als solche benannt")
  void rejectedCredentials()
  {
    assertThat(LinuxGioMounter.errorMessage(2, REJECTED))
      .containsIgnoringCase("abgelehnt")
      .containsIgnoringCase("benutzername")
      .containsIgnoringCase("passwort");
  }

  @Test
  @DisplayName("Die Eingabeaufforderungen landen nicht in der Meldung")
  void promptsAreNotLeaked()
  {
    assertThat(LinuxGioMounter.errorMessage(2, REJECTED))
      .doesNotContain("Authentication Required")
      .doesNotContain("User [")
      .doesNotContain("Password:");
  }

  @Test
  @DisplayName("Echte gio-Fehler werden ohne Praefix durchgereicht")
  void realErrorKeepsItsReason()
  {
    assertThat(LinuxGioMounter.errorMessage(2, REFUSED))
      .isEqualTo("Failed to mount Windows share: Connection refused");
  }

  @Test
  @DisplayName("Der gio-Fehler gewinnt gegen die Abfragen")
  void realErrorWinsOverPrompts()
  {
    assertThat(LinuxGioMounter.errorMessage(2, REJECTED + "\n" + REFUSED))
      .isEqualTo("Failed to mount Windows share: Connection refused");
  }

  @Test
  @DisplayName("Ohne Ausgabe bleibt der Exit-Code")
  void emptyOutput()
  {
    assertThat(LinuxGioMounter.errorMessage(2, "")).isEqualTo("gio-Exit-Code 2");
    assertThat(LinuxGioMounter.errorMessage(7, null))
      .isEqualTo("gio-Exit-Code 7");
  }
}
