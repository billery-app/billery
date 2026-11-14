package de.billery.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the desktop app keeps its data, derived from the H2 URL the Tauri shell passes ({@code
 * jdbc:h2:file:<app-data>/database;MODE=MariaDB}) and {@code FILES_PATH} ({@code
 * <app-data>/files}).
 *
 * @param databaseBase the database path without extension ({@code <app-data>/database})
 * @param filesDirectory the upload directory
 */
public record BackupLocations(Path databaseBase, Path filesDirectory) {

  private static final Pattern H2_FILE_URL = Pattern.compile("^jdbc:h2:file:([^;]+)(;.*)?$");

  /**
   * The locations of an H2 file database, or {@code null} for any other database (PostgreSQL, an
   * in-memory H2): those have no backup of this kind.
   *
   * @param jdbcUrl the database URL
   * @param filesDirectory the upload directory
   * @return the locations, or null
   */
  public static BackupLocations forDatabase(String jdbcUrl, Path filesDirectory) {
    if (jdbcUrl == null) {
      return null;
    }
    Matcher matcher = H2_FILE_URL.matcher(jdbcUrl);
    if (!matcher.matches()) {
      return null;
    }
    return new BackupLocations(
        Paths.get(matcher.group(1)).toAbsolutePath().normalize(),
        filesDirectory.toAbsolutePath().normalize());
  }

  /**
   * The database file H2 writes.
   *
   * @return {@code <base>.mv.db}
   */
  public Path databaseFile() {
    return databaseBase.resolveSibling(databaseBase.getFileName() + ".mv.db");
  }

  /**
   * The folder of the database: the app-data folder of the desktop app.
   *
   * @return the folder
   */
  public Path appData() {
    Path parent = databaseBase.getParent();
    return parent != null ? parent : Paths.get(".").toAbsolutePath().normalize();
  }

  /**
   * Where backups go unless the user chose another folder.
   *
   * @return {@code <app-data>/backups}
   */
  public Path defaultBackupDirectory() {
    return appData().resolve("backups");
  }

  /**
   * A backup chosen for restoring, decrypted and checked, applied on the next start.
   *
   * @return {@code <app-data>/restore-pending.zip}
   */
  public Path pendingRestore() {
    return appData().resolve("restore-pending.zip");
  }

  /**
   * What the user confirmed when choosing the restore (backup, what is lost), for the log.
   *
   * @return {@code <app-data>/restore-pending.properties}
   */
  public Path pendingRestoreInfo() {
    return appData().resolve("restore-pending.properties");
  }

  /**
   * Where a restore is unpacked before anything of the current state is touched.
   *
   * @return {@code <app-data>/restore-work}
   */
  public Path restoreWork() {
    return appData().resolve("restore-work");
  }

  /**
   * The append-only log of every restore (GoBD: a restore rolls documents back, so it is recorded
   * outside the database it replaces).
   *
   * @return {@code <app-data>/wiederherstellungen.log}
   */
  public Path restoreLog() {
    return appData().resolve("wiederherstellungen.log");
  }
}
