package de.billery.desktop;

import de.billery.desktop.DesktopBackupService.BackupFile;
import de.billery.desktop.DesktopBackupService.BackupStatus;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backup section of the desktop app's settings ({@link DesktopBackupService}). Desktop only:
 * the app listens on 127.0.0.1 and writes need the app's own origin (CSRF filter).
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/desktop/backups")
public class DesktopBackupController {

  private static final Logger LOGGER = Logger.getLogger(DesktopBackupController.class.getName());

  private final DesktopBackupService backups;

  /**
   * @param backups the backup service
   */
  public DesktopBackupController(DesktopBackupService backups) {
    this.backups = backups;
  }

  /**
   * Settings and backups.
   *
   * @return the status
   * @throws IOException if the backup folder cannot be read
   */
  @GetMapping
  public BackupStatus status() throws IOException {
    return backups.status();
  }

  /**
   * Changes the settings.
   *
   * @param request folder (empty: default), number to keep, backup on exit, password ({@code null}:
   *     unchanged, empty: no encryption)
   * @return the new status; 400 with a German message for invalid values
   * @throws IOException if the backup folder cannot be read
   */
  @PutMapping("/settings")
  public ResponseEntity<?> updateSettings(@RequestBody SettingsRequest request) throws IOException {
    try {
      backups.updateSettings(
          request.directory(), request.keep(), request.onExit(), request.password());
      return ResponseEntity.ok(backups.status());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
  }

  /**
   * Makes a backup now ("Jetzt sichern").
   *
   * @return 201 with the new backup; 500 with a German message if it cannot be written
   */
  @PostMapping
  public ResponseEntity<?> backupNow() {
    try {
      BackupFile file = backups.backup(BackupNames.MANUAL);
      return ResponseEntity.status(HttpStatus.CREATED).body(file);
    } catch (IOException | RuntimeException e) {
      LOGGER.log(Level.WARNING, "Manual backup failed", e);
      return ResponseEntity.internalServerError()
          .body(
              Map.of(
                  "message",
                  "Die Sicherung konnte nicht geschrieben werden. Ist der Sicherungsordner"
                      + " erreichbar?"));
    }
  }

  /**
   * Chooses a backup to restore at the next start.
   *
   * @param request the backup's file name and, for an encrypted one, the password
   * @return 202 with a German message; 400 with a German message if it cannot be restored
   * @throws IOException if a file cannot be read or written
   */
  @PostMapping("/restore")
  public ResponseEntity<Map<String, String>> restore(@RequestBody RestoreRequest request)
      throws IOException {
    try {
      backups.stageRestore(request.name(), request.password());
      return ResponseEntity.accepted()
          .body(
              Map.of(
                  "message",
                  "Die Sicherung wird beim nächsten Start von Billery wiederhergestellt. Der"
                      + " jetzige Stand wird vorher gesichert."));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
  }

  /**
   * Withdraws a chosen restore.
   *
   * @return 204
   * @throws IOException if the staged file cannot be deleted
   */
  @DeleteMapping("/restore")
  public ResponseEntity<Void> cancelRestore() throws IOException {
    backups.cancelRestore();
    return ResponseEntity.noContent().build();
  }

  /**
   * The body of {@code PUT /settings}.
   *
   * @param directory the backup folder, empty for the default
   * @param keep how many backups to keep
   * @param onExit whether to back up when the app is closed
   * @param password {@code null} keeps the password, empty removes it, otherwise the new one
   */
  public record SettingsRequest(String directory, int keep, boolean onExit, String password) {}

  /**
   * The body of {@code POST /restore}.
   *
   * @param name the backup's file name
   * @param password the password of an encrypted backup
   */
  public record RestoreRequest(String name, String password) {}
}
