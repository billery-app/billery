package de.billery.desktop;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Names of backup files: {@code billery-2026-11-13_18-05-12-beenden.zip}, with {@code .enc} when
 * encrypted. Only files of this pattern are listed and pruned, so a backup folder may hold other
 * files (a cloud folder, an external drive) without anything of them being deleted.
 */
public final class BackupNames {

  /** Made when the app was closed. */
  public static final String ON_EXIT = "beenden";

  /** Made by the user ("Jetzt sichern"). */
  public static final String MANUAL = "manuell";

  /** The state before a restore, made by the restore itself. */
  public static final String BEFORE_RESTORE = "vor-wiederherstellung";

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

  private static final Pattern FILE_NAME_PATTERN =
      Pattern.compile(
          "^billery-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})-([a-z-]+)\\.zip(\\.enc)?$");

  /** Counting from here takes every record into account. */
  static final LocalDateTime EVERYTHING = LocalDateTime.of(1970, 1, 1, 0, 0);

  private static final Pattern SNAPSHOT = Pattern.compile("\"snapshotAt\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern REASON_FIELD = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern SIG_FIELD = Pattern.compile("\"sig\"\\s*:\\s*\"([^\"]+)\"");

  private BackupNames() {}

  /**
   * The file name of a new backup.
   *
   * @param time when it is made
   * @param reason {@link #ON_EXIT}, {@link #MANUAL} or {@link #BEFORE_RESTORE}
   * @param encrypted whether the file is encrypted
   * @return the name
   */
  public static String name(LocalDateTime time, String reason, boolean encrypted) {
    return "billery-" + STAMP.format(time) + "-" + reason + ".zip" + (encrypted ? ".enc" : "");
  }

  /**
   * Whether a file name is one of ours.
   *
   * @param fileName a file name
   * @return {@code true} for a backup name
   */
  public static boolean isBackup(String fileName) {
    return time(fileName) != null;
  }

  /**
   * The time in the name of a backup.
   *
   * @param fileName a backup name
   * @return the time, or {@code null} for another name
   */
  public static LocalDateTime time(String fileName) {
    Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
    if (!matcher.matches()) {
      return null;
    }
    try {
      return LocalDateTime.parse(matcher.group(1), STAMP);
    } catch (DateTimeParseException e) {
      return null; // "2026-99-99_…": not one of ours, never breaks the list
    }
  }

  /**
   * The reason in the name of a backup.
   *
   * @param fileName a backup name
   * @return the reason, or {@code null} for another name
   */
  public static String reason(String fileName) {
    Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
    return matcher.matches() ? matcher.group(2) : null;
  }

  /**
   * The {@link BackupArchive#META} content, signed with the installation's manifest key.
   *
   * @param reason why the backup was made
   * @param snapshotAt when the copy of the database was taken (before the copy started); the time
   *     in the file name may be later (names only grow), so this one decides what a restore loses
   * @param manifestKey the installation's key ({@code backup_manifest_key}); {@code null} writes no
   *     signature
   * @return a small JSON document
   */
  public static String meta(String reason, LocalDateTime snapshotAt, byte[] manifestKey) {
    String json =
        "{\"format\":1,\"snapshotAt\":\"" + snapshotAt + "\",\"reason\":\"" + reason + "\"";
    if (manifestKey != null && manifestKey.length > 0) {
      json += ",\"sig\":\"" + signature(manifestKey, snapshotAt.toString(), reason) + "\"";
    }
    return json + "}";
  }

  /**
   * From when a restore counts what it takes out of the program: the snapshot time inside the
   * backup, if this installation signed it ({@code sig}, HMAC-SHA256 with the manifest key) and it
   * is plausible (not after the time in the name plus a minute for the copy, not after now).
   * Otherwise everything counts: a prepared or foreign backup cannot hide what a restore loses. A
   * backup from another computer (after losing the old one) is "foreign" too, but then the new
   * installation has nothing to lose yet.
   *
   * @param meta the {@link BackupArchive#META} document
   * @param fileName the backup's file name
   * @param now the current time
   * @param manifestKey this installation's key, {@code null} if it has none yet
   * @return the time to count from
   */
  public static LocalDateTime trustedSnapshot(
      String meta, String fileName, LocalDateTime now, byte[] manifestKey) {
    LocalDateTime snapshot = snapshotAt(meta);
    LocalDateTime named = time(fileName);
    String reason = field(meta, REASON_FIELD);
    String sig = field(meta, SIG_FIELD);
    if (snapshot == null
        || named == null
        || manifestKey == null
        || manifestKey.length == 0
        || reason == null
        || sig == null
        || !MessageDigest.isEqual(
            sig.getBytes(StandardCharsets.US_ASCII),
            signature(manifestKey, field(meta, SNAPSHOT), reason)
                .getBytes(StandardCharsets.US_ASCII))
        || snapshot.isAfter(named.plusMinutes(1))
        || snapshot.isAfter(now)) {
      return EVERYTHING;
    }
    return snapshot;
  }

  private static String signature(byte[] key, String snapshotAt, String reason) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] digest =
          mac.doFinal(
              ("billery-backup-1|" + snapshotAt + "|" + reason).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 not available", e);
    }
  }

  private static String field(String meta, Pattern pattern) {
    Matcher matcher = pattern.matcher(meta == null ? "" : meta);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * The snapshot time of a {@link BackupArchive#META} document.
   *
   * @param meta the document
   * @return the time, or {@code null} if it has none or it cannot be read
   */
  public static LocalDateTime snapshotAt(String meta) {
    String value = field(meta, SNAPSHOT);
    if (value == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
