package de.billery.desktop;

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

  static final String MESSAGE =
      "Eine Wiederherstellung ist vorgemerkt: bis zum Neustart wird nichts mehr gespeichert. Bitte"
          + " Billery beenden und neu starten, oder die Wiederherstellung unter Einstellungen →"
          + " Datensicherung abbrechen.";

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
    boolean writes =
        !READING.contains(request.getMethod())
            && path.startsWith("/api/")
            && !path.startsWith("/api/desktop/");
    if (!writes || locations == null) {
      chain.doFilter(request, response);
      return;
    }
    // Enter before checking: a restore staged after this point waits for this request.
    gate.enter();
    try {
      if (Files.exists(locations.pendingRestore())) {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + MESSAGE + "\"}");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      gate.leave();
    }
  }
}
