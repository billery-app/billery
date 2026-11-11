package de.billery.saas.provisioning;

import de.billery.saas.api.ApiError;
import de.billery.saas.identity.Role;
import de.billery.saas.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations by e-mail address. The invitation only names an address and a role; it is accepted by
 * the person who signs in with that <b>verified</b> address (the address stored with their user,
 * never an unverified claim), so no token has to be mailed and none can be intercepted. Invitations
 * expire after 14 days.
 */
@Service
@Profile("saas")
public class InvitationService {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final TenantAuditLog audit;

  /**
   * @param jdbc the tenant-aware database access
   * @param transactions runs each change with its audit entry as one
   * @param audit the administration log
   */
  public InvitationService(
      JdbcClient jdbc, TransactionTemplate transactions, TenantAuditLog audit) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.audit = audit;
  }

  /**
   * An open invitation of the active tenant, as its administrators see it.
   *
   * @param id the invitation
   * @param email the invited address
   * @param role the role offered
   * @param createdAt when it was created
   * @param expiresAt when it lapses
   */
  public record Invitation(
      UUID id, String email, Role role, Instant createdAt, Instant expiresAt) {}

  /**
   * An invitation addressed to the signed-in user.
   *
   * @param id the invitation
   * @param tenantId the tenant offering membership
   * @param tenantName its name
   * @param role the role offered
   */
  public record Pending(UUID id, int tenantId, String tenantName, Role role) {}

  /**
   * Invites an address to the active tenant.
   *
   * @param email the address
   * @param role the role offered; the acting user must be allowed to hand it out
   * @return the invitation
   * @throws ApiError 400 for an invalid address, 403 if the role may not be handed out, 409 if the
   *     address is already a member or already invited
   */
  public Invitation invite(String email, Role role) {
    Permissions.requireManage(role);
    String address = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    if (address.length() > 320 || !address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
      throw new ApiError(HttpStatus.BAD_REQUEST, "Enter a valid e-mail address");
    }
    int tenant = Permissions.tenant();
    boolean member =
        jdbc.sql(
                    "SELECT COUNT(*) FROM tenant_member m JOIN app_user u ON u.id = m.user_id"
                        + " WHERE m.tenant_id = :tenant AND lower(u.email) = :email")
                .param("tenant", tenant)
                .param("email", address)
                .query(Integer.class)
                .single()
            > 0;
    if (member) {
      throw new ApiError(HttpStatus.CONFLICT, "This person is already a member");
    }
    return transactions.execute(
        status -> {
          Invitation invitation;
          try {
            invitation =
                jdbc.sql(
                        "INSERT INTO tenant_invitation (email, role, invited_by)"
                            + " VALUES (:email, :role, :by)"
                            + " RETURNING id, email, role, created_at, expires_at")
                    .param("email", address)
                    .param("role", role.name())
                    .param("by", Permissions.user())
                    .query((rs, row) -> map(rs))
                    .single();
          } catch (DuplicateKeyException e) {
            throw new ApiError(HttpStatus.CONFLICT, "This address has already been invited", e);
          }
          audit.record(TenantAuditLog.MEMBER_INVITED, address + " (" + role.name() + ")");
          return invitation;
        });
  }

  /**
   * Lists the open invitations of the active tenant.
   *
   * @return the invitations, oldest first
   */
  public List<Invitation> openInvitations() {
    Permissions.require(Role.OWNER, Role.ADMIN);
    return jdbc.sql(
            "SELECT id, email, role, created_at, expires_at FROM tenant_invitation"
                + " WHERE tenant_id = :tenant AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP"
                + " ORDER BY created_at")
        .param("tenant", Permissions.tenant())
        .query((rs, row) -> map(rs))
        .list();
  }

  /**
   * Withdraws an open invitation of the active tenant.
   *
   * @param id the invitation
   * @throws ApiError 404 if there is no such open invitation, 403 if its role may not be managed
   */
  public void revoke(UUID id) {
    Permissions.require(Role.OWNER, Role.ADMIN);
    int tenant = Permissions.tenant();
    transactions.executeWithoutResult(
        status -> {
          String[] open =
              jdbc.sql(
                      "SELECT role, email FROM tenant_invitation WHERE id = :id"
                          + " AND tenant_id = :tenant AND accepted_at IS NULL")
                  .param("id", id)
                  .param("tenant", tenant)
                  .query((rs, row) -> new String[] {rs.getString("role"), rs.getString("email")})
                  .optional()
                  .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such invitation"));
          Permissions.requireManage(Role.valueOf(open[0]));
          jdbc.sql("DELETE FROM tenant_invitation WHERE id = :id AND tenant_id = :tenant")
              .param("id", id)
              .param("tenant", tenant)
              .update();
          audit.record(TenantAuditLog.INVITATION_REVOKED, open[1] + " (" + open[0] + ")");
        });
  }

  /**
   * Lists the open invitations addressed to the acting user's verified address.
   *
   * @return the invitations with the offering tenant's name
   */
  public List<Pending> pendingForCurrentUser() {
    String email = TenantContext.userEmail();
    if (email == null) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT i.id, i.tenant_id, t.name, i.role FROM tenant_invitation i"
                + " JOIN tenant t ON t.id = i.tenant_id"
                + " WHERE lower(i.email) = lower(:email) AND i.accepted_at IS NULL"
                + " AND i.expires_at > CURRENT_TIMESTAMP ORDER BY i.created_at")
        .param("email", email)
        .query(
            (rs, row) ->
                new Pending(
                    rs.getObject("id", UUID.class),
                    rs.getInt("tenant_id"),
                    rs.getString("name"),
                    Role.valueOf(rs.getString("role"))))
        .list();
  }

  /**
   * Accepts an invitation addressed to the acting user: they become a member of the tenant.
   *
   * @param invitationId the invitation
   * @return the tenant the user joined
   * @throws ApiError 404 if there is no such open invitation for the user's address, 409 if the
   *     tenant is no longer active
   */
  public int accept(UUID invitationId) {
    UUID user = Permissions.user();
    String email = TenantContext.userEmail();
    return transactions.execute(
        status -> {
          OpenInvitation invitation =
              jdbc.sql(
                      "SELECT i.tenant_id, i.role, t.status FROM tenant_invitation i"
                          + " JOIN tenant t ON t.id = i.tenant_id"
                          + " WHERE i.id = :id AND lower(i.email) = lower(:email)"
                          + " AND i.accepted_at IS NULL AND i.expires_at > CURRENT_TIMESTAMP"
                          + " FOR UPDATE OF i")
                  .param("id", invitationId)
                  .param("email", email == null ? "" : email)
                  .query(
                      (rs, row) ->
                          new OpenInvitation(
                              rs.getInt("tenant_id"), rs.getString("role"), rs.getString("status")))
                  .optional()
                  .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such invitation"));
          if (!"ACTIVE".equals(invitation.status())) {
            throw new ApiError(HttpStatus.CONFLICT, "The tenant is closed or suspended");
          }
          int tenant = invitation.tenantId();
          // The invitation is the proof of the right to join this tenant: act as it for the
          // rest of this transaction.
          jdbc.sql("SELECT set_config('app.tenant_id', :id, true)")
              .param("id", Integer.toString(tenant))
              .query(String.class)
              .single();
          jdbc.sql(
                  "INSERT INTO tenant_member (tenant_id, user_id, role)"
                      + " VALUES (:tenant, :user, :role)"
                      + " ON CONFLICT (tenant_id, user_id) DO NOTHING")
              .param("tenant", tenant)
              .param("user", user)
              .param("role", invitation.role())
              .update();
          jdbc.sql("UPDATE tenant_invitation SET accepted_at = CURRENT_TIMESTAMP WHERE id = :id")
              .param("id", invitationId)
              .update();
          audit.record(TenantAuditLog.INVITATION_ACCEPTED, email + " (" + invitation.role() + ")");
          return tenant;
        });
  }

  private record OpenInvitation(int tenantId, String role, String status) {}

  private static Invitation map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Invitation(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        Role.valueOf(rs.getString("role")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }
}
