package de.billery.data;

import java.util.Arrays;
import java.util.Locale;

/**
 * Reads the active Spring profiles before Spring has started (the database is prepared in {@code
 * MainApp.prepareDatabase()}, ahead of the application context).
 *
 * <p>Same sources and precedence Spring Boot uses for this setting: the system property {@code
 * spring.profiles.active}, then the environment variable {@code SPRING_PROFILES_ACTIVE}; both hold
 * a comma-separated list.
 */
public final class AppProfiles {

  /** The profile of the multi-tenant SaaS deployment. */
  public static final String SAAS = "saas";

  /** A classpath folder that only the SaaS build contains: its Flyway migrations. */
  private static final String SAAS_BUILD_MARKER = "/db/migration-saas";

  private AppProfiles() {}

  /**
   * Tells whether the given profile is active.
   *
   * @param profile the profile name, compared case-insensitively
   * @return true if it is listed in the active profiles
   */
  public static boolean isActive(String profile) {
    String configured = System.getProperty("spring.profiles.active");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("SPRING_PROFILES_ACTIVE");
    }
    if (configured == null) {
      return false;
    }
    return Arrays.stream(configured.split(","))
        .map(name -> name.trim().toLowerCase(Locale.ROOT))
        .anyMatch(name -> name.equals(profile.toLowerCase(Locale.ROOT)));
  }

  /**
   * Tells whether the {@code saas} profile is active.
   *
   * @return true for the multi-tenant SaaS deployment
   */
  public static boolean isSaas() {
    return isActive(SAAS);
  }

  /**
   * Tells whether this is the SaaS build (Maven profile {@code saas}), as opposed to the self-host
   * build that contains none of the SaaS code.
   *
   * @return true if the SaaS migrations are on the classpath
   */
  public static boolean isSaasBuild() {
    return AppProfiles.class.getResource(SAAS_BUILD_MARKER) != null;
  }

  /**
   * Refuses to start a SaaS build without the Spring profile {@code saas}. The authentication and
   * the tenant isolation of the SaaS application are switched on by that profile; without it the
   * same JAR would run like the self-host application, that is without any sign-in. A default in a
   * container image is not enough, an environment setting can replace it.
   *
   * @throws IllegalStateException if the SaaS build is started without the profile
   */
  public static void requireProfileForBuild() {
    if (isSaasBuild() && !isSaas()) {
      throw new IllegalStateException(
          "This is the SaaS build, but the Spring profile 'saas' is not active"
              + " (SPRING_PROFILES_ACTIVE=saas). Without it the application would run without any"
              + " authentication, so it does not start.");
    }
  }
}
