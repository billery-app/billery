package de.billery.api.controller;

import de.billery.config.AppConfig;
import de.billery.data.Database;
import de.billery.desktop.BackupLocations;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the single-page app how it is operated, before it asks for anything else. A self-hosted
 * install has no sign-in; the SaaS profile answers the same path with its sign-in settings.
 */
@RestController
@Profile("!saas")
public class ClientConfigController {

  private final Environment environment;

  /**
   * @param environment tells whether the Spring profile {@code desktop} is active
   */
  public ClientConfigController(Environment environment) {
    this.environment = environment;
  }

  /**
   * Returns the operating mode.
   *
   * @return {@code {"mode": "selfhost", "desktop": true|false}}; {@code desktop} tells the SPA to
   *     show the desktop-only settings (backups): the Spring profile {@code desktop} with an H2
   *     file database (the end-to-end tests run the profile on an in-memory database)
   */
  @GetMapping("/api/config")
  public Map<String, Object> config() {
    boolean desktopBackups =
        environment.matchesProfiles("desktop")
            && BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH) != null;
    return Map.of("mode", "selfhost", "desktop", desktopBackups);
  }
}
