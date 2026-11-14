package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** While a restore waits for the next start, the app takes no more writes. */
class RestorePendingFilterTest {

  @TempDir Path appData;

  private int status(RestorePendingFilter filter, String method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  @Test
  void writesAreRefusedOnlyWhileARestoreWaits() throws Exception {
    BackupLocations locations =
        new BackupLocations(appData.resolve("database"), appData.resolve("files"));
    RestorePendingFilter filter = new RestorePendingFilter(locations, new WriteGate());

    assertEquals(200, status(filter, "POST", "/api/invoices"), "no restore waiting");

    Files.writeString(locations.pendingRestore(), "x");
    assertEquals(409, status(filter, "POST", "/api/invoices"));
    assertEquals(409, status(filter, "PUT", "/api/customers/3"));
    assertEquals(409, status(filter, "PATCH", "/api/invoices/3/status"));
    assertEquals(409, status(filter, "DELETE", "/api/articles/3"));
    assertEquals(200, status(filter, "GET", "/api/invoices/page"), "reading stays possible");
    assertEquals(200, status(filter, "DELETE", "/api/desktop/backups/restore"), "withdrawing too");
    assertEquals(200, status(filter, "POST", "/api/desktop/shutdown"), "and closing the app");

    MockHttpServletResponse refused = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/invoices"), refused, new MockFilterChain());
    assertTrue(refused.getContentAsString().contains("Wiederherstellung ist vorgemerkt"));
  }

  @Test
  void withoutAFileDatabaseNothingIsRefused() throws Exception {
    assertEquals(
        200, status(new RestorePendingFilter(null, new WriteGate()), "POST", "/api/invoices"));
  }

  @Test
  void aWriteHoldsTheGateWhileItRunsAndReleasesItAfterwards() throws Exception {
    BackupLocations locations =
        new BackupLocations(appData.resolve("database"), appData.resolve("files"));
    WriteGate gate = new WriteGate();
    RestorePendingFilter filter = new RestorePendingFilter(locations, gate);
    boolean[] heldDuringTheWrite = {false};

    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/invoices"),
        new MockHttpServletResponse(),
        (req, res) -> {
          try {
            heldDuringTheWrite[0] = !gate.awaitIdle(java.time.Duration.ZERO);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    assertTrue(heldDuringTheWrite[0], "a restore chosen now would wait for this write");
    assertTrue(gate.awaitIdle(java.time.Duration.ZERO), "released afterwards");
  }
}
