/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Die Zuordnung Betriebssystem -&gt; Kommando fuer den Standardbrowser.
 *
 * <p>Reine Abbildung, ohne Prozess und ohne Oberflaeche - genau wie die
 * Fehlertabellen der Mounter. Der Rueckfallweg greift nur, wenn die
 * JavaFX-HostServices nicht zur Verfuegung stehen; getestet wird er
 * trotzdem, weil sich sein Fehler sonst erst auf einem fremden Rechner
 * zeigt.</p>
 */
class BrowserLauncherTest
{
  private static final String URL = "https://idp.example.org/account";

  @Test
  @DisplayName("macOS oeffnet mit open")
  void macos()
  {
    assertThat(BrowserLauncher.browserCommand("Mac OS X", URL))
      .containsExactly("open", URL);
  }

  @Test
  @DisplayName("Windows oeffnet ueber den FileProtocolHandler")
  void windows()
  {
    // Nicht "cmd /c start": das braucht die Shell, die & in einer URL als
    // Trenner liest.
    assertThat(BrowserLauncher.browserCommand("Windows 11", URL))
      .containsExactly("rundll32", "url.dll,FileProtocolHandler", URL);
  }

  @Test
  @DisplayName("Linux oeffnet mit xdg-open")
  void linux()
  {
    assertThat(BrowserLauncher.browserCommand("Linux", URL))
      .containsExactly("xdg-open", URL);
  }

  @Test
  @DisplayName("Die Schreibweise von os.name spielt keine Rolle")
  void caseInsensitive()
  {
    assertThat(BrowserLauncher.browserCommand("MAC OS X", URL))
      .containsExactly("open", URL);
  }

  @Test
  @DisplayName("Unbekanntes System ergibt kein Kommando statt eines falschen")
  void unknown()
  {
    assertThat(BrowserLauncher.browserCommand("Haiku", URL)).isEmpty();
    assertThat(BrowserLauncher.browserCommand(null, URL)).isEmpty();
  }
}
