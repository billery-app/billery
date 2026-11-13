package de.billery.desktop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    return FILE_NAME_PATTERN.matcher(fileName).matches();
  }

  /**
   * The time in the name of a backup.
   *
   * @param fileName a backup name
   * @return the time, or {@code null} for another name
   */
  public static LocalDateTime time(String fileName) {
    Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
    return matcher.matches() ? LocalDateTime.parse(matcher.group(1), STAMP) : null;
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
   * @return a small JSON document
   */
  public static String meta(String reason) {
    return "{\"format\":1,\"createdAt\":\""
        + LocalDateTime.now()
        + "\",\"reason\":\""
        + reason
        + "\"}";
  }
}
