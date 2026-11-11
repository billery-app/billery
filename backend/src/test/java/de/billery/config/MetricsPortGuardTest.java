package de.billery.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The metrics may never be served on the application's port. */
class MetricsPortGuardTest {

  @Test
  void theApplicationPortIsRefused() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> MetricsPortGuard.check(8080, 8080));
    assertTrue(e.getMessage().contains("BILLERY_METRICS_PORT"), e.getMessage());
  }

  @Test
  void offOrAnotherPortIsFine() {
    assertDoesNotThrow(() -> MetricsPortGuard.check(8080, -1));
    assertDoesNotThrow(() -> MetricsPortGuard.check(8080, 9464));
  }
}
