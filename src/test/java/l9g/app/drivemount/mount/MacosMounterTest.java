/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.mount;

import l9g.app.drivemount.model.SmbShare;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NetFSMountURLSync mischt zwei Fehlerraeume: positive Werte sind errno,
 * negative OSStatus. Die Tabelle wird hier ohne macOS geprueft - errorMessage
 * ist statisch und laedt keine Frameworks.
 */
class MacosMounterTest
{
  private static final SmbShare SHARE
    = new SmbShare("User Home", "smb://fileserver.example.org/home", null);

  @Test
  @DisplayName("errno 65 verweist auf Namensaufloesung und Netzweg")
  void hostUnreachable()
  {
    assertThat(MacosMounter.errorMessage(65, SHARE))
      .containsIgnoringCase("route")
      .contains("DNS");
  }

  @Test
  @DisplayName("errno 2 nennt die betroffene URL")
  void notFound()
  {
    assertThat(MacosMounter.errorMessage(2, SHARE))
      .contains("smb://fileserver.example.org/home");
  }

  @Test
  @DisplayName("errno 17 meldet eine bestehende Verbindung")
  void alreadyMounted()
  {
    assertThat(MacosMounter.errorMessage(17, SHARE))
      .containsIgnoringCase("bereits verbunden");
  }

  @Test
  @DisplayName("OSStatus -128 ist der Abbruch durch den Benutzer")
  void userCancelled()
  {
    assertThat(MacosMounter.errorMessage(-128, SHARE))
      .containsIgnoringCase("abgebrochen");
  }

  @Test
  @DisplayName("OSStatus -5999 meldet ein gesperrtes Konto")
  void accountRestricted()
  {
    assertThat(MacosMounter.errorMessage(-5999, SHARE))
      .containsIgnoringCase("konto");
  }

  @Test
  @DisplayName("Unbekannte Codes behalten ihr Vorzeichen")
  void unknownCodes()
  {
    assertThat(MacosMounter.errorMessage(4711, SHARE))
      .isEqualTo("NetFS-Fehlercode 4711");
    assertThat(MacosMounter.errorMessage(-4711, SHARE))
      .isEqualTo("NetFS-Fehlercode -4711");
  }
}
