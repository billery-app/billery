package de.billery.saas.identity;

/**
 * A user's membership in one tenant.
 *
 * @param tenantId the tenant
 * @param tenantName the tenant's name
 * @param role the user's role there
 */
public record Membership(int tenantId, String tenantName, Role role) {}
