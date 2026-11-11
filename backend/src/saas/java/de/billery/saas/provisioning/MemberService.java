package de.billery.saas.provisioning;

import de.billery.saas.api.ApiError;
import de.billery.saas.identity.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Members of the active tenant and their roles. An owner manages every role, an admin only
 * accountants and readers; anyone may leave; the last owner can neither leave, be demoted nor be
 * removed, so a tenant is never left without one.
 */
@Service
@Profile("saas")
public class MemberService {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final TenantAuditLog audit;

  /**
   * @param jdbc the tenant-aware database access
   * @param transactions runs each change with its audit entry as one
   * @param audit the administration log
   */
  public MemberService(JdbcClient jdbc, TransactionTemplate transactions, TenantAuditLog audit) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.audit = audit;
  }

  /**
   * A member of the active tenant.
   *
   * @param userId the user
   * @param email the user's verified address
   * @param displayName the user's name, may be null
   * @param role the role in the tenant
   */
  public record Member(UUID userId, String email, String displayName, Role role) {}

  /**
   * Lists the members of the active tenant.
   *
   * @return the members ordered by address
   */
  public List<Member> list() {
    Permissions.require(Role.OWNER, Role.ADMIN);
    return jdbc.sql(
            "SELECT m.user_id, u.email, u.display_name, m.role FROM tenant_member m"
                + " JOIN app_user u ON u.id = m.user_id WHERE m.tenant_id = :tenant"
                + " ORDER BY lower(u.email)")
        .param("tenant", Permissions.tenant())
        .query(
            (rs, row) ->
                new Member(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    Role.valueOf(rs.getString("role"))))
        .list();
  }

  /**
   * Changes a member's role.
   *
   * @param target the member
   * @param newRole the new role
   * @throws ApiError 403 if the acting user may not manage the old or the new role, 404 for a
   *     non-member, 409 if this would leave the tenant without an owner
   */
  public void changeRole(UUID target, Role newRole) {
    Permissions.require(Role.OWNER, Role.ADMIN);
    transactions.executeWithoutResult(
        status -> {
          Role current = lockedRoleOf(target);
          Permissions.requireManage(current);
          Permissions.requireManage(newRole);
          if (current == Role.OWNER && newRole != Role.OWNER && owners() < 2) {
            throw new ApiError(HttpStatus.CONFLICT, "The last owner cannot be demoted");
          }
          jdbc.sql(
                  "UPDATE tenant_member SET role = :role WHERE tenant_id = :tenant AND user_id = :user")
              .param("role", newRole.name())
              .param("tenant", Permissions.tenant())
              .param("user", target)
              .update();
          if (current != newRole) {
            audit.record(
                TenantAuditLog.ROLE_CHANGED,
                audit.addressOf(target) + ": " + current.name() + " → " + newRole.name());
          }
        });
  }

  /**
   * Removes a member from the tenant; every member may remove themselves.
   *
   * @param target the member
   * @throws ApiError 403 if the acting user may not manage that member's role, 404 for a
   *     non-member, 409 for the last owner
   */
  public void remove(UUID target) {
    boolean self = target.equals(Permissions.user());
    if (!self) {
      Permissions.require(Role.OWNER, Role.ADMIN);
    }
    transactions.executeWithoutResult(
        status -> {
          Role current = lockedRoleOf(target);
          if (!self) {
            Permissions.requireManage(current);
          }
          if (current == Role.OWNER && owners() < 2) {
            throw new ApiError(HttpStatus.CONFLICT, "The last owner cannot be removed");
          }
          jdbc.sql("DELETE FROM tenant_member WHERE tenant_id = :tenant AND user_id = :user")
              .param("tenant", Permissions.tenant())
              .param("user", target)
              .update();
          audit.record(
              self ? TenantAuditLog.MEMBER_LEFT : TenantAuditLog.MEMBER_REMOVED,
              audit.addressOf(target) + " (" + current.name() + ")");
        });
  }

  /** The member's role, with the tenant's owners locked so concurrent changes queue up. */
  private Role lockedRoleOf(UUID target) {
    jdbc.sql(
            "SELECT user_id FROM tenant_member WHERE tenant_id = :tenant AND role = 'OWNER' FOR UPDATE")
        .param("tenant", Permissions.tenant())
        .query(UUID.class)
        .list();
    return jdbc.sql("SELECT role FROM tenant_member WHERE tenant_id = :tenant AND user_id = :user")
        .param("tenant", Permissions.tenant())
        .param("user", target)
        .query(String.class)
        .optional()
        .map(Role::valueOf)
        .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such member"));
  }

  private int owners() {
    return jdbc.sql(
            "SELECT COUNT(*) FROM tenant_member WHERE tenant_id = :tenant AND role = 'OWNER'")
        .param("tenant", Permissions.tenant())
        .query(Integer.class)
        .single();
  }
}
