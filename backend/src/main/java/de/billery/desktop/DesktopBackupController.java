package de.billery.desktop;

import de.billery.desktop.DesktopBackupService.BackupFile;
import de.billery.desktop.DesktopBackupService.BackupStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backup section of the desktop app's settings ({@link DesktopBackupService}). Desktop only.
 *
 * <p>Every request needs the per-launch secret {@code DESKTOP_UI_TOKEN} in {@code X-Desktop-Token}:
 * the Tauri shell generates it, passes it to the backend and hands it to its own window only (in
 * the URL fragment, which the SPA keeps in {@code sessionStorage}). Other programs on the computer
 * reach 127.0.0.1 too; without the token they cannot change the backup folder, switch encryption
 * off or schedule a restore (which replaces the database file at the next start). Without a
 * configured token (the JAR started without the shell) the endpoints refuse every request.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/desktop/backups")
public class DesktopBackupController {

  private static final Logger LOGGER = Logger.getLogger(DesktopBackupController.class.getName());

  /** The header that carries the window's per-launch secret. */
  static final String TOKEN_HEADER = "X-Desktop-Token";

  private final DesktopBackupService backups;
  private final String uiToken;

  /**
   * @param backups the backup service
   */
  @Autowired
  public DesktopBackupController(DesktopBackupService backups) {
    this(backups, System.getenv().getOrDefault("DESKTOP_UI_TOKEN", ""));
  }

  /**
   * @param backups the backup service
   * @param uiToken the per-launch secret of the app window; empty refuses every request
   */
  DesktopBackupController(DesktopBackupService backups, String uiToken) {
    this.backups = backups;
    this.uiToken = uiToken == null ? "" : uiToken;
  }

  /** Whether the request comes from the app's own window (constant-time comparison). */
  private boolean fromAppWindow(String token) {
    return !uiToken.isEmpty()
        && token != null
        && MessageDigest.isEqual(
            uiToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  private static <T> ResponseEntity<T> forbidden() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }

  /**
   * Settings and backups.
   *
   * @param token the window's secret ({@code X-Desktop-Token}); 403 without it
   * @return the status
   * @throws IOException if the backup folder cannot be read
   */
  @GetMapping
  public ResponseEntity<BackupStatus> status(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) throws IOException {
    if (!fromAppWindow(token)) {
      return forbidden();
    }
    return ResponseEntity.ok(backups.status());
  }

  /**
   * Changes the settings.
   *
   * @param request folder (empty: default), number to keep, backup on exit, password ({@code null}:
   *     unchanged, empty: no encryption)
   * @param token the window's secret ({@code X-Desktop-Token}); 403 without it
   * @return the new status; 400 with a German message for invalid values
   * @throws IOException if the backup folder cannot be read
   */
  @PutMapping("/settings")
  public ResponseEntity<?> updateSettings(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token,
      @RequestBody SettingsRequest request)
      throws IOException {
    if (!fromAppWindow(token)) {
      return forbidden();
    }
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
   * @param token the window's secret ({@code X-Desktop-Token}); 403 without it
   * @return 201 with the new backup; 500 with a German message if it cannot be written
   */
  @PostMapping
  public ResponseEntity<?> backupNow(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
    if (!fromAppWindow(token)) {
      return forbidden();
    }
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
   * @param token the window's secret
   * @param request the backup's file name, for an encrypted one the password, and whether the user
   *     confirmed what the restore takes out of the program
   * @return 202 with a German message; 409 with the message and the counts if documents, payments
   *     or changes were entered after the backup and not confirmed; 400 with a German message if it
   *     cannot be restored
   * @throws IOException if a file cannot be read or written
   */
  @PostMapping("/restore")
  public ResponseEntity<Map<String, Object>> restore(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token,
      @RequestBody RestoreRequest request)
      throws IOException {
    if (!fromAppWindow(token)) {
      return forbidden();
    }
    try {
      backups.stageRestore(request.name(), request.password(), request.confirmLoss());
      return ResponseEntity.accepted()
          .body(
              Map.of(
                  "message",
                  "Die Sicherung wird beim nächsten Start von Billery wiederhergestellt. Der"
                      + " jetzige Stand wird vorher gesichert."));
    } catch (DesktopBackupService.RestoreLossException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              Map.of(
                  "message", e.getMessage(),
                  "issued", e.loss().issued(),
                  "payments", e.loss().payments(),
                  "changes", e.loss().changes()));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
  }

  /**
   * Withdraws a chosen restore.
   *
   * @param token the window's secret ({@code X-Desktop-Token}); 403 without it
   * @return 204
   * @throws IOException if the staged file cannot be deleted
   */
  @DeleteMapping("/restore")
  public ResponseEntity<Void> cancelRestore(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) throws IOException {
    if (!fromAppWindow(token)) {
      return forbidden();
    }
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
   * @param confirmLoss whether the user confirmed what the restore takes out of the program
   */
  public record RestoreRequest(String name, String password, boolean confirmLoss) {}
}
