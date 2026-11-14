package de.billery.desktop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
   * The {@link BackupArchive#META} content.
   *
   * @param reason why the backup was made
   * @param snapshotAt when the copy of the database was taken (before the copy started); the time
   *     in the file name may be later (names only grow), so this one decides what a restore loses
   * @return a small JSON document
   */
  public static String meta(String reason, LocalDateTime snapshotAt) {
    return "{\"format\":1,\"snapshotAt\":\"" + snapshotAt + "\",\"reason\":\"" + reason + "\"}";
  }

  /**
   * From when a restore counts what it takes out of the program: the snapshot time inside the
   * backup, not the (possibly later) time in its name. A backup without a readable one, or with one
   * after the time in its name (plus a minute for the copy itself) or after now, cannot be trusted
   * and counts everything: better a warning too many than one too few. This is a safety net for
   * mistakes, not a defence against someone who can place prepared files in the backup folder.
   *
   * @param meta the {@link BackupArchive#META} document
   * @param fileName the backup's file name
   * @param now the current time
   * @return the time to count from
   */
  public static LocalDateTime trustedSnapshot(String meta, String fileName, LocalDateTime now) {
    LocalDateTime snapshot = snapshotAt(meta);
    LocalDateTime named = time(fileName);
    if (snapshot == null
        || named == null
        || snapshot.isAfter(named.plusMinutes(1))
        || snapshot.isAfter(now)) {
      return EVERYTHING;
    }
    return snapshot;
  }

  /**
   * The snapshot time of a {@link BackupArchive#META} document.
   *
   * @param meta the document
   * @return the time, or {@code null} if it has none or it cannot be read
   */
  public static LocalDateTime snapshotAt(String meta) {
    Matcher matcher = SNAPSHOT.matcher(meta == null ? "" : meta);
    if (!matcher.find()) {
      return null;
    }
    try {
      return LocalDateTime.parse(matcher.group(1));
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
