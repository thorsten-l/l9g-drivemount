/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft, dass der Lizenztext wirklich im Artefakt liegt.
 *
 * <p>Das Lizenzfenster faellt ohne die Ressource lautlos auf einen Ersatztext
 * zurueck - ein Fehler, den man erst beim Druck auf Strg+Alt+L saehe. Die
 * Kopie nach {@code assets/LICENSE} macht Maven ueber einen
 * {@code <resource>}-Eintrag mit {@code targetPath}; faellt der irgendwann
 * heraus, schlaegt dieser Test fehl statt der Oberflaeche.</p>
 *
 * <p>Kein JavaFX noetig: hier wird nur die Ressource gelesen, nicht der
 * Dialog gebaut.</p>
 */
class LicenseResourceTest
{
  @Test
  void licenseIsPackaged() throws IOException
  {
    String text;
    try(InputStream in
      = LicenseDialog.class.getResourceAsStream(LicenseDialog.RESOURCE))
    {
      assertNotNull(in, LicenseDialog.RESOURCE + " fehlt im Klassenpfad");
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(text.contains("Apache License"), "Kopfzeile fehlt");
    assertTrue(text.contains("Version 2.0, January 2004"), "Version fehlt");
    // Der Anhang der Lizenz traegt die Copyright-Zeile des Projekts; genau
    // die soll das Fenster zeigen.
    assertTrue(text.contains("Copyright 2026 Thorsten Ludewig"),
      "Copyright-Zeile fehlt");
    // Vollstaendigkeit: die Lizenz endet mit den Bedingungen, nicht mitten im
    // Text.
    assertTrue(text.contains("limitations under the License."), "Ende fehlt");
  }
}
