package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Liveness and readiness. */
class HealthControllerTest {

  @Test
  void readyWhenTheDatabaseAnswers() {
    HealthController controller =
        new HealthController(new DriverManagerDataSource("jdbc:h2:mem:health;MODE=MariaDB"));

    ResponseEntity<Map<String, String>> ready = controller.ready();

    assertEquals(HttpStatus.OK, ready.getStatusCode());
    assertEquals(Map.of("status", "UP"), ready.getBody());
  }

  @Test
  void notReadyButStillAliveWhenTheDatabaseIsGone() {
    HealthController controller =
        new HealthController(
            new DriverManagerDataSource("jdbc:h2:file:/nonexistent/dir/db;IFEXISTS=TRUE"));

    ResponseEntity<Map<String, String>> ready = controller.ready();

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ready.getStatusCode());
    assertEquals(Map.of("status", "DOWN"), ready.getBody(), "no details for an open endpoint");
    assertEquals(
        HttpStatus.OK, controller.health().getStatusCode(), "liveness ignores the database");
  }

  @Test
  void aHangingDatabaseIsReportedWithinSecondsNotAfterThePoolTimeout() {
    DriverManagerDataSource hanging =
        new DriverManagerDataSource() {
          @Override
          public Connection getConnection() {
            try {
              Thread.sleep(60_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("never reached in time");
          }
        };
    HealthController controller = new HealthController(hanging);

    long start = System.nanoTime();
    ResponseEntity<Map<String, String>> first = controller.ready();
    ResponseEntity<Map<String, String>> second = controller.ready();
    long seconds = (System.nanoTime() - start) / 1_000_000_000L;

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, first.getStatusCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, second.getStatusCode());
    assertTrue(seconds < 10, "took " + seconds + " s");
  }
}
