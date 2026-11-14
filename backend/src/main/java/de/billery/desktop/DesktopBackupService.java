package de.billery.desktop;

import de.billery.config.AppConfig;
import de.billery.data.Database;
import de.billery.repository.UserSettingsRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Backups of the desktop app: the H2 database and the uploaded files as one ZIP, made when the app
 * is closed (if switched on) and on request, optionally encrypted, the newest N kept. Restoring
 * stages a chosen backup; {@link PendingRestore} applies it at the next start.
 *
 * <p>The settings live in {@code user_settings} ({@value #KEY_DIRECTORY}, {@value #KEY_KEEP},
 * {@value #KEY_ON_EXIT}, {@value #KEY_PASSWORD}); no endpoint returns the password. Only for the
 * Spring profile {@code desktop}: elsewhere the database is PostgreSQL, backed up with {@code
 * pg_dump} (docs/operations/backup-restore.md).
 */
@Service
@Profile("desktop")
public class DesktopBackupService {

  static final String KEY_DIRECTORY = "backup_directory";
  static final String KEY_KEEP = "backup_keep";
  static final String KEY_ON_EXIT = "backup_on_exit";
  // The name of the setting, not a password.
  static final String KEY_PASSWORD = "backup_password"; // gitleaks:allow

  /** Backups kept unless the user chose another number. */
  static final int DEFAULT_KEEP = 10;

  /** Most backups that may be kept. */
  static final int MAX_KEEP = 100;

  /** Shortest password for an encrypted backup. */
  static final int MIN_PASSWORD_LENGTH = 8;

  private static final Logger LOGGER = Logger.getLogger(DesktopBackupService.class.getName());

  private final JdbcClient jdbc;
  private final UserSettingsRepository settings;
  private final BackupLocations locations;

  /**
   * Uses the database and upload directory of this process.
   *
   * @param jdbc the database
   * @param settings where the backup settings are stored
   */
  @Autowired
  public DesktopBackupService(JdbcClient jdbc, UserSettingsRepository settings) {
    this(jdbc, settings, BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH));
  }

  /**
   * Uses the given locations (tests).
   *
   * @param jdbc the database
   * @param settings where the backup settings are stored
   * @param locations the desktop data; {@code null} when the database is no H2 file
   */
  DesktopBackupService(
      JdbcClient jdbc, UserSettingsRepository settings, BackupLocations locations) {
    this.jdbc = jdbc;
    this.settings = settings;
    this.locations = locations;
  }

  /**
   * The current settings and backups.
   *
   * @return the status shown on the settings page
   * @throws IOException if the backup folder cannot be read
   */
  public BackupStatus status() throws IOException {
    requireFileDatabase();
    return new BackupStatus(
        directory().toString(),
        locations.defaultBackupDirectory().toString(),
        keep(),
        onExit(),
        !password().isEmpty(),
        Files.exists(locations.pendingRestore()),
        list());
  }

  /**
   * Changes the settings.
   *
   * @param directory the backup folder, absolute; empty for the default
   * @param keep how many backups to keep, 1 to {@value #MAX_KEEP}
   * @param onExit whether to back up when the app is closed
   * @param password a new password (encrypts the next backups), {@code null} to keep the current
   *     one, empty to switch encryption off
   * @throws IllegalArgumentException with a German message for invalid values
   */
  public void updateSettings(String directory, int keep, boolean onExit, String password) {
    requireFileDatabase();
    if (keep < 1 || keep > MAX_KEEP) {
      throw new IllegalArgumentException(
          "Die Anzahl der aufbewahrten Sicherungen muss zwischen 1 und " + MAX_KEEP + " liegen.");
    }
    String folder = directory == null ? "" : directory.strip();
    if (!folder.isEmpty()) {
      Path path = Paths.get(folder);
      if (!path.isAbsolute()) {
        throw new IllegalArgumentException("Bitte einen vollständigen Ordnerpfad angeben.");
      }
      try {
        Files.createDirectories(path);
      } catch (IOException | SecurityException e) {
        throw new IllegalArgumentException("Der Ordner kann nicht angelegt werden: " + folder, e);
      }
      if (!Files.isWritable(path)) {
        throw new IllegalArgumentException(
            "In diesen Ordner kann nicht geschrieben werden: " + folder);
      }
    }
    if (password != null && !password.isEmpty() && password.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalArgumentException(
          "Das Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen haben.");
    }
    settings.updateSetting(KEY_DIRECTORY, folder);
    settings.updateSetting(KEY_KEEP, Integer.toString(keep));
    settings.updateSetting(KEY_ON_EXIT, Boolean.toString(onExit));
    if (password != null) {
      settings.updateSetting(KEY_PASSWORD, password);
    }
  }

  /**
   * Makes a backup now.
   *
   * @param reason {@link BackupNames#MANUAL} or {@link BackupNames#ON_EXIT}
   * @return the new backup
   * @throws IOException if it cannot be written
   */
  public synchronized BackupFile backup(String reason) throws IOException {
    requireFileDatabase();
    Path directory = directory();
    Files.createDirectories(directory);
    PendingRestore.deletePartFiles(directory); // left by an interrupted backup
    String password = password();
    boolean encrypted = !password.isEmpty();
    // The name carries the second, and the names must only grow: pruning keeps the newest by the
    // time in the name. A backup within the same second as (or, after a clock change, before) the
    // newest one gets the second after it, so it never replaces or sorts below an older backup.
    LocalDateTime time = LocalDateTime.now().withNano(0);
    for (BackupFile existing : list()) {
      if (!time.isAfter(existing.createdAt())) {
        time = existing.createdAt().plusSeconds(1);
      }
    }
    Path target = directory.resolve(BackupNames.name(time, reason, encrypted));
    Path work = Files.createTempDirectory(locations.appData(), "backup-");
    try {
      Path h2Copy = work.resolve("h2.zip");
      // H2 copies the open database consistently; the path is a literal, quotes doubled.
      // Taken before the copy starts: everything after it may be missing from the backup.
      LocalDateTime snapshotAt = LocalDateTime.now();
      jdbc.sql("BACKUP TO '" + h2Copy.toString().replace("'", "''") + "'").update();
      Path plain = work.resolve("backup.zip");
      writeZip(h2Copy, reason, snapshotAt, plain);
      Path partial = target.resolveSibling(target.getFileName() + ".part");
      if (encrypted) {
        BackupArchive.encrypt(plain, partial, password.toCharArray());
      } else {
        Files.copy(plain, partial, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      PendingRestore.deleteTree(work);
    }
    BackupFile written = BackupFile.fromFile(target);
    prune(directory, written.name());
    LOGGER.info(() -> "Backup written: " + target);
    return written;
  }

  /**
   * The backup made when the app is closed, if switched on and no restore is waiting (the restore
   * makes its own copy of the state before). Never throws: closing the app must not fail.
   */
  public void backupOnExit() {
    try {
      if (locations == null || !onExit() || Files.exists(locations.pendingRestore())) {
        return;
      }
      backup(BackupNames.ON_EXIT);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Backup on exit failed", e);
    }
  }

  /**
   * What a restore would take out of the program: documents issued (or cancelled), payments
   * recorded and document changes after the snapshot of the backup. They stay in the safety copy
   * the restore makes, but they are no longer in the running program.
   *
   * @param snapshotAt when the backup's copy of the database was taken
   * @return the counts
   */
  RestoreLoss lossSince(LocalDateTime snapshotAt) {
    long issued =
        jdbc.sql(
                "SELECT COUNT(*) FROM document_history WHERE action IN ('ISSUED', 'CANCELLED')"
                    + " AND action_date >= :since")
            .param("since", snapshotAt)
            .query(Long.class)
            .single();
    long payments =
        jdbc.sql("SELECT COUNT(*) FROM payment WHERE created_at >= :since")
            .param("since", snapshotAt)
            .query(Long.class)
            .single();
    long changes =
        jdbc.sql("SELECT COUNT(*) FROM document_history WHERE action_date >= :since")
            .param("since", snapshotAt)
            .query(Long.class)
            .single();
    return new RestoreLoss(issued, payments, changes);
  }

  /**
   * Chooses a backup for restoring: decrypts and checks it and stages it; the restore itself
   * happens at the next start ({@link PendingRestore}). If documents were issued, payments recorded
   * or documents changed after the backup, the user must have confirmed that ({@code confirmLoss}):
   * a restore rolls the program back, the safety copy keeps the later state.
   *
   * @param fileName a backup in the backup folder
   * @param password the password of an encrypted backup ({@code null} for a plain one)
   * @param confirmLoss whether the user confirmed what the restore takes out of the program
   * @throws RestoreLossException if there is something to lose and it was not confirmed
   * @throws IllegalArgumentException with a German message for an unknown file, a missing or wrong
   *     password, or a file that is no backup
   * @throws IOException if a file cannot be read or written
   */
  public void stageRestore(String fileName, String password, boolean confirmLoss)
      throws IOException {
    requireFileDatabase();
    if (fileName == null || !BackupNames.isBackup(fileName)) {
      throw new IllegalArgumentException("Unbekannte Sicherung.");
    }
    Path source = directory().resolve(fileName);
    if (!Files.isRegularFile(source)) {
      throw new IllegalArgumentException("Die Sicherung gibt es nicht mehr.");
    }
    Path staged = locations.pendingRestore();
    Path partial = staged.resolveSibling(staged.getFileName() + ".part");
    try {
      if (BackupArchive.isEncrypted(source)) {
        if (password == null || password.isEmpty()) {
          throw new IllegalArgumentException(
              "Diese Sicherung ist verschlüsselt: bitte das Passwort angeben.");
        }
        try {
          BackupArchive.decrypt(source, partial, password.toCharArray());
        } catch (BackupArchive.WrongPasswordException e) {
          throw new IllegalArgumentException(
              "Das Passwort ist falsch, oder die Sicherung ist beschädigt.", e);
        }
      } else {
        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
      }
      try {
        BackupArchive.validate(partial);
      } catch (IOException e) {
        throw new IllegalArgumentException("Die Datei ist keine gültige Billery-Sicherung.", e);
      }
      // The snapshot time inside the backup, not the (possibly later) time in its name. A backup
      // without one counts everything as lost: better a warning too many than one too few.
      LocalDateTime snapshotAt = BackupNames.snapshotAt(BackupArchive.readMeta(partial));
      RestoreLoss loss =
          lossSince(snapshotAt != null ? snapshotAt : LocalDateTime.of(1970, 1, 1, 0, 0));
      if (loss.any() && !confirmLoss) {
        throw new RestoreLossException(loss);
      }
      Properties info = new Properties();
      info.setProperty("backup", fileName);
      info.setProperty("issued", Long.toString(loss.issued()));
      info.setProperty("payments", Long.toString(loss.payments()));
      info.setProperty("changes", Long.toString(loss.changes()));
      try (Writer writer =
          Files.newBufferedWriter(locations.pendingRestoreInfo(), StandardCharsets.UTF_8)) {
        info.store(writer, "Restore chosen at " + LocalDateTime.now().withNano(0));
      }
      Files.move(
          partial, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /**
   * Withdraws a staged restore.
   *
   * @throws IOException if the staged file cannot be deleted
   */
  public void cancelRestore() throws IOException {
    requireFileDatabase();
    Files.deleteIfExists(locations.pendingRestore());
    Files.deleteIfExists(locations.pendingRestoreInfo());
  }

  /** The backups in the backup folder, newest first. */
  List<BackupFile> list() throws IOException {
    Path directory = directory();
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<BackupFile> backups = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file :
          files.filter(f -> BackupNames.isBackup(f.getFileName().toString())).toList()) {
        backups.add(BackupFile.fromFile(file));
      }
    }
    backups.sort(Comparator.comparing(BackupFile::createdAt).reversed());
    return backups;
  }

  /**
   * Deletes the oldest of our backups beyond the number to keep. Other files stay, and so do the
   * safety copies made before a restore: they hold what was entered after the restored backup and
   * fall under the retention duty (GoBD), so only the user deletes them.
   */
  private void prune(Path directory, String justWritten) throws IOException {
    List<BackupFile> backups =
        list().stream()
            .filter(b -> !BackupNames.BEFORE_RESTORE.equals(b.reason()))
            .filter(b -> !b.name().equals(justWritten))
            .toList();
    int others = keep() - 1; // the backup just written is one of those kept
    for (BackupFile old : backups.subList(Math.min(others, backups.size()), backups.size())) {
      Files.deleteIfExists(directory.resolve(old.name()));
    }
  }

  /** The backup ZIP: description, the database from H2's copy, the uploaded files. */
  private void writeZip(Path h2Copy, String reason, LocalDateTime snapshotAt, Path target)
      throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target));
        ZipFile h2 = new ZipFile(h2Copy.toFile())) {
      zip.putNextEntry(new ZipEntry(BackupArchive.META));
      zip.write(BackupNames.meta(reason, snapshotAt).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      ZipEntry database =
          h2.stream()
              .filter(e -> e.getName().endsWith(".mv.db"))
              .findFirst()
              .orElseThrow(() -> new IOException("H2 backup without database file"));
      zip.putNextEntry(new ZipEntry(BackupArchive.DATABASE));
      try (InputStream in = h2.getInputStream(database)) {
        in.transferTo(zip);
      }
      zip.closeEntry();
      for (Path file : PendingRestore.regularFiles(locations.filesDirectory())) {
        zip.putNextEntry(new ZipEntry(BackupArchive.FILES_PREFIX + file.getFileName()));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
  }

  private Path directory() {
    String configured = settings.getSetting(KEY_DIRECTORY, "");
    return configured == null || configured.isBlank()
        ? locations.defaultBackupDirectory()
        : Paths.get(configured);
  }

  private int keep() {
    try {
      int keep = Integer.parseInt(settings.getSetting(KEY_KEEP, Integer.toString(DEFAULT_KEEP)));
      return Math.max(1, Math.min(MAX_KEEP, keep));
    } catch (NumberFormatException e) {
      return DEFAULT_KEEP;
    }
  }

  private boolean onExit() {
    return Boolean.parseBoolean(settings.getSetting(KEY_ON_EXIT, "true"));
  }

  private String password() {
    String password = settings.getSetting(KEY_PASSWORD, "");
    return password == null ? "" : password;
  }

  private void requireFileDatabase() {
    if (locations == null) {
      throw new IllegalStateException("Backups need the desktop H2 file database");
    }
  }

  /**
   * One backup file.
   *
   * @param name the file name
   * @param createdAt when it was made (from the name)
   * @param reason {@link BackupNames#ON_EXIT}, {@link BackupNames#MANUAL} or {@link
   *     BackupNames#BEFORE_RESTORE}
   * @param encrypted whether it is encrypted
   * @param size the size in bytes
   */
  public record BackupFile(
      String name, LocalDateTime createdAt, String reason, boolean encrypted, long size) {

    static BackupFile fromFile(Path file) throws IOException {
      String name = file.getFileName().toString();
      return new BackupFile(
          name,
          BackupNames.time(name),
          BackupNames.reason(name),
          name.endsWith(".enc"),
          Files.size(file));
    }
  }

  /**
   * What a restore would take out of the running program.
   *
   * @param issued documents issued or cancelled after the backup
   * @param payments payments (and reversals) recorded after the backup
   * @param changes document history entries after the backup (all kinds)
   */
  public record RestoreLoss(long issued, long payments, long changes) {
    /**
     * Whether anything would be lost.
     *
     * @return {@code true} if any count is above zero
     */
    public boolean any() {
      return issued > 0 || payments > 0 || changes > 0;
    }
  }

  /** A restore would take documents out of the program and the user has not confirmed it. */
  public static final class RestoreLossException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient RestoreLoss counts;

    RestoreLossException(RestoreLoss loss) {
      super(
          "Seit dieser Sicherung wurden "
              + loss.issued()
              + " Belege ausgestellt oder storniert, "
              + loss.payments()
              + " Zahlungen erfasst und "
              + loss.changes()
              + " Änderungen an Dokumenten gemacht. Sie fehlen nach der Wiederherstellung im"
              + " Programm; erhalten bleiben sie in der Sicherheitskopie, die vorher angelegt und"
              + " nicht automatisch gelöscht wird und wie alle Rechnungen aufzubewahren ist (§ 147 AO).");
      this.counts = loss;
    }

    /**
     * What would be lost.
     *
     * @return the counts
     */
    public RestoreLoss loss() {
      return counts;
    }
  }

  /**
   * What the settings page shows.
   *
   * @param directory the backup folder in use
   * @param defaultDirectory the folder used when none is chosen
   * @param keep how many backups are kept
   * @param onExit whether a backup is made when the app is closed
   * @param encrypted whether new backups are encrypted (a password is set)
   * @param restorePending whether a restore waits for the next start
   * @param backups the backups, newest first
   */
  public record BackupStatus(
      String directory,
      String defaultDirectory,
      int keep,
      boolean onExit,
      boolean encrypted,
      boolean restorePending,
      List<BackupFile> backups) {}
}
