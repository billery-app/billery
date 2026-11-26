package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.billery.TestWiring;
import de.billery.data.TestDatabaseConfig;
import de.billery.preferences.InstallationPreferences;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PreferencesController} on self-host: the language per installation (#15). */
class PreferencesControllerTest {

  private PreferencesController preferences;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void fresh() throws Exception {
    TestDatabaseConfig.clearData();
    preferences =
        new PreferencesController(new InstallationPreferences(TestWiring.settingsRepository()));
  }

  @Test
  void noLanguageUntilOneIsChosen() {
    assertNull(preferences.get().get("language"));
  }

  @Test
  void storesAnOfferedLanguage() {
    var response = preferences.update(Map.of("language", "en"));
    assertEquals(200, response.getStatusCode().value());
    assertEquals("en", response.getBody().get("language"));
    assertEquals("en", preferences.get().get("language"));
    preferences.update(Map.of("language", "de"));
    assertEquals("de", preferences.get().get("language"));
  }

  @Test
  void refusesALanguageTheAppDoesNotOffer() {
    preferences.update(Map.of("language", "en"));
    assertEquals(400, preferences.update(Map.of("language", "fr")).getStatusCode().value());
    assertEquals(400, preferences.update(Map.of()).getStatusCode().value());
    assertEquals("en", preferences.get().get("language"), "a refused value changes nothing");
  }
}
