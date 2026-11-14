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
    service.stageRestore(backup.name(), null, false);
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
    service.stageRestore(old.name(), null, false);

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
            IllegalArgumentException.class, () -> service.stageRestore(backup.name(), null, false));
    assertTrue(missing.getMessage().contains("verschlüsselt"));
    IllegalArgumentException wrong =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.stageRestore(backup.name(), "falsch123", false));
    assertTrue(wrong.getMessage().contains("Passwort ist falsch"));
    assertFalse(Files.exists(locations.pendingRestore()));

    service.stageRestore(backup.name(), "geheim123", false);
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
  void backupsWithinTheSameSecondNeverReplaceOrPruneEachOther() throws Exception {
    service.updateSettings("", 1, true, null);

    BackupFile last = null;
    for (int i = 0; i < 4; i++) {
      BackupFile written = service.backup(BackupNames.MANUAL);
      assertTrue(
          Files.exists(locations.defaultBackupDirectory().resolve(written.name())),
          "the backup just written is kept: " + written.name());
      if (last != null) {
        assertTrue(written.createdAt().isAfter(last.createdAt()), "names only grow");
      }
      last = written;
    }
    assertEquals(
        List.of(last.name()), service.status().backups().stream().map(BackupFile::name).toList());
  }

  @Test
  void theLossIsCountedFromTheSnapshotNotFromTheLaterTimeInTheName() throws Exception {
    // A backup "from the future" in the folder pushes the name of the next one ahead of the clock.
    Path folder = Files.createDirectories(locations.defaultBackupDirectory());
    Files.writeString(
        folder.resolve(
            BackupNames.name(
                java.time.LocalDateTime.now().withNano(0).plusSeconds(30),
                BackupNames.MANUAL,
                false)),
        "x");
    BackupFile backup = service.backup(BackupNames.MANUAL);
    assertTrue(backup.createdAt().isAfter(java.time.LocalDateTime.now().plusSeconds(20)));

    try (var conn = Database.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");
      stmt.execute(
          "INSERT INTO document_history (invoice_id, action, notes) VALUES (1, 'ISSUED', 'x')");
      stmt.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    DesktopBackupService.RestoreLossException refused =
        assertThrows(
            DesktopBackupService.RestoreLossException.class,
            () -> service.stageRestore(backup.name(), null, false));
    assertEquals(1, refused.loss().issued(), "issued after the snapshot, before the name's time");
  }

  @Test
  void aFileWithAnImpossibleDateInItsNameIsIgnored() throws Exception {
    Path folder = Files.createDirectories(locations.defaultBackupDirectory());
    Files.writeString(folder.resolve("billery-2026-99-99_99-99-99-manuell.zip"), "x");

    BackupFile backup = service.backup(BackupNames.MANUAL);

    assertEquals(
        List.of(backup.name()), service.status().backups().stream().map(BackupFile::name).toList());
    assertFalse(BackupNames.isBackup("billery-2026-99-99_99-99-99-manuell.zip"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.stageRestore("billery-2026-99-99_99-99-99-manuell.zip", null, true));
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

    service.stageRestore(backups.get(0).name(), null, false);
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
        () -> service.stageRestore("billery-2026-01-01_10-00-00-manuell.zip", null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.stageRestore("../database.mv.db", null, false));
    assertFalse(Files.exists(locations.pendingRestore()));
  }

  @Test
  void aRestoreThatWouldTakeOutLaterRecordsNeedsAConfirmationAndIsLogged() throws Exception {
    BackupFile backup = service.backup(BackupNames.MANUAL);
    Thread.sleep(1100); // the backup name carries the second; later records come after it
    try (var conn = Database.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");
      stmt.execute(
          "INSERT INTO document_history (invoice_id, action, notes) VALUES (1, 'ISSUED', 'x')");
      stmt.execute(
          "INSERT INTO document_history (invoice_id, action, notes) VALUES (1, 'UPDATED', 'y')");
      stmt.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    DesktopBackupService.RestoreLossException refused =
        assertThrows(
            DesktopBackupService.RestoreLossException.class,
            () -> service.stageRestore(backup.name(), null, false));
    assertEquals(new DesktopBackupService.RestoreLoss(1, 0, 2), refused.loss());
    assertTrue(refused.getMessage().contains("Sicherheitskopie"));
    assertFalse(Files.exists(locations.pendingRestore()), "nothing staged without confirmation");

    service.stageRestore(backup.name(), null, true);
    shutdownDatabase();
    Path safetyCopy = PendingRestore.applyIfPending(locations);

    String log = Files.readString(locations.restoreLog());
    assertTrue(log.contains(backup.name()), log);
    assertTrue(log.contains(safetyCopy.getFileName().toString()), log);
    assertTrue(log.contains("1 ausgestellte Belege, 0 Zahlungen, 2 Änderungen"), log);
    assertEquals(
        log,
        Files.readString(safetyCopy.resolveSibling("wiederherstellungen.log")),
        "the same line next to the safety copy in the backup folder");
  }

  @Test
  void theSafetyCopyIsEncryptedLikeTheBackupsAndNeverPruned() throws Exception {
    service.updateSettings("", 1, true, "geheim123");
    BackupFile backup = service.backup(BackupNames.MANUAL);
    service.stageRestore(backup.name(), "geheim123", true);
    shutdownDatabase();

    Path safetyCopy = PendingRestore.applyIfPending(locations);
    MainAppTestAccess.prepareDatabase();

    assertTrue(safetyCopy.getFileName().toString().endsWith(".zip.enc"), safetyCopy.toString());
    assertTrue(BackupArchive.isEncrypted(safetyCopy));
    // keep = 1: two more backups, the safety copy stays
    service.backup(BackupNames.MANUAL);
    service.backup(BackupNames.MANUAL);
    assertTrue(Files.exists(safetyCopy), "a safety copy is only deleted by the user");
    assertEquals(
        2,
        service.status().backups().size(),
        "the newest backup plus the safety copy: " + service.status().backups());
  }

  @Test
  void whatAnInterruptedBackupLeftIsRemovedAtTheNextStart() throws Exception {
    Files.createDirectories(appData.resolve("backup-123").resolve("x"));
    Files.createDirectories(locations.restoreWork());
    Path backups = Files.createDirectories(locations.defaultBackupDirectory());
    Path part =
        Files.writeString(backups.resolve("billery-2026-01-01_10-00-00-beenden.zip.part"), "x");
    Path foreign = Files.writeString(backups.resolve("notiz.part"), "fremd");

    PendingRestore.cleanLeftovers(locations);

    assertFalse(Files.exists(appData.resolve("backup-123")));
    assertFalse(Files.exists(locations.restoreWork()));
    assertFalse(Files.exists(part));
    assertTrue(Files.exists(foreign), "files that are not ours stay");
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
