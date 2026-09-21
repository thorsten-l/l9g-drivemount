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
 * Reine Stringlogik, laeuft auf jeder Plattform. Anlass war ein echter Lauf:
 * "WNet-Fehlercode 67" sagte niemandem, dass der Rechner den Servernamen
 * nicht aufloesen konnte.
 */
class WindowsWNetMounterTest
{
  private static final SmbShare SHARE
    = new SmbShare("User Home", "smb://fileserver.example.org/home", "H");

  @Test
  @DisplayName("67 nennt den Servernamen und verweist auf die Aufloesung")
  void badNetName()
  {
    assertThat(WindowsWNetMounter.errorMessage(67, SHARE))
      .contains("67")
      .contains("fileserver.example.org")
      .contains("DNS");
  }

  @Test
  @DisplayName("53 nennt den vollstaendigen UNC-Pfad")
  void badNetPath()
  {
    assertThat(WindowsWNetMounter.errorMessage(53, SHARE))
      .contains("53")
      .contains("\\\\fileserver.example.org\\home");
  }

  @Test
  @DisplayName("1219 erklaert den Credential-Konflikt samt Abhilfe")
  void credentialConflict()
  {
    assertThat(WindowsWNetMounter.errorMessage(1219, SHARE))
      .contains("1219")
      .contains("fileserver.example.org")
      .contains("net use");
  }

  @Test
  @DisplayName("86 nennt alle drei moeglichen Ursachen")
  void invalidPassword()
  {
    // Gemessen gegen fileserver.example.org: ein Benutzername, den es nicht gibt,
    // liefert 86 und nicht 1326. Die Meldung darf deshalb nicht allein auf
    // das Passwort zeigen.
    assertThat(WindowsWNetMounter.errorMessage(86, SHARE))
      .contains("86")
      .containsIgnoringCase("benutzername")
      .containsIgnoringCase("passwort");
  }

  @Test
  @DisplayName("1326 ist eine fehlgeschlagene Anmeldung")
  void logonFailure()
  {
    assertThat(WindowsWNetMounter.errorMessage(1326, SHARE))
      .contains("1326")
      .containsIgnoringCase("passwort");
  }

  @Test
  @DisplayName("Unbekannte Codes werden mit ihrer Nummer durchgereicht")
  void unknownCode()
  {
    assertThat(WindowsWNetMounter.errorMessage(4711, SHARE))
      .isEqualTo("WNet-Fehlercode 4711");
  }

  @Test
  @DisplayName("Ohne Laufwerksbuchstabe bleibt 1200 beantwortbar")
  void invalidDeviceWithoutMount()
  {
    SmbShare ohne = new SmbShare("X", "smb://fs/x", null);
    assertThat(WindowsWNetMounter.errorMessage(1200, ohne)).contains("1200");
  }
}
