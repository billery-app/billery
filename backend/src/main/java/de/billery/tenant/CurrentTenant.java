package de.billery.tenant;

/**
 * The tenant (company) the current unit of work belongs to.
 *
 * <p>Self-host and desktop installs have exactly one tenant, {@link #DEFAULT_TENANT_ID}, which is
 * also the default of every {@code tenant_id} column; {@link DefaultCurrentTenant} returns it. The
 * SaaS profile provides a request-scoped implementation that resolves the tenant from the
 * authenticated user (see {@code docs/architecture/multi-tenancy.md}).
 *
 * <p>Repositories do not filter by tenant themselves: the database does (row-level security in the
 * SaaS profile). Code that has to name a tenant explicitly, such as provisioning or startup
 * seeding, asks this interface.
 */
@FunctionalInterface
public interface CurrentTenant {

  /** The id of the only tenant of a self-hosted install, and the default of {@code tenant_id}. */
  int DEFAULT_TENANT_ID = 1;

  /**
   * Returns the id of the active tenant.
   *
   * @return the tenant id
   */
  int tenantId();
}
