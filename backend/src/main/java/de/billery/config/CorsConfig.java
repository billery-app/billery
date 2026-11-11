package de.billery.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access to the API.
 *
 * <p>The normal deployments need no CORS at all: the SPA is served by the backend itself, or a
 * reverse proxy puts SPA and API on one origin. {@code CORS_ALLOWED_ORIGINS} is only for a frontend
 * on a <em>different</em> origin (e.g. the Vite dev server). It is a comma-separated list of
 * origins ({@code scheme://host[:port]}, no path, no wildcard); empty or unset disables CORS for
 * {@code /api/**} entirely.
 *
 * <p>Behind a TLS-terminating proxy a same-origin request is only recognised as such when the
 * proxy's {@code X-Forwarded-*} headers are honoured ({@code server.forward-headers-strategy} in
 * {@code application.properties}); otherwise Spring sees {@code http://...} against the browser's
 * {@code Origin: https://...} and answers 403 "Invalid CORS request".
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  /**
   * Splits the configured origins.
   *
   * @param configured the raw property value, may be {@code null}
   * @return the trimmed, non-empty origins without a trailing slash
   */
  static String[] parseOrigins(String configured) {
    if (configured == null) {
      return new String[0];
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
        .filter(origin -> !origin.isEmpty())
        .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String[] origins = parseOrigins(allowedOrigins);
    if (origins.length == 0) {
      return;
    }
    // No credentials: the API has no cookie or session authentication, so there is nothing to send.
    registry
        .addMapping("/api/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("Content-Disposition")
        .maxAge(3600);
  }

  /**
   * Sets the allowed origins directly (tests).
   *
   * @param allowedOrigins comma-separated origins
   */
  void setAllowedOrigins(String allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }
}
