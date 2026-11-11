package de.billery.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.TestWiring;
import de.billery.data.TestDatabaseConfig;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reading and upserting {@code user_settings} on the MariaDB-compatible H2 database. */
class UserSettingsRepositoryTest {

  private static UserSettingsRepository settings;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    settings = TestWiring.settingsRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clear() throws Exception {
    TestDatabaseConfig.clearData();
  }

  @Test
  void missingKeyReturnsTheDefault() {
    assertEquals("RE", settings.getPrefixForDocumentType(1));
  }

  @Test
  void updateSettingInsertsAndThenOverwrites() {
    settings.updateSetting("prefix_offer", "ANG");
    assertEquals("ANG", settings.getSetting("prefix_offer", "AN"));

    settings.updateSetting("prefix_offer", "OF");
    assertEquals("OF", settings.getPrefixForDocumentType(2));
    assertEquals(Map.of("prefix_offer", "OF"), settings.getAllSettings());
  }

  @Test
  void updateDocumentPrefixesWritesOnlyKnownTypes() {
    settings.updateDocumentPrefixes(Map.of(1, "INV", 99, "XX"));

    assertEquals(Map.of("prefix_invoice", "INV"), settings.getAllSettings());
  }
}
