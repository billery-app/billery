package de.billery.mail;

import de.billery.config.AppConfig;
import de.billery.config.SecretFiles;
import de.billery.data.Database;
import de.billery.desktop.BackupLocations;
import de.billery.tenant.CurrentTenant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the passwords of the companies' SMTP accounts for the database (AES-256-GCM, a random IV
 * per value). The key is never in the database:
 *
 * <ul>
 *   <li>a server (self-host, SaaS) takes it from {@code BILLERY_SECRETS_KEY} (32 random bytes,
 *       Base64; {@code openssl rand -base64 32});
 *   <li>the desktop app, without that variable, keeps it in {@code mail-secrets.key} next to its
 *       database, created when the first password is stored. It is in no backup: a backup restored
 *       on another computer has the accounts, and their passwords are entered again.
 * </ul>
 *
 * Without a key no password can be stored ({@link #available()} is false) and the settings say so.
 * The active tenant is bound into every value (authenticated data), so a value copied into another
 * company's row does not decrypt there. Changing the key makes the stored passwords unreadable:
 * they are entered again, nothing else is lost.
 */
@Component
public class CredentialCipher {

  /** The prefix of the stored form; a later format gets another one. */
  static final String VERSION = "v1:";

  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String KEY_FILE = "mail-secrets.key";

  private final KeySource keys;
  private final CurrentTenant tenant;
  private final SecureRandom random = new SecureRandom();

  /** Where the key comes from. */
  interface KeySource {

    /**
     * @param create whether a missing key may be created (only when a value is encrypted)
     * @return the key, or an empty array if there is none
     * @throws IOException if the key file cannot be read or written
     */
    byte[] key(boolean create) throws IOException;

    /**
     * @return true if {@link #key} returns a key when {@code create} is true
     */
    boolean canProvide();
  }

  /** A fixed key, or none (an empty array). */
  private record FixedKey(byte[] value) implements KeySource {

    @Override
    public byte[] key(boolean create) {
      return value.clone();
    }

    @Override
    public boolean canProvide() {
      return value.length > 0;
    }
  }

  /** The desktop key file, created on first use. */
  private record KeyFile(Path file) implements KeySource {

    @Override
    public byte[] key(boolean create) throws IOException {
      return keyFile(file, create);
    }

    @Override
    public boolean canProvide() {
      return true;
    }
  }

  /**
   * The application's cipher: {@code BILLERY_SECRETS_KEY}, or the desktop key file next to an H2
   * file database, or none.
   *
   * @param configuredKey {@code BILLERY_SECRETS_KEY}
   * @param tenant the active tenant (bound into every value)
   * @throws IllegalStateException if the key is set but not 32 bytes of Base64
   */
  @Autowired
  public CredentialCipher(
      @Value("${billery.secrets.key:}") String configuredKey, CurrentTenant tenant) {
    this(source(configuredKey), tenant);
  }

  /**
   * @param keys where the key comes from
   * @param tenant the active tenant
   */
  CredentialCipher(KeySource keys, CurrentTenant tenant) {
    this.keys = keys;
    this.tenant = tenant;
  }

  /**
   * A cipher with a fixed key, for tests.
   *
   * @param key 32 bytes
   * @param tenant the active tenant
   * @return the cipher
   */
  public static CredentialCipher withKey(byte[] key, CurrentTenant tenant) {
    return new CredentialCipher(new FixedKey(key.clone()), tenant);
  }

  /**
   * A cipher without a key, for tests and installations that cannot store passwords.
   *
   * @param tenant the active tenant
   * @return the cipher
   */
  public static CredentialCipher none(CurrentTenant tenant) {
    return new CredentialCipher(new FixedKey(new byte[0]), tenant);
  }

  private static KeySource source(String configuredKey) {
    String trimmed = configuredKey == null ? "" : configuredKey.trim();
    if (!trimmed.isEmpty()) {
      byte[] key;
      try {
        key = Base64.getDecoder().decode(trimmed);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "BILLERY_SECRETS_KEY must be Base64 (openssl rand -base64 32)", e);
      }
      if (key.length != KEY_BYTES) {
        throw new IllegalStateException(
            "BILLERY_SECRETS_KEY must be 32 bytes, Base64-encoded (openssl rand -base64 32)");
      }
      return new FixedKey(key);
    }
    BackupLocations desktop = BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH);
    if (desktop == null) {
      return new FixedKey(new byte[0]);
    }
    return new KeyFile(desktop.appData().resolve(KEY_FILE));
  }

  /**
   * Reads the desktop key file, or creates it. The file is created with owner-only permissions from
   * the start (no moment in which another local user could read it); on a file system without POSIX
   * permissions (Windows) the user's app-data folder protects it. When two starts race, the second
   * reads the key the first wrote.
   *
   * @param file the key file
   * @param create whether a missing file may be created
   * @return the key, or an empty array when there is none and none may be created
   * @throws IOException if the file cannot be read or written, or is damaged
   */
  static byte[] keyFile(Path file, boolean create) throws IOException {
    if (Files.exists(file)) {
      return readKey(file);
    }
    if (!create) {
      return new byte[0];
    }
    byte[] key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key);
    try {
      SecretFiles.createOwnerOnly(file, Base64.getEncoder().encodeToString(key));
    } catch (FileAlreadyExistsException e) {
      return readKey(file);
    }
    return key;
  }

  private static byte[] readKey(Path file) throws IOException {
    byte[] key =
        Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip());
    if (key.length != KEY_BYTES) {
      throw new IOException("The key file " + file + " is damaged");
    }
    return key;
  }

  /**
   * @return true when passwords can be stored: a key is configured, or the desktop app can create
   *     its key file
   */
  public boolean available() {
    return keys.canProvide();
  }

  /**
   * Encrypts a password.
   *
   * @param plain the password
   * @return the stored form ({@code v1:} + Base64 of IV and ciphertext)
   * @throws IllegalStateException if there is no key
   */
  public String encrypt(String plain) {
    try {
      byte[] key = keys.key(true);
      if (key.length == 0) {
        throw new IllegalStateException("No key to store passwords (BILLERY_SECRETS_KEY)");
      }
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData());
      byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = Arrays.copyOf(iv, IV_BYTES + sealed.length);
      System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
      return VERSION + Base64.getEncoder().encodeToString(out);
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException("The password could not be encrypted", e);
    }
  }

  /**
   * Decrypts a stored password.
   *
   * @param stored the stored form
   * @return the password
   * @throws IllegalStateException if there is no key, the key changed, or the value belongs to
   *     another company
   */
  public String decrypt(String stored) {
    if (stored == null || !stored.startsWith(VERSION)) {
      throw new IllegalStateException("Not an encrypted password");
    }
    try {
      byte[] key = keys.key(false);
      if (key.length == 0) {
        throw new IllegalStateException("No key to read passwords (BILLERY_SECRETS_KEY)");
      }
      byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      cipher.updateAAD(associatedData());
      return new String(
          cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("The stored password cannot be decrypted", e);
    }
  }

  private byte[] associatedData() {
    return ("billery-mail-account:" + tenant.tenantId()).getBytes(StandardCharsets.UTF_8);
  }
}
