package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.MainAppTestAccess;
import de.billery.TestWiring;
import de.billery.data.Database;
import de.billery.data.TestDatabaseConfig;
import de.billery.desktop.DesktopBackupService.BackupFile;
import de.billery.repository.UserSettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backups of the desktop app against a real H2 file database (the URL shape of the Tauri shell): a
 * backup holds the database and the uploaded files, a restore is staged and applied at the next
 * start with a safety copy of the state before, encrypted backups need their password, old backups
 * are pruned, and a backup of an older schema version is brought up to date by the migrations.
 */
class DesktopBackupServiceTest {

  @TempDir Path appData;

  private Path files;
  private BackupLocations locations;
  private UserSettingsRepository settings;
  private DesktopBackupService service;

  @BeforeEach
  void freshDesktopDatabase() throws Exception {
    Database.setDbPath("jdbc:h2:file:" + appData.resolve("database") + ";MODE=MariaDB");
    MainAppTestAccess.prepareDatabase();
    files = Files.createDirectories(appData.resolve("files"));
    locations = new BackupLocations(appData.resolve("database"), files);
    settings = new UserSettingsRepository(TestWiring.jdbcClient());
    service = new DesktopBackupService(TestWiring.jdbcClient(), settings, locations);
  }

  @AfterEach
  void closeDatabase() {
    shutdownDatabase();
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void aRestoreBringsBackTheDatabaseAndTheFilesAndKeepsTheStateBefore() throws Exception {
    settings.updateSetting("test_marker", "vorher");
    Files.writeString(files.resolve("logo.png"), "altes Logo");
    BackupFile backup = service.backup(BackupNames.MANUAL);

    settings.updateSetting("test_marker", "nachher");
    Files.delete(files.resolve("logo.png"));
    Files.writeString(files.resolve("neu.png"), "neues Logo");
    service.stageRestore(backup.name(), null);
    assertTrue(service.status().restorePending());

    shutdownDatabase(); // the app is closed
    Path safetyCopy = PendingRestore.applyIfPending(locations);
    MainAppTestAccess.prepareDatabase(); // the next start

    assertEquals("vorher", settings.getSetting("test_marker", null));
    assertEquals(List.of("logo.png"), names(files));
    assertEquals("altes Logo", Files.readString(files.resolve("logo.png")));
    assertFalse(Files.exists(locations.pendingRestore()));
    assertTrue(safetyCopy.getFileName().toString().contains(BackupNames.BEFORE_RESTORE));
    try (ZipFile zip = new ZipFile(safetyCopy.toFile())) {
      assertNotNull(zip.getEntry(BackupArchive.DATABASE), "the state before is kept");
      assertNotNull(zip.getEntry(BackupArchive.FILES_PREFIX + "neu.png"));
    }
  }

  @Test
  void aBackupOfAnOlderSchemaIsBroughtUpToDateAfterTheRestore() throws Exception {
    try (var conn = Database.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE owner DROP COLUMN url"); // as in an older desktop version
    }
    BackupFile old = service.backup(BackupNames.MANUAL);
    service.stageRestore(old.name(), null);

    shutdownDatabase();
    PendingRestore.applyIfPending(locations);
    MainAppTestAccess.prepareDatabase();

    TestWiring.ownerRepository().save(MainAppTestAccess.owner());
    assertEquals("https://example.de", TestWiring.ownerRepository().findOwner().getUrl());
  }

  @Test
  void anEncryptedBackupNeedsItsPassword() throws Exception {
    service.updateSettings("", 10, true, "geheim123");
    BackupFile backup = service.backup(BackupNames.MANUAL);

    assertTrue(backup.encrypted());
    assertTrue(
        BackupArchive.isEncrypted(locations.defaultBackupDirectory().resolve(backup.name())));
    assertTrue(service.status().encrypted());
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class, () -> service.stageRestore(backup.name(), null));
    assertTrue(missing.getMessage().contains("verschlüsselt"));
    IllegalArgumentException wrong =
        assertThrows(
            IllegalArgumentException.class, () -> service.stageRestore(backup.name(), "falsch123"));
    assertTrue(wrong.getMessage().contains("Passwort ist falsch"));
    assertFalse(Files.exists(locations.pendingRestore()));

    service.stageRestore(backup.name(), "geheim123");
    assertTrue(Files.exists(locations.pendingRestore()));
  }

  @Test
  void onlyTheNewestBackupsAreKeptAndOtherFilesInTheFolderStay() throws Exception {
    Path folder = Files.createDirectories(appData.resolve("cloud"));
    Files.writeString(folder.resolve("steuer.pdf"), "nicht von Billery");
    service.updateSettings(folder.toString(), 2, true, null);

    BackupFile first = service.backup(BackupNames.MANUAL);
    BackupFile second = service.backup(BackupNames.MANUAL);
    BackupFile third = service.backup(BackupNames.MANUAL);

    List<String> names = names(folder);
    assertEquals(3, names.size(), names.toString());
    assertTrue(names.contains("steuer.pdf"));
    assertFalse(names.contains(first.name()), "the oldest is gone");
    assertTrue(names.contains(second.name()));
    assertTrue(names.contains(third.name()));
    assertEquals(third.name(), service.status().backups().get(0).name(), "newest first");
  }

  @Test
  void theBackupOnExitFollowsTheSettingAndSkipsAWaitingRestore() throws Exception {
    service.updateSettings("", 10, false, null);
    service.backupOnExit();
    assertTrue(service.status().backups().isEmpty(), "switched off");

    service.updateSettings("", 10, true, null);
    service.backupOnExit();
    List<BackupFile> backups = service.status().backups();
    assertEquals(1, backups.size());
    assertEquals(BackupNames.ON_EXIT, backups.get(0).reason());

    service.stageRestore(backups.get(0).name(), null);
    service.backupOnExit();
    assertEquals(1, service.status().backups().size(), "the restore makes its own copy");
  }

  @Test
  void invalidSettingsAreRefusedInGerman() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateSettings("relativ", 10, true, null))
            .getMessage()
            .contains("vollständigen Ordnerpfad"));
    assertThrows(IllegalArgumentException.class, () -> service.updateSettings("", 0, true, null));
    assertThrows(IllegalArgumentException.class, () -> service.updateSettings("", 101, true, null));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> service.updateSettings("", 10, true, "kurz"))
            .getMessage()
            .contains("mindestens 8"));
  }

  @Test
  void aFileThatIsNoBackupOrNotOneOfOursCannotBeRestored() throws Exception {
    Path folder = Files.createDirectories(locations.defaultBackupDirectory());
    Files.writeString(folder.resolve("billery-2026-01-01_10-00-00-manuell.zip"), "kein zip");

    assertThrows(
        IllegalArgumentException.class,
        () -> service.stageRestore("billery-2026-01-01_10-00-00-manuell.zip", null));
    assertThrows(
        IllegalArgumentException.class, () -> service.stageRestore("../database.mv.db", null));
    assertFalse(Files.exists(locations.pendingRestore()));
  }

  private void shutdownDatabase() {
    try (var conn = Database.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("SHUTDOWN");
    } catch (Exception e) {
      // already closed
    }
  }

  private static List<String> names(Path folder) throws Exception {
    List<String> names = new ArrayList<>();
    try (var list = Files.list(folder)) {
      list.forEach(p -> names.add(p.getFileName().toString()));
    }
    names.sort(null);
    return names;
  }
}
