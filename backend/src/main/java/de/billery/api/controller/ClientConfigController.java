package de.billery.api.controller;

import de.billery.config.AppConfig;
import de.billery.config.AppVersion;
import de.billery.config.DemoMode;
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
  private final DemoMode demo;
  private final AppVersion version;

  /**
   * @param environment tells whether the Spring profile {@code desktop} is active
   * @param demo whether this is the public demo ({@code BILLERY_DEMO})
   * @param version the released version of this build
   */
  public ClientConfigController(Environment environment, DemoMode demo, AppVersion version) {
    this.environment = environment;
    this.demo = demo;
    this.version = version;
  }

  /**
   * Returns the operating mode.
   *
   * @return {@code {"mode": "selfhost", "desktop": true|false, "demo": true|false, "version":
   *     "0.2.0"|"dev"}}; {@code demo} is the public demo (banner, tour in every session, mail
   *     switched off); {@code desktop} tells the SPA to show the desktop-only settings (backups):
   *     the Spring profile {@code desktop} with an H2 file database (the end-to-end tests run the
   *     profile on an in-memory database) and the window token of the Tauri shell ({@code
   *     DESKTOP_UI_TOKEN})
   */
  @GetMapping("/api/config")
  public Map<String, Object> config() {
    boolean desktopBackups =
        environment.matchesProfiles("desktop")
            && BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH) != null
            && !environment.getProperty("DESKTOP_UI_TOKEN", "").isEmpty();
    return Map.of(
        "mode",
        "selfhost",
        "desktop",
        desktopBackups,
        "demo",
        demo.isActive(),
        "version",
        version.version());
  }
}
