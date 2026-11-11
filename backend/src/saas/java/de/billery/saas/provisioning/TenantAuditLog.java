package de.billery.saas.provisioning;

import de.billery.saas.identity.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The administration log of a tenant ({@code tenant_audit}): who invited, removed or changed whom,
 * who created or closed the company. Rows are written in the transaction of the change, with the
 * tenant and the acting user taken from the connection (column defaults). The application can only
 * add and read them.
 */
@Service
@Profile("saas")
public class TenantAuditLog {

  /** A company was created (sign-up). */
  public static final String TENANT_CREATED = "TENANT_CREATED";

  /** The company was closed and is read-only since. */
  public static final String TENANT_CLOSED = "TENANT_CLOSED";

  /** An address was invited. */
  public static final String MEMBER_INVITED = "MEMBER_INVITED";

  /** An open invitation was withdrawn. */
  public static final String INVITATION_REVOKED = "INVITATION_REVOKED";

  /** An invited person accepted and became a member. */
  public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";

  /** A member's role was changed. */
  public static final String ROLE_CHANGED = "ROLE_CHANGED";

  /** A member was removed by an owner or administrator. */
  public static final String MEMBER_REMOVED = "MEMBER_REMOVED";

  /** A member left the company. */
  public static final String MEMBER_LEFT = "MEMBER_LEFT";

  /** How many entries {@link #list()} returns at most, newest first. */
  static final int MAX_ENTRIES = 200;

  private final JdbcClient jdbc;

  /**
   * One entry.
   *
   * @param id the entry
   * @param occurredAt when
   * @param action one of the constants of this class
   * @param details what was affected, for example the address and the role
   * @param actor name or address of the acting user; null for system tasks
   */
  public record Entry(long id, Instant occurredAt, String action, String details, String actor) {}

  /**
   * @param jdbc the tenant-aware database access
   */
  public TenantAuditLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Adds an entry for the active tenant and the acting user. Call it inside the transaction of the
   * change, so both are stored or neither.
   *
   * @param action one of the constants of this class
   * @param details what was affected; may be null, cut to 500 characters
   */
  public void record(String action, String details) {
    String text = details == null || details.length() <= 500 ? details : details.substring(0, 500);
    jdbc.sql("INSERT INTO tenant_audit (action, details) VALUES (:action, :details)")
        .param("action", action)
        .param("details", text)
        .update();
  }

  /**
   * The newest entries of the active tenant. Owners and administrators only.
   *
   * @return at most {@value #MAX_ENTRIES} entries, newest first
   */
  public List<Entry> list() {
    Permissions.require(Role.OWNER, Role.ADMIN);
    return jdbc.sql(
            "SELECT a.id, a.occurred_at, a.action, a.details,"
                + " COALESCE(NULLIF(u.display_name, ''), u.email) AS actor"
                + " FROM tenant_audit a LEFT JOIN app_user u ON u.id = a.actor_id"
                + " WHERE a.tenant_id = :tenant ORDER BY a.id DESC LIMIT :limit")
        .param("tenant", Permissions.tenant())
        .param("limit", MAX_ENTRIES)
        .query(
            (rs, row) ->
                new Entry(
                    rs.getLong("id"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("action"),
                    rs.getString("details"),
                    rs.getString("actor")))
        .list();
  }

  /**
   * The address of a user, for the details of an entry.
   *
   * @param user the user
   * @return the address, or the id if the user is unknown
   */
  public String addressOf(UUID user) {
    return jdbc.sql("SELECT email FROM app_user WHERE id = :id")
        .param("id", user)
        .query(String.class)
        .optional()
        .orElse(user.toString());
  }
}
