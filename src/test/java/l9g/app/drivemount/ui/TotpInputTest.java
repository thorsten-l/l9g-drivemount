/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package l9g.app.drivemount.ui;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Die Regel hinter dem TOTP-Filter. Der TextFormatter prueft jede Aenderung,
 * also auch Einfuegen aus der Zwischenablage und Ersetzen einer Auswahl -
 * deshalb muss die Regel den *neuen Gesamtinhalt* beurteilen, nicht die
 * einzelne Eingabe.
 */
class TotpInputTest
{
  @ParameterizedTest
  @ValueSource(strings = { "", "1", "12", "123456" })
  @DisplayName("Bis zu sechs Ziffern sind erlaubt")
  void acceptsUpToSixDigits(String input)
  {
    assertThat(LoginController.isAcceptedTotpInput(input)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = { "1234567", "12345a", "a", " ", "12 34", "12-34",
    "１２３４５６" })
  @DisplayName("Alles andere wird abgelehnt")
  void rejectsEverythingElse(String input)
  {
    assertThat(LoginController.isAcceptedTotpInput(input)).isFalse();
  }

  @Test
  @DisplayName("null ist kein gueltiger Inhalt und wirft nicht")
  void rejectsNull()
  {
    assertThat(LoginController.isAcceptedTotpInput(null)).isFalse();
  }
}
