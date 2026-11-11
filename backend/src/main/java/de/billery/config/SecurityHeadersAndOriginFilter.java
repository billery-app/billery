package de.billery.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Adds browser security headers and rejects cross-origin API writes. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SecurityHeadersAndOriginFilter extends OncePerRequestFilter {

  /**
   * The browser policy for every response. {@code frame-src} allows {@code blob:} because the
   * template editor shows its PDF preview as a blob URL (created by this origin) in an iframe;
   * without it the preview stayed blank. {@code e2e/smoke.spec.js} opens the preview in a real
   * browser and fails on any CSP violation.
   */
  static final String CONTENT_SECURITY_POLICY = policy("'none'");

  /**
   * The policy of the sign-in callback page ({@value #SIGNIN_CALLBACK_PATH}): the same, except that
   * this origin may frame it. The SaaS sign-in renews the access token silently by loading the
   * callback URL in a hidden iframe when the provider issued no refresh token; with {@code
   * frame-ancestors 'none'} the browser refused that frame and the renewal always timed out. Other
   * origins still cannot frame it, and every other page keeps {@code 'none'}.
   */
  static final String SIGNIN_CALLBACK_POLICY = policy("'self'");

  /** The SPA route the identity provider redirects back to ({@code authService.CALLBACK_PATH}). */
  static final String SIGNIN_CALLBACK_PATH = "/auth/callback";

  private static final String HSTS = "max-age=31536000";
  private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String DESKTOP_SHUTDOWN_PATH = "/api/desktop/shutdown";

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins = "";

  /** The response policy with the given {@code frame-ancestors} source. */
  private static String policy(String frameAncestors) {
    return "default-src 'self'; "
        + "script-src 'self'; "
        + "style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data: blob:; "
        + "font-src 'self' data:; "
        + "connect-src 'self' https:; "
        + "frame-src 'self' https: blob:; "
        + "object-src 'none'; "
        + "base-uri 'self'; "
        + "form-action 'self' https:; "
        + "frame-ancestors "
        + frameAncestors;
  }

  /** Configures trusted cross-origin SPA origins in unit tests. */
  void setAllowedOrigins(String allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    setSecurityHeaders(request, response);
    if (requiresOriginCheck(request) && !hasTrustedOrigin(request)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write("{\"message\":\"Untrusted or missing request origin\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static void setSecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
    boolean callback =
        SIGNIN_CALLBACK_PATH.equals(
            UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    response.setHeader(
        "Content-Security-Policy", callback ? SIGNIN_CALLBACK_POLICY : CONTENT_SECURITY_POLICY);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", callback ? "SAMEORIGIN" : "DENY");
    if (request.isSecure()) {
      response.setHeader("Strict-Transport-Security", HSTS);
    }
  }

  private static boolean requiresOriginCheck(HttpServletRequest request) {
    if (!UNSAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
      return false;
    }
    // Same decoding as Spring MVC's routing: the raw URI would let /%61pi/... or /api;x=1/...
    // reach an /api controller without being checked.
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    return ("/api".equals(path) || path.startsWith("/api/")) && !DESKTOP_SHUTDOWN_PATH.equals(path);
  }

  private boolean hasTrustedOrigin(HttpServletRequest request) {
    Enumeration<String> originHeaders = request.getHeaders("Origin");
    if (originHeaders != null && originHeaders.hasMoreElements()) {
      String origin = originHeaders.nextElement();
      if (originHeaders.hasMoreElements()) {
        return false;
      }
      return matchesTrustedOrigin(request, origin, true);
    }
    return matchesTrustedOrigin(request, request.getHeader("Referer"), false);
  }

  private boolean matchesTrustedOrigin(
      HttpServletRequest request, String source, boolean originHeader) {
    if (source == null || source.isBlank()) {
      return false;
    }
    Origin candidate = parseOrigin(source, originHeader);
    if (candidate == null) {
      return false;
    }
    Origin requestOrigin =
        new Origin(
            request.getScheme(),
            request.getServerName(),
            effectivePort(request.getScheme(), request.getServerPort()));
    if (candidate.equals(requestOrigin)) {
      return true;
    }
    return Arrays.stream(CorsConfig.parseOrigins(allowedOrigins))
        .map(configured -> parseOrigin(configured, true))
        .anyMatch(candidate::equals);
  }

  private static int effectivePort(String scheme, int port) {
    if (port > 0) {
      return port;
    }
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }

  private static Origin parseOrigin(String value, boolean originOnly) {
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null
          || host == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
          || uri.getRawUserInfo() != null
          || (originOnly && (uri.getRawQuery() != null || uri.getRawFragment() != null))) {
        return null;
      }
      String path = uri.getRawPath();
      if (originOnly && path != null && !path.isEmpty()) {
        return null;
      }
      return new Origin(scheme, host, effectivePort(scheme, uri.getPort()));
    } catch (URISyntaxException exception) {
      return null;
    }
  }

  private record Origin(String scheme, String host, int port) {
    private Origin {
      scheme = scheme.toLowerCase(Locale.ROOT);
      host = host.toLowerCase(Locale.ROOT);
    }
  }
}
