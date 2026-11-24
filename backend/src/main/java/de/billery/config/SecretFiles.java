package de.billery.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes a small secret file next to the desktop database (the backup signing key, the key of the
 * mail passwords) so that nobody ever sees it incomplete or readable for others: the content goes
 * into a temporary file in the same folder, created readable for the owner only, and only the
 * finished file gets its name (an atomic hard link). An existing file is never replaced: when two
 * writers race, the second gets {@link FileAlreadyExistsException} and reads what the first wrote.
 * On a file system without POSIX permissions (Windows) the user's app-data folder protects the
 * file.
 */
public final class SecretFiles {

  private SecretFiles() {}

  /**
   * Creates the file with the given content, owner-only, complete or not at all.
   *
   * @param file where the secret goes
   * @param content its text (ASCII)
   * @throws FileAlreadyExistsException if the file exists already (another writer was first)
   * @throws IOException if it cannot be written
   */
  public static void createOwnerOnly(Path file, String content) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    Path temp;
    try {
      temp =
          Files.createTempFile(
              dir,
              ".secret-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      temp = Files.createTempFile(dir, ".secret-", ".tmp");
    }
    try {
      Files.writeString(temp, content, StandardCharsets.US_ASCII);
      publish(temp, file);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * Gives the finished file its name without ever replacing an existing one. A hard link is atomic
   * and fails when the name exists (POSIX {@code link}, NTFS), so of two writers exactly one wins;
   * where links are not supported (FAT), a move without {@code REPLACE_EXISTING} is the fallback,
   * which checks and renames in two steps.
   */
  private static void publish(Path temp, Path file) throws IOException {
    try {
      Files.createLink(file, temp);
    } catch (FileAlreadyExistsException e) {
      throw e;
    } catch (UnsupportedOperationException | FileSystemException e) {
      Files.move(temp, file);
    }
  }
}
