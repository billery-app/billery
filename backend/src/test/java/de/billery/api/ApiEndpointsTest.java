package de.billery.api;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.api.controller.DesktopController;
import de.billery.api.controller.HealthController;
import de.billery.data.DatabaseException;
import de.billery.pdf.MissingConfigurationException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The small API classes: error mapping, health, and the desktop shutdown guard. */
class ApiEndpointsTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void databaseErrorsBecome500WithoutLeakingSqlDetails() {
    var response =
        handler.handleDatabase(
            new DatabaseException("save invoice", new SQLException("FK violation on table x")));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertFalse(response.getBody().get("message").contains("FK violation"));
  }

  @Test
  void missingConfigurationBecomes409WithTheUserFacingMessage() {
    var response =
        handler.handleMissingConfiguration(
            new MissingConfigurationException("Bitte zuerst die Firmendaten anlegen"));

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals("Bitte zuerst die Firmendaten anlegen", response.getBody().get("message"));
  }

  @Test
  void healthReportsUp() {
    // Liveness never touches the database (HealthControllerTest covers readiness).
    var body = new HealthController(null).health().getBody();

    assertEquals("UP", body.get("status"));
    assertTrue(body.containsKey("instance"));
  }

  @Test
  void desktopShutdownIsRefusedWithoutTheLaunchToken() {
    // No DESKTOP_SHUTDOWN_TOKEN in the test environment: every request must be refused,
    // including one that sends an empty token.
    DesktopController controller = new DesktopController(null, null);

    assertEquals(HttpStatus.FORBIDDEN, controller.shutdown(null).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, controller.shutdown("").getStatusCode());
  }
}
