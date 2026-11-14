package de.billery.desktop;

import de.billery.data.Database;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Applies a restore that was chosen while the app ran. The database file can only be replaced while
 * nothing has it open, so the restore runs at the next start, before {@code MainApp} opens the
 * database:
 *
 * <ol>
 *   <li>the staged backup is unpacked completely into a working folder (limits and path checks of
 *       {@link BackupArchive#extract}); a failure here leaves the current state untouched;
 *   <li>the current state is saved as a backup of its own, the "safety copy", encrypted when
 *       encryption is on (with the stored public key; no password needed), in the chosen backup
 *       folder; safety copies are never deleted automatically, because they hold what was entered
 *       after the restored backup (GoBD);
 *   <li>the database file and the uploaded files are replaced by the unpacked ones, and the number
 *       counters ({@code document_number_series}) of the replaced database are carried over where
 *       they are higher: a number issued after the backup is never issued again;
 *   <li>the restore is appended to {@code wiederherstellungen.log} in the app-data folder and in
 *       the backup folder next to the safety copy.
 * </ol>
 *
 * The H2 migrations that follow bring a backup of an older version up to date. The staged and the
 * unpacked copies are plain files in the app-data folder, next to the (unencrypted) database file
 * itself; {@link #cleanLeftovers} removes what an interrupted run left behind at every start.
 */
public final class PendingRestore {

  private static final Logger LOGGER = Logger.getLogger(PendingRestore.class.getName());

  /** The log of the restores, in the app-data folder and in the backup folder. */
  static final String RESTORE_LOG = "wiederherstellungen.log";

  private PendingRestore() {}

  /**
   * Removes what an interrupted backup or restore left: working folders in the app-data folder and
   * {@code .part} files in the default backup folder. Runs at every start of the desktop app.
   *
   * @param locations the desktop data, or {@code null} (no H2 file database: nothing to do)
   */
  public static void cleanLeftovers(BackupLocations locations) {
    if (locations == null || !Files.isDirectory(locations.appData())) {
      return;
    }
    try (Stream<Path> entries = Files.list(locations.appData())) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (Files.isDirectory(entry)
            && (name.startsWith("backup-") || "restore-work".equals(name))) {
          deleteTree(entry);
        } else if (name.endsWith(".zip.part")) {
          Files.deleteIfExists(entry);
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not clean up after an interrupted backup", e);
    }
    deletePartFiles(locations.defaultBackupDirectory());
  }

  /**
   * Deletes the {@code .part} files of interrupted backups in a backup folder.
   *
   * @param folder a backup folder
   */
  static void deletePartFiles(Path folder) {
    if (!Files.isDirectory(folder)) {
      return;
    }
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith("billery-") && name.endsWith(".part")) {
          Files.deleteIfExists(file);
        }
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not delete interrupted backups in " + folder, e);
    }
  }

  /**
   * Restores the staged backup, if there is one.
   *
   * @param locations the desktop data, or {@code null} (no H2 file database: nothing to do)
   * @return the safety copy of the state before, or {@code null} if nothing was restored
   * @throws IOException if the restore fails; the staged file stays, the current state is either
   *     untouched or kept in the safety copy
   */
  public static Path applyIfPending(BackupLocations locations) throws IOException {
    if (locations == null || !Files.exists(locations.pendingRestore())) {
      return null;
    }
    Path staged = locations.pendingRestore();
    Path work = locations.restoreWork();
    deleteTree(work);
    BackupArchive.extract(staged, work);

    // What the restore really takes out, counted now from the backup's own snapshot time (writes
    // were refused since it was chosen, so this is what the user confirmed, unless that refusal
    // was bypassed); written to the log next to the confirmed numbers.
    String backupName = readInfo(locations).getProperty("backup", "");
    Map<String, String> settings = currentSettings();
    byte[] manifestKey = manifestKey(settings);
    String actual =
        countSince(
            BackupNames.trustedSnapshot(
                BackupArchive.readMeta(staged), backupName, LocalDateTime.now(), manifestKey));
    // Kept in a file before the swap: if the start fails after it, the next attempt still knows
    // the counters of the replaced database (and keeps the higher of both).
    Map<String, Long> counters = rememberCounters(locations, numberCounters());
    Path safetyCopy = saveCurrentState(locations, settings);
    LOGGER.info(() -> "Restoring a backup; the state before is saved in " + safetyCopy);

    Files.deleteIfExists(locations.databaseFile());
    Files.deleteIfExists(
        locations
            .databaseBase()
            .resolveSibling(locations.databaseBase().getFileName() + ".trace.db"));
    Files.move(work.resolve(BackupArchive.DATABASE), locations.databaseFile());
    Files.createDirectories(locations.filesDirectory());
    for (Path file : regularFiles(locations.filesDirectory())) {
      Files.delete(file);
    }
    for (Path file : regularFiles(work.resolve("files"))) {
      Files.move(
          file,
          BackupArchive.inside(locations.filesDirectory(), file.getFileName().toString()),
          StandardCopyOption.REPLACE_EXISTING);
    }
    deleteTree(work);
    int raised = carryOverNumberCounters(counters);

    appendToLog(locations, safetyCopy, actual + " | Nummernkreise angehoben: " + raised);
    Files.delete(staged);
    Files.deleteIfExists(locations.pendingRestoreInfo());
    Files.deleteIfExists(countersFile(locations));
    LOGGER.info("Backup restored.");
    return safetyCopy;
  }

  /**
   * The backup settings of the database that is about to be replaced (keys and folder for the
   * safety copy). An unreadable or missing database has none.
   */
  static Map<String, String> currentSettings() {
    Map<String, String> settings = new HashMap<>();
    try (Connection conn = Database.getConnection();
        PreparedStatement query =
            conn.prepareStatement(
                "SELECT setting_key, setting_value FROM user_settings"
                    + " WHERE setting_key IN (?, ?, ?, ?)")) {
      query.setString(1, DesktopBackupService.KEY_PUBLIC_KEY);
      query.setString(2, DesktopBackupService.KEY_LOCKED_KEY);
      query.setString(3, DesktopBackupService.KEY_DIRECTORY);
      query.setString(4, DesktopBackupService.KEY_MANIFEST);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          settings.put(rows.getString(1), rows.getString(2));
        }
      }
    } catch (SQLException e) {
      LOGGER.log(Level.WARNING, "Backup settings of the current database not readable", e);
    }
    return settings;
  }

  /**
   * Writes the current database file and uploaded files as a backup ({@link
   * BackupNames#BEFORE_RESTORE}) into the chosen backup folder, or the default one, encrypted when
   * a password is set. The database is closed at this point, so a plain copy of the file is
   * consistent.
   *
   * @param locations the desktop data
   * @param settings the backup settings of the current database
   * @return the safety copy
   * @throws IOException if it cannot be written
   */
  static Path saveCurrentState(BackupLocations locations, Map<String, String> settings)
      throws IOException {
    Optional<BackupArchive.BackupKeys> keys =
        DesktopBackupService.keysOf(
            settings.get(DesktopBackupService.KEY_PUBLIC_KEY),
            settings.get(DesktopBackupService.KEY_LOCKED_KEY));
    boolean encrypted = keys.isPresent();
    Path directory = locations.defaultBackupDirectory();
    String chosen = settings.get(DesktopBackupService.KEY_DIRECTORY);
    if (chosen != null && !chosen.isBlank() && Files.isDirectory(Paths.get(chosen))) {
      directory = Paths.get(chosen);
    }
    Files.createDirectories(directory);
    LocalDateTime time = LocalDateTime.now().withNano(0);
    Path target = directory.resolve(BackupNames.name(time, BackupNames.BEFORE_RESTORE, encrypted));
    while (Files.exists(target)) {
      time = time.plusSeconds(1);
      target = directory.resolve(BackupNames.name(time, BackupNames.BEFORE_RESTORE, encrypted));
    }
    Path plain = locations.appData().resolve("restore-safety-copy.zip.part");
    try {
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plain))) {
        zip.putNextEntry(new ZipEntry(BackupArchive.META));
        zip.write(
            BackupNames.meta(BackupNames.BEFORE_RESTORE, LocalDateTime.now(), manifestKey(settings))
                .getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        if (Files.exists(locations.databaseFile())) {
          zip.putNextEntry(new ZipEntry(BackupArchive.DATABASE));
          Files.copy(locations.databaseFile(), zip);
          zip.closeEntry();
        }
        for (Path file : regularFiles(locations.filesDirectory())) {
          zip.putNextEntry(new ZipEntry(BackupArchive.FILES_PREFIX + file.getFileName()));
          try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
      Path partial = target.resolveSibling(target.getFileName() + ".part");
      if (encrypted) {
        BackupArchive.encrypt(plain, partial, keys.get());
      } else {
        Files.copy(plain, partial, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(plain);
    }
    return target;
  }

  /** The installation's manifest key from the settings, empty if it has none. */
  static byte[] manifestKey(Map<String, String> settings) {
    String stored = settings.get(DesktopBackupService.KEY_MANIFEST);
    try {
      return stored == null || stored.isBlank()
          ? new byte[0]
          : java.util.Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  /** Where the counters of the replaced database are kept until the restore is complete. */
  static Path countersFile(BackupLocations locations) {
    return locations.appData().resolve("restore-pending-counters.properties");
  }

  /** Merges the counters with those remembered by an earlier attempt, keeps them, returns them. */
  static Map<String, Long> rememberCounters(BackupLocations locations, Map<String, Long> counters)
      throws IOException {
    Map<String, Long> merged = new HashMap<>(counters);
    Path file = countersFile(locations);
    Properties stored = new Properties();
    if (Files.exists(file)) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        stored.load(reader);
      }
      for (String key : stored.stringPropertyNames()) {
        merged.merge(key, Long.parseLong(stored.getProperty(key)), Math::max);
      }
    }
    Properties out = new Properties();
    merged.forEach((key, value) -> out.setProperty(key, Long.toString(value)));
    try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      out.store(writer, "Number counters of the database replaced by a restore");
    }
    return merged;
  }

  /**
   * The number counters of the current database, {@code "<type>:<year>"} to the last number given.
   * An unreadable database has none.
   */
  static Map<String, Long> numberCounters() {
    Map<String, Long> counters = new HashMap<>();
    try (Connection conn = Database.getConnection();
        PreparedStatement query =
            conn.prepareStatement(
                "SELECT document_type_id, doc_year, last_number FROM document_number_series");
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        counters.put(rows.getInt(1) + ":" + rows.getInt(2), rows.getLong(3));
      }
    } catch (SQLException e) {
      LOGGER.log(Level.WARNING, "Number counters of the current database not readable", e);
    }
    return counters;
  }

  /**
   * Raises the number counters of the restored database to those of the replaced one where they are
   * lower (GoBD: a document number is never given twice; the numbers given after the backup stay a
   * documented gap).
   *
   * @param counters the counters of the replaced database
   * @return how many counters were raised or added
   * @throws IOException if the restored database cannot be updated
   */
  static int carryOverNumberCounters(Map<String, Long> counters) throws IOException {
    int raised = 0;
    try (Connection conn = Database.getConnection()) {
      for (Map.Entry<String, Long> counter : counters.entrySet()) {
        String[] key = counter.getKey().split(":");
        int type = Integer.parseInt(key[0]);
        int year = Integer.parseInt(key[1]);
        try (PreparedStatement update =
            conn.prepareStatement(
                "UPDATE document_number_series SET last_number = ?"
                    + " WHERE document_type_id = ? AND doc_year = ? AND last_number < ?")) {
          update.setLong(1, counter.getValue());
          update.setInt(2, type);
          update.setInt(3, year);
          update.setLong(4, counter.getValue());
          raised += update.executeUpdate();
        }
        try (PreparedStatement insert =
            conn.prepareStatement(
                "INSERT INTO document_number_series (document_type_id, doc_year, last_number)"
                    + " SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM document_number_series"
                    + " WHERE document_type_id = ? AND doc_year = ?)")) {
          insert.setInt(1, type);
          insert.setInt(2, year);
          insert.setLong(3, counter.getValue());
          insert.setInt(4, type);
          insert.setInt(5, year);
          raised += insert.executeUpdate();
        }
      }
    } catch (SQLException e) {
      throw new IOException("Could not carry the number counters over to the restored database", e);
    }
    return raised;
  }

  /** What the user confirmed when choosing the restore. */
  private static Properties readInfo(BackupLocations locations) throws IOException {
    Properties info = new Properties();
    if (Files.exists(locations.pendingRestoreInfo())) {
      try (Reader reader =
          Files.newBufferedReader(locations.pendingRestoreInfo(), StandardCharsets.UTF_8)) {
        info.load(reader);
      }
    }
    return info;
  }

  /**
   * Counts in the current database what was recorded from {@code since} on, as text for the log. An
   * unreadable database gives "unbekannt".
   */
  static String countSince(LocalDateTime since) {
    try (Connection conn = Database.getConnection()) {
      long issued =
          count(
              conn,
              "SELECT COUNT(*) FROM document_history WHERE action IN ('ISSUED', 'CANCELLED')"
                  + " AND action_date >= ?",
              since);
      long payments = count(conn, "SELECT COUNT(*) FROM payment WHERE created_at >= ?", since);
      long changes =
          count(conn, "SELECT COUNT(*) FROM document_history WHERE action_date >= ?", since);
      return issued
          + " ausgestellte Belege, "
          + payments
          + " Zahlungen, "
          + changes
          + " Änderungen an Dokumenten";
    } catch (SQLException e) {
      LOGGER.log(Level.WARNING, "Could not count what the restore takes out", e);
      return "unbekannt";
    }
  }

  private static long count(Connection conn, String sql, LocalDateTime since) throws SQLException {
    try (PreparedStatement query = conn.prepareStatement(sql)) {
      query.setObject(1, since);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /** One line per restore, never rewritten: time, backup, safety copy, what was entered since. */
  private static void appendToLog(BackupLocations locations, Path safetyCopy, String actual)
      throws IOException {
    Properties info = readInfo(locations);
    String line =
        LocalDateTime.now().withNano(0)
            + " Wiederhergestellt: "
            + info.getProperty("backup", "?")
            + " | Stand davor gesichert in: "
            + safetyCopy
            + " | seit der Sicherung erfasst: "
            + actual
            + " | beim Vormerken bestätigt: "
            + info.getProperty("issued", "?")
            + " ausgestellte Belege, "
            + info.getProperty("payments", "?")
            + " Zahlungen, "
            + info.getProperty("changes", "?")
            + " Änderungen an Dokumenten"
            + System.lineSeparator();
    // In the app-data folder, and next to the safety copy in the backup folder (an external drive
    // or a cloud folder outlives the computer).
    for (Path log : List.of(locations.restoreLog(), safetyCopy.resolveSibling(RESTORE_LOG))) {
      Files.writeString(
          log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }

  /**
   * The files directly in a folder (the upload directory is flat).
   *
   * @param directory the folder
   * @return the regular files, sorted
   * @throws IOException if the folder cannot be read
   */
  static List<Path> regularFiles(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }

  /**
   * Deletes a folder with everything in it; failures are logged.
   *
   * @param root the folder
   */
  static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Could not delete " + root, e);
    }
  }
}
