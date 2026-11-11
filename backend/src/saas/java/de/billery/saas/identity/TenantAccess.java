package de.billery.saas.identity;

/**
 * A user's access to one tenant.
 *
 * @param role the user's role there
 * @param tenantStatus {@code ACTIVE}, or {@code CLOSED} / {@code SUSPENDED} (read-only)
 */
public record TenantAccess(Role role, String tenantStatus) {

  /**
   * Tells whether the tenant accepts changes.
   *
   * @return true for an active tenant
   */
  public boolean writable() {
    return "ACTIVE".equals(tenantStatus);
  }
}
