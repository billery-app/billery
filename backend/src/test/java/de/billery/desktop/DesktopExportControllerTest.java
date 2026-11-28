package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.audit.NoAuditTrail;
import de.billery.data.TestDatabaseConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * The desktop app's GoBD export into a folder (#22): only the app window may call it, the folder
 * must be usable, the ZIP lands complete under its final name and never overwrites a file.
 */
class DesktopExportControllerTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
  private static final LocalDate TO = LocalDate.of(2026, 12, 31);

  private DesktopExportController controller;

  @TempDir Path folder;

  @BeforeAll
  static void setUpDatabase() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDownDatabase() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void setUp() throws Exception {
    TestDatabaseConfig.clearData();
    controller =
        new DesktopExportController(
            TestWiring.gobdExportService(), new NoAuditTrail(), new DesktopUiToken("fenster"));
  }

  private DesktopExportController.FolderExportRequest request(String directory) {
    return new DesktopExportController.FolderExportRequest(FROM, TO, false, directory);
  }

  @Test
  void onlyTheAppWindowMayExport() throws Exception {
    for (String token : new String[] {null, "", "falsch"}) {
      assertEquals(
          HttpStatus.FORBIDDEN,
          controller.exportToFolder(token, request(folder.toString())).getStatusCode());
    }
    try (Stream<Path> files = Files.list(folder)) {
      assertEquals(0, files.count(), "nothing written without the token");
    }
  }

  @Test
  void anUnusableFolderIsRefusedWithItsCode() throws Exception {
    for (String directory :
        new String[] {null, "", "relativ/ordner", folder.resolve("fehlt").toString()}) {
      var response = controller.exportToFolder("fenster", request(directory));
      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), String.valueOf(directory));
      assertEquals("export.folderInvalid", response.getBody().get("code"));
    }
    var reversed =
        controller.exportToFolder(
            "fenster",
            new DesktopExportController.FolderExportRequest(TO, FROM, false, folder.toString()));
    assertEquals("export.periodInvalid", reversed.getBody().get("code"));
  }

  @Test
  void writesACompleteZipAndNeverOverwrites() throws Exception {
    var first = controller.exportToFolder("fenster", request(folder.toString()));
    assertEquals(HttpStatus.OK, first.getStatusCode());
    Path written = Path.of((String) first.getBody().get("path"));
    assertEquals(folder.resolve("GoBD-Export_2026-01-01_2026-12-31.zip"), written);
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(written))) {
      boolean index = false;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        index |= entry.getName().endsWith("index.xml");
      }
      assertTrue(index, "the GDPdU index is in the ZIP");
    }

    var second = controller.exportToFolder("fenster", request(folder.toString()));
    Path again = Path.of((String) second.getBody().get("path"));
    assertEquals(folder.resolve("GoBD-Export_2026-01-01_2026-12-31 (2).zip"), again);
    assertTrue(Files.exists(written), "the first export is still there");
    try (Stream<Path> files = Files.list(folder)) {
      assertFalse(
          files.anyMatch(f -> f.getFileName().toString().endsWith(".part")), "no partial file");
    }
  }
}
