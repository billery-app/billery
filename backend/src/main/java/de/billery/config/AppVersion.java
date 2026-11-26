package de.billery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The released version of this build ({@code BILLERY_APP_VERSION}, e.g. {@code 0.2.0}). The release
 * workflow passes the tag into the image ({@code backend/Dockerfile}, build argument {@code
 * APP_VERSION}); everything else (local builds, CI scans, the dev server) says {@code dev}. Shown
 * under Einstellungen and the base of a later update check.
 */
@Component
public class AppVersion {

  private final String value;

  /**
   * @param version {@code billery.app-version}, from {@code BILLERY_APP_VERSION}
   */
  public AppVersion(@Value("${billery.app-version:dev}") String version) {
    this.value = version == null || version.isBlank() ? "dev" : version.trim();
  }

  /**
   * @return the version, {@code dev} for a build that is not a release
   */
  public String version() {
    return value;
  }
}
