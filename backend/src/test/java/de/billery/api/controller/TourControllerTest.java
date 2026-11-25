package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.TestWiring;
import de.billery.data.TestDatabaseConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link TourController}: the tour state per installation, kept in {@code user_settings}. */
class TourControllerTest {

  private TourController tour;

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
    tour = new TourController(TestWiring.settingsRepository(), TestWiring.invoiceRepository());
  }

  @Test
  void aFreshInstallationHasNotSeenTheTourAndHasNoDocuments() {
    assertEquals(Map.of("seen", false, "everySession", false, "hasDocuments", false), tour.state());
  }

  @Test
  void seenAndEverySessionAreStoredSeparately() {
    assertEquals(
        Map.of("seen", true, "everySession", false, "hasDocuments", false),
        tour.update(Map.of("seen", true)));
    assertEquals(
        Map.of("seen", true, "everySession", true, "hasDocuments", false),
        tour.update(Map.of("everySession", true)));
    Map<String, Boolean> nothing = new HashMap<>();
    nothing.put("seen", null);
    assertEquals(
        Map.of("seen", true, "everySession", true, "hasDocuments", false),
        tour.update(nothing),
        "a missing value changes nothing");
  }
}
