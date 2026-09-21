/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.model;

/**
 * Ein zu verbindendes SMB-Share.
 *
 * <p>Einzige Quelle ist {@code drivemount.shares} in der Konfiguration,
 * nach AD-Domaene gruppiert. Frueher konnte derselbe Record auch aus dem
 * Token-Claim {@code smbShares} kommen; dafuer trug er eine
 * Jackson-Annotation, die mit dem Claim entfallen ist. Gebunden wird er
 * jetzt ausschliesslich vom Spring-Konfigurationsbinder.</p>
 *
 * @param label Anzeigename in der Ergebnisliste ("User Home", "Group Share")
 * @param url   Adresse der Freigabe, {@code smb://host/share[/pfad]}
 * @param mount Windows-Laufwerksbuchstabe ("H"); unter macOS und Linux ohne
 *              Bedeutung und dort auch leer oder {@code null} erlaubt
 */
public record SmbShare(String label, String url, String mount)
{
  /**
   * Wandelt die SMB-URL in einen UNC-Pfad, wie ihn
   * {@code WNetAddConnection2W} erwartet.
   *
   * <p>Aus {@code smb://host/share} wird {@code \\host\share}: das Schema
   * faellt weg, zwei Backslashes kommen davor, und die Schraegstriche drehen
   * sich um. Ein fehlendes {@code smb://} ist erlaubt - dann bleibt der Rest
   * unveraendert und wird nur umgesetzt.</p>
   *
   * <p>Nur Windows braucht das; macOS und Linux arbeiten direkt mit
   * {@link #url()}.</p>
   *
   * @return UNC-Pfad mit fuehrenden Backslashes
   */
  public String uncPath()
  {
    String s = url.replaceFirst("^smb://", "");
    return "\\\\" + s.replace('/', '\\');
  }
}
