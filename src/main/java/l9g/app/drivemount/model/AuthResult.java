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
 * Zweck erfuellt, sobald die Domaene daraus gelesen ist; es laenger zu
 * halten hiesse nur, ein Geheimnis ohne Nutzen mitzuschleppen.</p>
 *
 * @param username  Benutzername aus dem Login-Dialog, ohne Domaenenanteil
 * @param smbDomain AD-Domaene, aus dem Mail-Claim des Tokens abgeleitet -
 *                  pro Benutzer und nicht aus der Konfiguration
 * @param shares    zu verbindende Shares: die in der Konfiguration unter
 *                  dieser Domaene hinterlegten. Leer, wenn die Domaene dort
 *                  nicht vorkommt - dann bleibt das Fenster mit einem
 *                  Hinweis offen
 */
public record AuthResult(String username, String smbDomain,
  List<SmbShare> shares)
{
}
