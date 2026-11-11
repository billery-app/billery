package de.billery.saas.security;

import de.billery.saas.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RequestLimits} to the API, after {@link TenantContextFilter}: the key is the
 * active tenant, or the user for requests that name none ({@code /api/me}, sign-up, invitations).
 * Refused requests get 429 with {@code Retry-After} and a German message the app shows. Requests
 * without a signed-in user (health, configuration) are not counted.
 *
 * <p>Not a Spring bean on purpose, like {@link TenantContextFilter}.
 */
public class RequestLimitFilter extends OncePerRequestFilter {

  /** The requests that render or package documents: PDF, preview, XRechnung, export, import. */
  static final Pattern HEAVY =
      Pattern.compile(
          "^/api/(invoices/\\d+/(pdf|xrechnung)|templates/preview|export/.*|documents/(export|import).*)$");

  private final RequestLimits limits;

  /**
   * @param limits the limits of this instance
   */
  public RequestLimitFilter(RequestLimits limits) {
    super();
    this.limits = limits;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = key();
    if (key == null) {
      chain.doFilter(request, response);
      return;
    }
    long wait = limits.tryRequest(key);
    if (wait > 0) {
      reject(response, wait, "Zu viele Anfragen in kurzer Zeit. Bitte einen Moment warten.");
      return;
    }
    if (!HEAVY.matcher(request.getRequestURI()).matches()) {
      chain.doFilter(request, response);
      return;
    }
    if (!limits.tryHeavy(key)) {
      reject(
          response,
          5,
          "Es werden gerade schon Dokumente erzeugt. Bitte warten, bis sie fertig sind.");
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      limits.releaseHeavy(key);
    }
  }

  private static String key() {
    Integer tenant = TenantContext.tenantId();
    if (tenant != null) {
      return "tenant:" + tenant;
    }
    String user = TenantContext.userId();
    return user == null ? null : "user:" + user;
  }

  private static void reject(HttpServletResponse response, long seconds, String message)
      throws IOException {
    response.setStatus(429);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}
