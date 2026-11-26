package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.config.DemoMode;
import de.billery.data.Database;
import de.billery.data.TestDatabaseConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ClientConfigControllerTest {

  private static final DemoMode NO_DEMO = new DemoMode(false);
  private static final de.billery.config.AppVersion VERSION =
      new de.billery.config.AppVersion("0.3.0");

  @TempDir Path appData;

  @AfterEach
  void resetDatabase() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void theDesktopAppWithItsFileDatabaseAndWindowTokenShowsTheBackups() {
    MockEnvironment desktop = new MockEnvironment().withProperty("DESKTOP_UI_TOKEN", "fenster");
    desktop.setActiveProfiles("desktop");
    MockEnvironment withoutShell = new MockEnvironment();
    withoutShell.setActiveProfiles("desktop");
    Database.setDbPath("jdbc:h2:file:" + appData.resolve("database") + ";MODE=MariaDB");

    assertEquals(
        Map.of("mode", "selfhost", "desktop", true, "demo", false, "version", "0.3.0"),
        new ClientConfigController(desktop, NO_DEMO, VERSION).config());
    assertEquals(
        Map.of("mode", "selfhost", "desktop", false, "demo", false, "version", "0.3.0"),
        new ClientConfigController(withoutShell, NO_DEMO, VERSION).config(),
        "the JAR without the Tauri shell has no window token");
  }

  @Test
  void aServerOrAnInMemoryDatabaseHasNoDesktopBackups() {
    MockEnvironment server = new MockEnvironment();
    MockEnvironment desktopInMemory = new MockEnvironment();
    desktopInMemory.setActiveProfiles("desktop");
    Database.setDbPath("jdbc:h2:mem:e2e;MODE=MariaDB;DB_CLOSE_DELAY=-1");

    assertEquals(
        Map.of("mode", "selfhost", "desktop", false, "demo", false, "version", "0.3.0"),
        new ClientConfigController(server, NO_DEMO, VERSION).config());
    assertEquals(
        Map.of("mode", "selfhost", "desktop", false, "demo", false, "version", "0.3.0"),
        new ClientConfigController(desktopInMemory, NO_DEMO, VERSION).config());
  }

  @Test
  void theDemoSaysSoAndRefusesTheMailToCustomers() {
    DemoMode demo = new DemoMode(true);
    Database.setDbPath("jdbc:h2:mem:e2e;MODE=MariaDB;DB_CLOSE_DELAY=-1");

    assertEquals(
        Map.of("mode", "selfhost", "desktop", false, "demo", true, "version", "0.3.0"),
        new ClientConfigController(new MockEnvironment(), demo, VERSION).config());
    de.billery.service.DocumentStateException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            de.billery.service.DocumentStateException.class, () -> demo.refuse("Der Mailversand"));
    assertEquals("Der Mailversand ist in der Demo abgeschaltet.", refused.getMessage());
    NO_DEMO.refuse("Der Mailversand"); // outside the demo nothing is refused
  }

  @Test
  void aBuildWithoutAReleaseVersionSaysDev() {
    assertEquals("dev", new de.billery.config.AppVersion("").version());
    assertEquals("dev", new de.billery.config.AppVersion(null).version());
    assertEquals("0.2.0", new de.billery.config.AppVersion(" 0.2.0 ").version());
  }
}
