package de.billery.saas.tenant;

import de.billery.saas.identity.Role;
import java.util.concurrent.Callable;

/**
 * The tenant (and user) of the unit of work running on the current thread. A request filter sets it
 * after authentication and clears it when the request ends; {@code TenantAwareDataSource} hands it
 * to the database with every connection it lends.
 *
 * <p>There is deliberately no default: a thread without a tenant gets connections without one and
 * the database refuses to read or write tenant data (fail closed).
 */
public final class TenantContext {

  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  /** Tenant and user of one unit of work; the user is {@code null} for system tasks. */
  private record Scope(Integer tenantId, String userId, String userEmail, Role role) {}

  /**
   * Makes {@code tenantId} the active tenant of the current thread.
   *
   * @param tenantId the tenant, or null while only the user is known (the sign-in lookups of {@code
   *     /api/me}: then just the user's own tenants are visible)
   * @param userId the acting user (an {@code app_user} id) or null for system tasks
   */
  public static void set(Integer tenantId, String userId) {
    CURRENT.set(new Scope(tenantId, userId, null, null));
  }

  /**
   * Makes the tenant, user and role of an authenticated request the context of the current thread.
   *
   * @param tenantId the tenant, or null for requests that name none
   * @param userId the acting user
   * @param userEmail the user's verified address (matches invitations)
   * @param role the user's role in the tenant, null without a tenant
   */
  public static void set(Integer tenantId, String userId, String userEmail, Role role) {
    CURRENT.set(new Scope(tenantId, userId, userEmail, role));
  }

  /** Removes the tenant from the current thread. Always call this when the work is done. */
  public static void clear() {
    CURRENT.remove();
  }

  /**
   * Returns the active tenant.
   *
   * @return the tenant id, or null if the thread has none
   */
  public static Integer tenantId() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.tenantId();
  }

  /**
   * Returns the acting user.
   *
   * @return the user id, or null if there is none
   */
  public static String userId() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.userId();
  }

  /**
   * Returns the acting user's verified e-mail address.
   *
   * @return the address, or null if the thread has none
   */
  public static String userEmail() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.userEmail();
  }

  /**
   * Returns the acting user's role in the active tenant.
   *
   * @return the role, or null if there is no tenant or no user
   */
  public static Role role() {
    Scope scope = CURRENT.get();
    return scope == null ? null : scope.role();
  }

  /**
   * Runs {@code work} as the given tenant and restores the previous state afterwards, also when the
   * work throws.
   *
   * @param tenantId the tenant
   * @param userId the acting user, or null
   * @param work the work
   * @param <T> the result type
   * @return the result of {@code work}
   * @throws Exception whatever {@code work} throws
   */
  public static <T> T runAs(Integer tenantId, String userId, Callable<T> work) throws Exception {
    Scope previous = CURRENT.get();
    CURRENT.set(new Scope(tenantId, userId, null, null));
    try {
      return work.call();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
