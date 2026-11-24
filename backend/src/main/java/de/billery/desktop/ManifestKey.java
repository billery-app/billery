package de.billery.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The key this installation signs {@code backup.json} with (HMAC-SHA256). It lives in its own file
 * in the app-data folder, outside the database and outside every backup: whoever can read or edit a
 * backup cannot use it to sign a forged one. A backup restored on another computer (after losing
 * the old one) is therefore not trusted there, but that new installation has nothing to lose yet.
 */
final class ManifestKey {

  private static final Logger LOGGER = Logger.getLogger(ManifestKey.class.getName());
  private static final int KEY_BYTES = 32;

  private ManifestKey() {}

  /**
   * The key, if this installation has one.
   *
   * @param locations the desktop data
   * @return the key, or an empty array
   */
  static byte[] load(BackupLocations locations) {
    Path file = locations.signingKeyFile();
    if (!Files.exists(file)) {
      return new byte[0];
    }
    try {
      return Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip());
    } catch (IOException | IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Backup signing key not readable; backups are not trusted", e);
      return new byte[0];
    }
  }

  /**
   * The key, created if there is none yet. The file is created readable for the owner only from the
   * start (no moment in which another local user could read it); on a file system without POSIX
   * permissions (Windows) the user's app-data folder protects it. When two starts race, the second
   * reads the key the first wrote.
   *
   * @param locations the desktop data
   * @return the key
   * @throws IOException if it cannot be written
   */
  static byte[] loadOrCreate(BackupLocations locations) throws IOException {
    byte[] key = load(locations);
    if (key.length > 0) {
      return key;
    }
    key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key);
    Path file = locations.signingKeyFile();
    try {
      createOwnerOnly(file);
    } catch (FileAlreadyExistsException e) {
      return load(locations);
    }
    Files.writeString(
        file,
        Base64.getEncoder().encodeToString(key),
        StandardCharsets.US_ASCII,
        StandardOpenOption.TRUNCATE_EXISTING);
    return key;
  }

  private static void createOwnerOnly(Path file) throws IOException {
    try {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      LOGGER.fine("No POSIX permissions for the backup signing key");
      Files.createFile(file);
    }
  }
}
