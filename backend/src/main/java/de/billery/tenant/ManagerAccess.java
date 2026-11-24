package de.billery.tenant;

/**
 * Settings that only the people who run a company may change (its SMTP accounts). Self-host and
 * desktop have one user, who may do everything ({@link NoManagerAccess}); in SaaS the owners and
 * administrators of the active company may ({@code saas.provisioning.SaasManagerAccess}), the same
 * group that manages the team.
 */
@FunctionalInterface
public interface ManagerAccess {

  /**
   * Refuses the request unless the acting user manages the active company.
   *
   * @throws RuntimeException mapped to HTTP 403 when the role is not allowed
   */
  void requireManager();
}
