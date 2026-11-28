package de.billery.desktop;

import de.billery.api.error.ErrorCode;
import de.billery.config.AppConfig;
import de.billery.data.Database;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * While a restore waits for the next start, nothing may be written: whatever was entered after
 * choosing the restore would vanish with it, unseen by the count the user confirmed. Every changing
 * API request answers 409 with a German message until the app is restarted or the restore is
 * withdrawn; reading, the backup endpoints and the shutdown stay possible.
 */
@Component
@Profile("desktop")
public class RestorePendingFilter extends OncePerRequestFilter {

  private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

  /** The changing desktop requests that stay possible while a restore waits. */
  private static final Set<String> ALLOWED_WHILE_PENDING =
      Set.of(
          "POST /api/desktop/backups/restore",
          "DELETE /api/desktop/backups/restore",
          "POST /api/desktop/shutdown");

  /** The answer to a refused write (409), with its code (#15). */
  static final String MESSAGE = ErrorCode.BACKUP_RESTORE_PENDING.template();

  private final BackupLocations locations;
  private final WriteGate gate;

  /**
   * Uses the database and upload directory of this process.
   *
   * @param gate counts the writes in progress
   */
  @Autowired
  public RestorePendingFilter(WriteGate gate) {
    this(BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH), gate);
  }

  /**
   * Uses the given locations (tests).
   *
   * @param locations the desktop data; {@code null} when the database is no H2 file
   * @param gate counts the writes in progress
   */
  RestorePendingFilter(BackupLocations locations, WriteGate gate) {
    super();
    this.locations = locations;
    this.gate = gate;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    boolean writes = !READING.contains(request.getMethod()) && path.startsWith("/api/");
    if (!writes || locations == null) {
      chain.doFilter(request, response);
      return;
    }
    if (path.startsWith("/api/desktop/")) {
      // The desktop endpoints stay outside the gate (a manual backup pauses the writes itself);
      // while a restore waits only choosing or withdrawing it and closing the app are allowed:
      // changed backup settings would be overwritten by the restore.
      if (Files.exists(locations.pendingRestore())
          && !ALLOWED_WHILE_PENDING.contains(request.getMethod() + " " + path)) {
        refuse(response);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    // Enter before checking: a restore staged after this point waits for this request.
    try {
      gate.enter();
    } catch (InterruptedException e) {
      // Not counted, so it must not write (and must not leave the gate).
      Thread.currentThread().interrupt();
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    try {
      if (Files.exists(locations.pendingRestore())) {
        refuse(response);
        return;
      }
      chain.doFilter(request, response);
    } finally {
      gate.leave();
    }
  }

  private static void refuse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_CONFLICT);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    // the message holds no quote or backslash, so it is written as it is
    response
        .getWriter()
        .write(
            "{\"message\":\""
                + MESSAGE
                + "\",\"code\":\""
                + ErrorCode.BACKUP_RESTORE_PENDING.code()
                + "\",\"params\":{}}");
  }
}
