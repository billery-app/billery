package de.billery.saas.security;

import de.billery.api.error.ErrorBody;
import de.billery.saas.api.SaasErrorCode;
import de.billery.saas.identity.IdentityException;
import de.billery.saas.identity.IdentityRepository;
import de.billery.saas.identity.IdentityService;
import de.billery.saas.identity.Membership;
import de.billery.saas.identity.Role;
import de.billery.saas.identity.SignInLog;
import de.billery.saas.identity.TenantAccess;
import de.billery.saas.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns an authenticated request into a tenant context: maps the token to a user, finds the tenant
 * the request is for, <b>checks in the database that the user is a member</b> of it, checks that
 * the user's role allows the HTTP method, and makes user and tenant the context of the thread for
 * the rest of the request.
 *
 * <p>The tenant comes from the {@code X-Tenant-Id} header. It is only a request to act in that
 * tenant; it counts after the membership check, never on its own, and never from a request body. A
 * user with exactly one tenant may omit the header. {@code /api/me} needs no tenant: it lists the
 * user's tenants.
 *
 * <p>Not a Spring bean on purpose: it is added to the security filter chain after the bearer token
 * filter, and a bean would be registered a second time as a plain servlet filter.
 */
public class TenantContextFilter extends OncePerRequestFilter {

  /** The header naming the tenant a request acts in. */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  private static final String ME_PATH = "/api/me";

  private final IdentityService identities;
  private final IdentityRepository repository;
  private final SignInLog signIns;

  /**
   * Creates the filter.
   *
   * @param identities maps a token to a user
   * @param repository the users, memberships and addresses
   * @param signIns records a provider session's first access to a company (operator only)
   */
  public TenantContextFilter(
      IdentityService identities, IdentityRepository repository, SignInLog signIns) {
    super();
    this.identities = identities;
    this.repository = repository;
    this.signIns = signIns;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return !(authentication instanceof JwtAuthenticationToken);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Jwt token =
        ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
            .getToken();
    UUID user;
    try {
      user = identities.resolve(token);
    } catch (IdentityException e) {
      ErrorBody.write(response, HttpServletResponse.SC_FORBIDDEN, e.userMessage());
      return;
    }
    String userId = user.toString();
    String email = repository.findEmail(user).orElse(null);

    if (needsNoTenant(request)) {
      TenantContext.set(null, userId, email, null);
      proceed(request, response, chain);
      return;
    }

    Optional<Integer> tenant;
    try {
      tenant = requestedTenant(request, user, userId);
    } catch (IllegalArgumentException e) {
      ErrorBody.write(
          response, HttpServletResponse.SC_BAD_REQUEST, SaasErrorCode.TENANT_HEADER_INVALID.with());
      return;
    }
    if (tenant.isEmpty()) {
      ErrorBody.write(
          response, HttpServletResponse.SC_BAD_REQUEST, SaasErrorCode.TENANT_HEADER_MISSING.with());
      return;
    }
    Optional<TenantAccess> access = accessTo(user, userId, tenant.get());
    if (access.isEmpty()) {
      ErrorBody.write(response, HttpServletResponse.SC_FORBIDDEN, SaasErrorCode.NOT_MEMBER.with());
      return;
    }
    Role role = access.get().role();
    // Authenticated and a member: this is an access to the company, whatever the request does.
    TenantContext.set(tenant.get(), userId, email, role);
    signIns.record(token, user, tenant.get(), request.getRemoteAddr());
    if (isLeaving(request, userId)) {
      // Anyone may leave a tenant, whatever the role and whether or not it is still open; the
      // member service keeps the last owner in.
      TenantContext.set(tenant.get(), userId, email, role);
      proceed(request, response, chain);
      return;
    }
    if (!role.allows(request.getMethod())) {
      ErrorBody.write(
          response, HttpServletResponse.SC_FORBIDDEN, SaasErrorCode.ROLE_READ_ONLY.with());
      return;
    }
    if (!access.get().writable() && !Role.READER.allows(request.getMethod())) {
      ErrorBody.write(
          response, HttpServletResponse.SC_FORBIDDEN, SaasErrorCode.TENANT_READ_ONLY.with());
      return;
    }
    TenantContext.set(tenant.get(), userId, email, role);
    proceed(request, response, chain);
  }

  private Optional<Integer> requestedTenant(HttpServletRequest request, UUID user, String userId) {
    String header = request.getHeader(TENANT_HEADER);
    if (header != null && !header.isBlank()) {
      try {
        return Optional.of(Integer.parseInt(header.trim()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(TENANT_HEADER + " must be a tenant id", e);
      }
    }
    // Only the user's own memberships are visible in this lookup; a single one is the default.
    List<Membership> memberships = membershipsOf(user, userId);
    return memberships.size() == 1 ? Optional.of(memberships.get(0).tenantId()) : Optional.empty();
  }

  /** {@code DELETE /api/tenant/members/<own id>}: the user removes themselves. */
  private static boolean isLeaving(HttpServletRequest request, String userId) {
    return "DELETE".equals(request.getMethod())
        && request.getRequestURI().equals("/api/tenant/members/" + userId);
  }

  /**
   * Requests that act for the user alone: reading one's tenants, signing up, accepting an
   * invitation.
   */
  private static boolean needsNoTenant(HttpServletRequest request) {
    String path = request.getRequestURI();
    return ME_PATH.equals(path)
        || "/api/preferences".equals(path)
        || "/api/tenants".equals(path)
        || path.startsWith("/api/invitations/");
  }

  /** The membership table shows a user its own rows, so the lookup runs as that user. */
  private Optional<TenantAccess> accessTo(UUID user, String userId, int tenantId) {
    TenantContext.set(null, userId);
    try {
      return repository.findAccess(user, tenantId);
    } finally {
      TenantContext.clear();
    }
  }

  private List<Membership> membershipsOf(UUID user, String userId) {
    TenantContext.set(null, userId);
    try {
      return repository.findMemberships(user);
    } finally {
      TenantContext.clear();
    }
  }

  private static void proceed(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
