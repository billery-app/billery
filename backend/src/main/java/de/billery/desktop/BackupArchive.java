package de.billery.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * The file format of a desktop backup.
 *
 * <p>A backup is a ZIP with three kinds of entries: {@value #META} (format and time), {@value
 * #DATABASE} (a consistent copy of the H2 database file, made with H2's {@code BACKUP TO}) and the
 * uploaded files under {@value #FILES_PREFIX}.
 *
 * <p>An encrypted backup ({@link #ENCRYPTED_MAGIC}) is that ZIP encrypted with a random AES-256 key
 * (GCM); the key is wrapped with the user's RSA public key (OAEP, SHA-256), and the RSA private
 * key, locked with the password (PBKDF2-HMAC-SHA256, AES-256-GCM), travels in the file. The program
 * stores only the public key and the locked private key, never the password: whoever gets the
 * database file cannot open the backups. Layout: magic, length + locked private key, length +
 * wrapped content key, IV, ciphertext. GCM authenticates key and content, so a wrong password and a
 * changed file are both refused instead of restoring garbage.
 */
public final class BackupArchive {

  /** The entry with the format version and the creation time. */
  public static final String META = "backup.json";

  /** The entry with the H2 database file. */
  public static final String DATABASE = "database.mv.db";

  /** The prefix of the uploaded files. */
  public static final String FILES_PREFIX = "files/";

  /** Most entries a backup may have (the database, the description and the uploaded files). */
  static final int MAX_ENTRIES = 10_000;

  /** Most bytes a backup may have once unpacked (4 GiB). */
  static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

  /** First bytes of an encrypted backup. */
  static final byte[] ENCRYPTED_MAGIC = "BILLERY-BACKUP-2\n".getBytes(StandardCharsets.US_ASCII);

  private static final int SALT_BYTES = 16;
  private static final int IV_BYTES = 12;
  private static final int KEY_BITS = 256;
  private static final int TAG_BITS = 128;
  private static final int PBKDF2_ITERATIONS = 310_000;
  private static final int RSA_BITS = 3072;
  private static final int MAX_HEADER_BLOCK = 8192;
  private static final String AES_GCM = "AES/GCM/NoPadding";
  private static final String RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
  private static final OAEPParameterSpec OAEP =
      new OAEPParameterSpec(
          "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  /** A file name inside {@value #FILES_PREFIX}: one plain name, no path. */
  private static final Pattern PLAIN_NAME = Pattern.compile("[^/\\\\]+");

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
   * New keys for encrypted backups: an RSA key pair whose private key is locked with the password
   * (PBKDF2-HMAC-SHA256, AES-256-GCM). Only these are stored, never the password: a backup can be
   * written with the public key alone (on exit, without asking), and only the password opens it.
   *
   * @param password the password chosen by the user
   * @return the public key and the locked private key, Base64
   */
  public static BackupKeys newKeys(char... password) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(RSA_BITS, SECURE_RANDOM);
      KeyPair pair = generator.generateKeyPair();
      byte[] salt = random(SALT_BYTES);
      byte[] iv = random(IV_BYTES);
      Cipher lock = passwordCipher(Cipher.ENCRYPT_MODE, password, salt, iv);
      byte[] locked = concat(salt, iv, lock.doFinal(pair.getPrivate().getEncoded()));
      return new BackupKeys(
          Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
          Base64.getEncoder().encodeToString(locked));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("RSA / AES-GCM not available", e);
    }
  }

  /**
   * Encrypts a plain backup ZIP for the given keys: a random AES-256 key encrypts the ZIP
   * (AES-GCM), the public key wraps that key (RSA-OAEP, SHA-256), and the locked private key goes
   * into the file, so the password alone opens the backup on another computer.
   *
   * @param plainZip the ZIP
   * @param target the encrypted file to write
   * @param keys the keys from {@link #newKeys}
   * @throws IOException if a file cannot be read or written, or the keys are damaged
   */
  public static void encrypt(Path plainZip, Path target, BackupKeys keys) throws IOException {
    try {
      byte[] locked = Base64.getDecoder().decode(keys.lockedPrivateKey());
      PublicKey publicKey =
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(keys.publicKey())));
      byte[] contentKey = random(KEY_BITS / 8);
      Cipher wrap = Cipher.getInstance(RSA_OAEP);
      wrap.init(Cipher.ENCRYPT_MODE, publicKey, OAEP);
      byte[] wrapped = wrap.doFinal(contentKey);
      byte[] iv = random(IV_BYTES);
      Cipher content = Cipher.getInstance(AES_GCM);
      content.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(contentKey, "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      try (DataOutputStream out =
              new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)));
          InputStream in = new BufferedInputStream(Files.newInputStream(plainZip))) {
        out.write(ENCRYPTED_MAGIC);
        out.writeInt(locked.length);
        out.write(locked);
        out.writeInt(wrapped.length);
        out.write(wrapped);
        out.write(iv);
        try (CipherOutputStream encrypted = new CipherOutputStream(new KeepOpen(out), content)) {
          in.transferTo(encrypted);
        }
      }
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException("Backup keys are damaged", e);
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
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(encrypted)))) {
      if (!Arrays.equals(in.readNBytes(ENCRYPTED_MAGIC.length), ENCRYPTED_MAGIC)) {
        throw new IOException("Not an encrypted backup");
      }
      byte[] locked = readBlock(in);
      byte[] wrapped = readBlock(in);
      byte[] iv = in.readNBytes(IV_BYTES);
      if (iv.length != IV_BYTES || locked.length <= SALT_BYTES + IV_BYTES) {
        throw new IOException("Encrypted backup is truncated");
      }
      Cipher content;
      try {
        Cipher unlock =
            passwordCipher(
                Cipher.DECRYPT_MODE,
                password,
                Arrays.copyOfRange(locked, 0, SALT_BYTES),
                Arrays.copyOfRange(locked, SALT_BYTES, SALT_BYTES + IV_BYTES));
        byte[] privateKeyDer =
            unlock.doFinal(locked, SALT_BYTES + IV_BYTES, locked.length - SALT_BYTES - IV_BYTES);
        PrivateKey privateKey =
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
        Cipher unwrap = Cipher.getInstance(RSA_OAEP);
        unwrap.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
        byte[] contentKey = unwrap.doFinal(wrapped);
        content = Cipher.getInstance(AES_GCM);
        content.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(contentKey, "AES"),
            new GCMParameterSpec(TAG_BITS, iv));
      } catch (AEADBadTagException e) {
        throw new WrongPasswordException(e);
      } catch (GeneralSecurityException e) {
        throw new IOException("Encrypted backup is damaged", e);
      }
      try (OutputStream out = Files.newOutputStream(target);
          CipherInputStream plain = new CipherInputStream(in, content)) {
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

  /** A length-prefixed block of the header, refused when longer than a key can be. */
  private static byte[] readBlock(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length <= 0 || length > MAX_HEADER_BLOCK) {
      throw new IOException("Encrypted backup has a damaged header");
    }
    byte[] block = in.readNBytes(length);
    if (block.length != length) {
      throw new IOException("Encrypted backup is truncated");
    }
    return block;
  }

  private static Cipher passwordCipher(int mode, char[] password, byte[] salt, byte[] iv)
      throws GeneralSecurityException {
    byte[] key =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS))
            .getEncoded();
    Cipher cipher = Cipher.getInstance(AES_GCM);
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
    return cipher;
  }

  private static byte[] random(int bytes) {
    byte[] value = new byte[bytes];
    SECURE_RANDOM.nextBytes(value);
    return value;
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] all = new byte[length];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, all, at, part.length);
      at += part.length;
    }
    return all;
  }

  /**
   * The keys of encrypted backups as stored in the settings.
   *
   * @param publicKey the RSA public key (X.509), Base64
   * @param lockedPrivateKey salt, IV and the AES-GCM-encrypted RSA private key (PKCS#8), Base64
   */
  public record BackupKeys(String publicKey, String lockedPrivateKey) {}

  /**
   * Checks that a plain ZIP is a backup: it has the database and the description, every uploaded
   * file is a plain name (no path that could leave the upload folder), every name occurs once, and
   * it stays within {@link #MAX_ENTRIES} entries and {@link #MAX_TOTAL_BYTES} bytes once unpacked
   * (counted while reading, not taken from the headers).
   *
   * @param plainZip the ZIP
   * @throws IOException if it is no backup, exceeds a limit or cannot be read
   */
  public static void validate(Path plainZip) throws IOException {
    readEntries(plainZip, null);
  }

  /**
   * Unpacks a backup into an empty working folder: {@value #DATABASE} and {@code files/<name>}.
   * Checks the same as {@link #validate}; nothing outside {@code target} is written.
   *
   * @param plainZip the ZIP
   * @param target an empty folder
   * @throws IOException if it is no backup, exceeds a limit or cannot be written
   */
  public static void extract(Path plainZip, Path target) throws IOException {
    Files.createDirectories(target.resolve("files"));
    readEntries(plainZip, target);
  }

  /**
   * The {@value #META} document of a plain backup ZIP (at most 64 KiB read).
   *
   * @param plainZip the ZIP
   * @return the document, or an empty string if there is none
   * @throws IOException if the ZIP cannot be read
   */
  public static String readMeta(Path plainZip) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(plainZip))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (META.equals(entry.getName())) {
          return new String(zip.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
      }
    }
    return "";
  }

  /**
   * The SHA-256 (hex) of the database file in a plain backup ZIP.
   *
   * @param plainZip the ZIP
   * @return the digest, or an empty string if there is no database
   * @throws IOException if the ZIP cannot be read
   */
  public static String databaseDigest(Path plainZip) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(plainZip))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (DATABASE.equals(entry.getName())) {
          return sha256(zip);
        }
      }
    }
    return "";
  }

  /**
   * The SHA-256 (hex) of a stream, read to its end.
   *
   * @param in the stream
   * @return the digest
   * @throws IOException if it cannot be read
   */
  public static String sha256(InputStream in) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
        digest.update(buffer, 0, read);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** Reads every entry; with a target, writes it there. */
  private static void readEntries(Path plainZip, Path target) throws IOException {
    boolean database = false;
    boolean meta = false;
    int entries = 0;
    long total = 0;
    java.util.Set<String> seen = new java.util.HashSet<>();
    byte[] buffer = new byte[64 * 1024];
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(plainZip))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        entries++;
        if (entries > MAX_ENTRIES) {
          throw new IOException("Backup has too many entries");
        }
        String name = entry.getName();
        // Each name once: with two databases the check would read one and the restore the other.
        if (!seen.add(name)) {
          throw new IOException("Duplicate entry in backup: " + name);
        }
        database |= DATABASE.equals(name);
        meta |= META.equals(name);
        Optional<Path> out = destination(target, name);
        try (OutputStream sink =
            out.isPresent() ? Files.newOutputStream(out.get()) : OutputStream.nullOutputStream()) {
          for (int read = zip.read(buffer); read != -1; read = zip.read(buffer)) {
            total += read;
            if (total > MAX_TOTAL_BYTES) {
              throw new IOException("Backup is larger than " + MAX_TOTAL_BYTES + " bytes unpacked");
            }
            sink.write(buffer, 0, read);
          }
        }
      }
    }
    if (!database || !meta) {
      throw new IOException("Not a Billery backup");
    }
  }

  /**
   * Where an entry is unpacked to: nowhere for the description or when only checking, the database
   * file, or a file in {@code files/}.
   *
   * @throws IOException for an entry that is not part of the format
   */
  private static Optional<Path> destination(Path target, String name) throws IOException {
    if (META.equals(name)) {
      return Optional.empty();
    }
    if (DATABASE.equals(name)) {
      return target == null ? Optional.empty() : Optional.of(target.resolve(DATABASE));
    }
    String file = fileName(name);
    if (file == null) {
      throw new IOException("Unexpected entry in backup: " + name);
    }
    return target == null ? Optional.empty() : Optional.of(inside(target.resolve("files"), file));
  }

  /**
   * {@code folder/name}, refused if it would leave the folder (the name is already a plain name;
   * this is the second line of defence against "Zip Slip").
   */
  static Path inside(Path folder, String name) throws IOException {
    Path base = folder.toAbsolutePath().normalize();
    Path resolved = base.resolve(name).normalize();
    if (!resolved.startsWith(base) || resolved.equals(base)) {
      throw new IOException("Entry leaves the target folder: " + name);
    }
    return resolved;
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
