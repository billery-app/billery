package de.billery.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Applies a restore that was chosen while the app ran. The database file can only be replaced while
 * nothing has it open, so the restore runs at the next start, before {@code MainApp} opens the
 * database: first the current state is saved as a backup of its own (the "safety copy"), then the
 * database file and the uploaded files are replaced by the backup's, then the staged file is
 * removed. The H2 migrations that follow bring a backup of an older version up to date.
 */
public final class PendingRestore {

  private static final Logger LOGGER = Logger.getLogger(PendingRestore.class.getName());

  private PendingRestore() {}

  /**
   * Restores the staged backup, if there is one.
   *
   * @param locations the desktop data, or {@code null} (no H2 file database: nothing to do)
   * @return the safety copy of the state before, or {@code null} if nothing was restored
   * @throws IOException if the restore fails; the staged file and the safety copy stay
   */
  public static Path applyIfPending(BackupLocations locations) throws IOException {
    if (locations == null || !Files.exists(locations.pendingRestore())) {
      return null;
    }
    Path staged = locations.pendingRestore();
    BackupArchive.validate(staged);

    Path safetyCopy = saveCurrentState(locations);
    LOGGER.info(() -> "Restoring a backup; the state before is saved in " + safetyCopy);

    Files.deleteIfExists(locations.databaseFile());
    Files.deleteIfExists(
        locations
            .databaseBase()
            .resolveSibling(locations.databaseBase().getFileName() + ".trace.db"));
    Files.createDirectories(locations.filesDirectory());
    try (Stream<Path> files = Files.list(locations.filesDirectory())) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Files.delete(file);
      }
    }
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(staged))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (BackupArchive.DATABASE.equals(entry.getName())) {
          Files.copy(zip, locations.databaseFile(), StandardCopyOption.REPLACE_EXISTING);
        } else {
          String name = BackupArchive.fileName(entry.getName());
          if (name != null) {
            Files.copy(
                zip, locations.filesDirectory().resolve(name), StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
    Files.delete(staged);
    LOGGER.info("Backup restored.");
    return safetyCopy;
  }

  /**
   * Writes the current database file and uploaded files as a backup into the default backup folder.
   * The database is closed at this point, so a plain copy of the file is consistent.
   */
  static Path saveCurrentState(BackupLocations locations) throws IOException {
    Path directory = locations.defaultBackupDirectory();
    Files.createDirectories(directory);
    Path target =
        directory.resolve(BackupNames.name(LocalDateTime.now(), BackupNames.BEFORE_RESTORE, false));
    Path partial = target.resolveSibling(target.getFileName() + ".part");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(partial))) {
      zip.putNextEntry(new ZipEntry(BackupArchive.META));
      zip.write(BackupNames.meta(BackupNames.BEFORE_RESTORE).getBytes(StandardCharsets.UTF_8));
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
    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    return target;
  }

  /** The files directly in the upload directory (it is flat). */
  static List<Path> regularFiles(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }
}
