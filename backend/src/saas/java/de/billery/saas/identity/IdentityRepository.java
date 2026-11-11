package de.billery.saas.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Users, their identities at the identity provider and their tenant memberships. {@code app_user}
 * and {@code user_identity} have no row-level security (a user is not tenant data), so every query
 * on them is keyed by an identity, an e-mail address or a user id. {@code tenant_member} is
 * protected: the thread's {@link de.billery.saas.tenant.TenantContext} must carry the user (their
 * own memberships) or the tenant (its members).
 */
@Repository
@Profile("saas")
public class IdentityRepository {

  private final JdbcClient jdbc;

  public IdentityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Finds the user an identity belongs to.
   *
   * @param issuer the identity provider's issuer
   * @param subject the user's id at that provider
   * @return the user id, if the identity is known
   */
  public Optional<UUID> findUserByIdentity(String issuer, String subject) {
    return jdbc.sql("SELECT user_id FROM user_identity WHERE issuer = :issuer AND subject = :sub")
        .param("issuer", issuer)
        .param("sub", subject)
        .query(UUID.class)
        .optional();
  }

  /**
   * Finds a user by e-mail address, ignoring case.
   *
   * @param email the address
   * @return the user id, if there is such a user
   */
  public Optional<UUID> findUserByEmail(String email) {
    return jdbc.sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
        .param("email", email)
        .query(UUID.class)
        .optional();
  }

  /**
   * Creates a user unless one with that address exists (another sign-in may have been faster).
   *
   * @param email the verified address
   * @param displayName the name for display, may be null
   * @return the id of the new user, or empty if the address was already taken
   */
  public Optional<UUID> createUser(String email, String displayName) {
    return jdbc.sql(
            "INSERT INTO app_user (email, display_name) VALUES (:email, :name)"
                + " ON CONFLICT (lower(email)) DO NOTHING RETURNING id")
        .param("email", email)
        .param("name", displayName)
        .query(UUID.class)
        .optional();
  }

  /**
   * Links an identity to a user; an existing link is left alone.
   *
   * @param issuer the identity provider's issuer
   * @param subject the user's id at that provider
   * @param userId the user
   */
  public void linkIdentity(String issuer, String subject, UUID userId) {
    jdbc.sql(
            "INSERT INTO user_identity (issuer, subject, user_id) VALUES (:issuer, :sub, :user)"
                + " ON CONFLICT (issuer, subject) DO NOTHING")
        .param("issuer", issuer)
        .param("sub", subject)
        .param("user", userId)
        .update();
  }

  /**
   * Returns a user's role in a tenant. Needs the user in the thread's tenant context.
   *
   * @param userId the user
   * @param tenantId the tenant
   * @return the role, or empty if the user is not a member
   */
  public Optional<Role> findRole(UUID userId, int tenantId) {
    return jdbc.sql("SELECT role FROM tenant_member WHERE user_id = :user AND tenant_id = :tenant")
        .param("user", userId)
        .param("tenant", tenantId)
        .query(String.class)
        .optional()
        .map(Role::valueOf);
  }

  /**
   * Returns a user's verified e-mail address (the one stored when the user was created or linked,
   * never an unverified claim of a token).
   *
   * @param userId the user
   * @return the address, if the user exists
   */
  public Optional<String> findEmail(UUID userId) {
    return jdbc.sql("SELECT email FROM app_user WHERE id = :user")
        .param("user", userId)
        .query(String.class)
        .optional();
  }

  /**
   * Returns what a user may do in a tenant: the role and whether the tenant is still open. Needs
   * the user in the thread's tenant context.
   *
   * @param userId the user
   * @param tenantId the tenant
   * @return the access, or empty if the user is not a member
   */
  public Optional<TenantAccess> findAccess(UUID userId, int tenantId) {
    return jdbc.sql(
            "SELECT m.role, t.status FROM tenant_member m JOIN tenant t ON t.id = m.tenant_id"
                + " WHERE m.user_id = :user AND m.tenant_id = :tenant")
        .param("user", userId)
        .param("tenant", tenantId)
        .query(
            (rs, row) ->
                new TenantAccess(Role.valueOf(rs.getString("role")), rs.getString("status")))
        .optional();
  }

  /**
   * Lists a user's tenants with the role in each. The {@code tenant} table shows a user the tenants
   * it belongs to, so the thread's context must carry this user.
   *
   * @param userId the user
   * @return the memberships ordered by tenant id
   */
  public List<Membership> findMemberships(UUID userId) {
    return jdbc.sql(
            "SELECT m.tenant_id, t.name, m.role FROM tenant_member m"
                + " JOIN tenant t ON t.id = m.tenant_id WHERE m.user_id = :user"
                + " ORDER BY m.tenant_id")
        .param("user", userId)
        .query(
            (rs, row) ->
                new Membership(
                    rs.getInt("tenant_id"),
                    rs.getString("name"),
                    Role.valueOf(rs.getString("role"))))
        .list();
  }

  /**
   * Makes a user a member of a tenant (or changes the role). Needs that tenant active in the
   * thread's tenant context.
   *
   * @param userId the user
   * @param tenantId the tenant
   * @param role the role
   */
  public void addMember(UUID userId, int tenantId, Role role) {
    jdbc.sql(
            "INSERT INTO tenant_member (tenant_id, user_id, role) VALUES (:tenant, :user, :role)"
                + " ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role")
        .param("tenant", tenantId)
        .param("user", userId)
        .param("role", role.name())
        .update();
  }
}
