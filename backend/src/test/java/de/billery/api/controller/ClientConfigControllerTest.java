package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.data.Database;
import de.billery.data.TestDatabaseConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ClientConfigControllerTest {

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
        Map.of("mode", "selfhost", "desktop", true), new ClientConfigController(desktop).config());
    assertEquals(
        Map.of("mode", "selfhost", "desktop", false),
        new ClientConfigController(withoutShell).config(),
        "the JAR without the Tauri shell has no window token");
  }

  @Test
  void aServerOrAnInMemoryDatabaseHasNoDesktopBackups() {
    MockEnvironment server = new MockEnvironment();
    MockEnvironment desktopInMemory = new MockEnvironment();
    desktopInMemory.setActiveProfiles("desktop");
    Database.setDbPath("jdbc:h2:mem:e2e;MODE=MariaDB;DB_CLOSE_DELAY=-1");

    assertEquals(
        Map.of("mode", "selfhost", "desktop", false), new ClientConfigController(server).config());
    assertEquals(
        Map.of("mode", "selfhost", "desktop", false),
        new ClientConfigController(desktopInMemory).config());
  }
}
