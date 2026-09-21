/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.model;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * uncPath() fuettert WNetAddConnection2W - ein Fehler hier aeussert sich
 * unter Windows als Fehlercode 67 und sieht dann nach einem Netzwerkproblem
 * aus.
 */
class SmbShareTest
{
  private static SmbShare share(String url)
  {
    return new SmbShare("Test", url, null);
  }

  @Test
  @DisplayName("smb://host/share wird zum UNC-Pfad")
  void uncPathFromSmbUrl()
  {
    assertThat(share("smb://fileserver.example.org/home").uncPath())
      .isEqualTo("\\\\fileserver.example.org\\home");
  }

  @Test
  @DisplayName("Mehrstufiger Pfad behaelt alle Segmente")
  void uncPathWithSubPath()
  {
    assertThat(share("smb://fileserver.example.org/group/abt/projekt").uncPath())
      .isEqualTo("\\\\fileserver.example.org\\group\\abt\\projekt");
  }

  @Test
  @DisplayName("Ohne smb://-Praefix bleibt der Rest unveraendert")
  void uncPathWithoutScheme()
  {
    assertThat(share("fileserver.example.org/home").uncPath())
      .isEqualTo("\\\\fileserver.example.org\\home");
  }

  @Test
  @DisplayName("Nur der Server ergibt einen Serverpfad ohne Freigabe")
  void uncPathServerOnly()
  {
    assertThat(share("smb://fileserver.example.org").uncPath())
      .isEqualTo("\\\\fileserver.example.org");
  }
}
