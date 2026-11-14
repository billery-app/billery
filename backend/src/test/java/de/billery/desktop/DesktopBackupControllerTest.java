package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Only the app's own window may use the backup endpoints: other programs on the computer reach
 * 127.0.0.1 too, and a scheduled restore replaces the database file at the next start.
 */
class DesktopBackupControllerTest {

  private final DesktopBackupController controller = new DesktopBackupController(null, "fenster");
  private final DesktopBackupController withoutShell = new DesktopBackupController(null, "");

  @Test
  void everyEndpointRefusesARequestWithoutTheWindowToken() throws Exception {
    var restore = new DesktopBackupController.RestoreRequest("billery-x.zip", null, true);
    var settings = new DesktopBackupController.SettingsRequest("/tmp", 1, false, "");

    for (String token : new String[] {null, "", "falsch", "fenster "}) {
      assertEquals(HttpStatus.FORBIDDEN, controller.status(token).getStatusCode());
      assertEquals(
          HttpStatus.FORBIDDEN, controller.updateSettings(token, settings).getStatusCode());
      assertEquals(HttpStatus.FORBIDDEN, controller.backupNow(token).getStatusCode());
      assertEquals(HttpStatus.FORBIDDEN, controller.restore(token, restore).getStatusCode());
      assertEquals(HttpStatus.FORBIDDEN, controller.cancelRestore(token).getStatusCode());
    }
  }

  @Test
  void withoutAConfiguredTokenNothingIsAllowed() throws Exception {
    assertEquals(HttpStatus.FORBIDDEN, withoutShell.status("").getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, withoutShell.status(null).getStatusCode());
  }
}
