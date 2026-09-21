/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.model;

import java.util.List;

/**
 * Ergebnis einer erfolgreichen Keycloak-Authentifizierung.
 *
 * <p>Das Access Token selbst wird bewusst <b>nicht</b> aufbewahrt - nur die
 * drei Angaben, die fuer den Mount gebraucht werden. Das Token hat seinen
 * Zweck erfuellt, sobald Domaene und Share-Liste daraus gelesen sind; es
 * laenger zu halten hiesse nur, ein Geheimnis ohne Nutzen mitzuschleppen.</p>
 *
 * @param username  Benutzername aus dem Login-Dialog, ohne Domaenenanteil
 * @param smbDomain AD-Domaene, aus dem Mail-Claim des Tokens abgeleitet -
 *                  pro Benutzer und nicht aus der Konfiguration
 * @param shares    zu verbindende Shares, entweder die statischen aus der
 *                  Konfiguration oder die aus dem optionalen Claim
 */
public record AuthResult(String username, String smbDomain,
  List<SmbShare> shares)
{
}
