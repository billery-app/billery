package de.billery.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The file format of a desktop backup.
 *
 * <p>A backup is a ZIP with three kinds of entries: {@value #META} (format and time), {@value
 * #DATABASE} (a consistent copy of the H2 database file, made with H2's {@code BACKUP TO}) and the
 * uploaded files under {@value #FILES_PREFIX}. An encrypted backup is that ZIP encrypted with
 * AES-256-GCM, the key derived from the password with PBKDF2-HMAC-SHA256; the file starts with
 * {@link #ENCRYPTED_MAGIC}, the salt and the IV. GCM authenticates the content, so a wrong password
 * and a damaged file are both refused instead of restoring garbage.
 */
public final class BackupArchive {

  /** The entry with the format version and the creation time. */
  public static final String META = "backup.json";

  /** The entry with the H2 database file. */
  public static final String DATABASE = "database.mv.db";

  /** The prefix of the uploaded files. */
  public static final String FILES_PREFIX = "files/";

  /** First bytes of an encrypted backup. */
  static final byte[] ENCRYPTED_MAGIC = "BILLERY-BACKUP-1\n".getBytes(StandardCharsets.US_ASCII);

  private static final int SALT_BYTES = 16;
  private static final int IV_BYTES = 12;
  private static final int KEY_BITS = 256;
  private static final int TAG_BITS = 128;
  private static final int PBKDF2_ITERATIONS = 310_000;

  /** A file name inside {@value #FILES_PREFIX}: one plain name, no path. */
  private static final Pattern PLAIN_NAME = Pattern.compile("[^/\\\\]+");

  private static final SecureRandom RANDOM = new SecureRandom();

  private BackupArchive() {}

  /**
   * Whether the file is an encrypted backup (by its first bytes).
   *
   * @param file the backup file
   * @return {@code true} if it starts with {@link #ENCRYPTED_MAGIC}
   * @throws IOException if the file cannot be read
   */
  public static boolean isEncrypted(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return Arrays.equals(in.readNBytes(ENCRYPTED_MAGIC.length), ENCRYPTED_MAGIC);
    }
  }

  /**
   * Encrypts a plain backup ZIP.
   *
   * @param plainZip the ZIP
   * @param target the encrypted file to write
   * @param password the password, at least one character
   * @throws IOException if a file cannot be read or written
   */
  public static void encrypt(Path plainZip, Path target, char... password) throws IOException {
    byte[] salt = new byte[SALT_BYTES];
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(salt);
    RANDOM.nextBytes(iv);
    Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv);
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target));
        InputStream in = new BufferedInputStream(Files.newInputStream(plainZip))) {
      out.write(ENCRYPTED_MAGIC);
      out.write(salt);
      out.write(iv);
      try (CipherOutputStream encrypted = new CipherOutputStream(new KeepOpen(out), cipher)) {
        in.transferTo(encrypted);
      }
    }
  }

  /**
   * Decrypts an encrypted backup into a plain ZIP.
   *
   * @param encrypted the encrypted backup
   * @param target the ZIP to write
   * @param password the password
   * @throws WrongPasswordException if the password is wrong or the file was changed
   * @throws IOException if a file cannot be read or written, or the file is no encrypted backup
   */
  public static void decrypt(Path encrypted, Path target, char... password) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(encrypted))) {
      if (!Arrays.equals(in.readNBytes(ENCRYPTED_MAGIC.length), ENCRYPTED_MAGIC)) {
        throw new IOException("Not an encrypted backup");
      }
      byte[] salt = in.readNBytes(SALT_BYTES);
      byte[] iv = in.readNBytes(IV_BYTES);
      if (salt.length != SALT_BYTES || iv.length != IV_BYTES) {
        throw new IOException("Encrypted backup is truncated");
      }
      Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv);
      try (OutputStream out = Files.newOutputStream(target);
          CipherInputStream plain = new CipherInputStream(in, cipher)) {
        plain.transferTo(out);
      } catch (IOException e) {
        // CipherInputStream reports the failed GCM tag as an IOException with this cause.
        if (e.getCause() instanceof AEADBadTagException) {
          throw new WrongPasswordException(e);
        }
        throw e;
      }
    }
  }

  /**
   * Checks that a plain ZIP is a backup: it has the database and the description, and every
   * uploaded file is a plain name (no path that could leave the upload folder).
   *
   * @param plainZip the ZIP
   * @throws IOException if it is no backup or cannot be read
   */
  public static void validate(Path plainZip) throws IOException {
    boolean database = false;
    boolean meta = false;
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(plainZip))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String name = entry.getName();
        if (DATABASE.equals(name)) {
          database = true;
        } else if (META.equals(name)) {
          meta = true;
        } else if (fileName(name) == null) {
          throw new IOException("Unexpected entry in backup: " + name);
        }
      }
    }
    if (!database || !meta) {
      throw new IOException("Not a Billery backup");
    }
  }

  /**
   * The name of an uploaded file entry, or {@code null} if the entry is none (or would leave the
   * upload folder: a path, {@code ..}).
   *
   * @param entryName the ZIP entry name
   * @return the plain file name, or null
   */
  static String fileName(String entryName) {
    if (!entryName.startsWith(FILES_PREFIX)) {
      return null;
    }
    String name = entryName.substring(FILES_PREFIX.length());
    if (!PLAIN_NAME.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
      return null;
    }
    return name;
  }

  private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] iv) {
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      byte[] key =
          factory
              .generateSecret(new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS))
              .getEncoded();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
      return cipher;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM / PBKDF2 not available", e);
    }
  }

  /** Lets the cipher stream finish without closing the file stream it writes into. */
  private static final class KeepOpen extends java.io.FilterOutputStream {
    private KeepOpen(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }

  /** The password of an encrypted backup is wrong, or the file was changed. */
  public static final class WrongPasswordException extends IOException {
    private static final long serialVersionUID = 1L;

    WrongPasswordException(Throwable cause) {
      super("Wrong password or damaged backup", cause);
    }
  }
}
