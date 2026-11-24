package de.billery.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes a small secret file next to the desktop database (the backup signing key, the key of the
 * mail passwords) so that nobody ever sees it incomplete or readable for others: the content goes
 * into a temporary file in the same folder, created readable for the owner only, and only the
 * finished file is moved to its name. An existing file is never replaced: when two writers race,
 * the second gets {@link FileAlreadyExistsException} and reads what the first wrote. On a file
 * system without POSIX permissions (Windows) the user's app-data folder protects the file.
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
      // Without REPLACE_EXISTING: an existing key is never overwritten.
      Files.move(temp, file);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
