package de.billery.saas.db;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the SaaS backend unless its database role is exactly what the tenant isolation
 * relies on: a plain login role that is a member of {@code billery_app} and nothing else.
 *
 * <p>The isolation is forced row-level security, and many privileges reach past it: a superuser or
 * {@code BYPASSRLS} skips every policy, {@code REPLICATION} can read every change through logical
 * decoding, {@code CREATEROLE} and the predefined {@code pg_*} roles open further ways, a role that
 * owns a table can switch the policies off, and a member of any such role may switch to it with
 * {@code SET ROLE}. Instead of forbidding these one by one, the guard allows only the expected
 * shape and refuses everything else:
 *
 * <ul>
 *   <li>the role has no attribute besides {@code LOGIN} / {@code INHERIT} (no {@code SUPERUSER},
 *       {@code BYPASSRLS}, {@code REPLICATION}, {@code CREATEROLE}, {@code CREATEDB});
 *   <li>the only role it is a member of, directly or indirectly, is {@code billery_app}, and that
 *       role has no such attribute either (so it can act as no table owner and no privileged or
 *       predefined role);
 *   <li>it owns no table in {@code public};
 *   <li>the connection acts as the role it signed in as ({@code current_user = session_user}): a
 *       switched role ({@code SET ROLE}, {@code options=-c role=…} in {@code DB_URL}, {@code ALTER
 *       ROLE … SET role}) could switch back with {@code RESET ROLE}. Everything above is therefore
 *       checked for {@code session_user}.
 * </ul>
 *
 * <p>These are configuration mistakes (connecting with the migration account, a wrong {@code
 * DB_USER}, a role with too many rights) that nothing else would notice, see {@code
 * docs/operations/saas-deployment.md}. The check runs once while the application context starts,
 * before the server accepts requests; it reads only the system catalogs, so it needs no tenant.
 */
@Component
@Profile("saas")
public class AppRoleGuard {

  /** The group role whose members the application connects as (created by the SaaS migrations). */
  static final String APP_ROLE = "billery_app";

  /** How many role or table names a message lists at most. */
  private static final int SHOWN = 5;

  /** The attributes the role and {@code billery_app} must not have, as columns of pg_roles. */
  private static final String ATTRIBUTE_COLUMNS =
      "rolsuper, rolbypassrls, rolreplication, rolcreaterole, rolcreatedb";

  private static final String[] ATTRIBUTE_NAMES = {
    "SUPERUSER", "BYPASSRLS", "REPLICATION", "CREATEROLE", "CREATEDB"
  };

  /** The role the connection signed in as, the role it acts as, and the attributes. */
  private static final String SESSION_ROLE =
      "SELECT session_user, current_user, "
          + ATTRIBUTE_COLUMNS
          + " FROM pg_roles WHERE rolname = session_user";

  /** Every role the signed-in role is a member of, directly or indirectly, with its attributes. */
  private static final String MEMBERSHIPS =
      "SELECT rolname, "
          + ATTRIBUTE_COLUMNS
          + " FROM pg_roles WHERE rolname <> session_user"
          + " AND pg_has_role(session_user, oid, 'MEMBER') ORDER BY rolname";

  /** The tables of schema public the signed-in role owns. */
  private static final String OWNED_TABLES =
      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
          + " AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = session_user)"
          + " ORDER BY c.relname";

  private final DataSource dataSource;

  /**
   * Creates the guard.
   *
   * @param dataSource the application's (tenant-aware) pool, i.e. the role the requests use
   */
  public AppRoleGuard(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Checks the role of the application's connections and stops the start if it is anything but a
   * plain member of {@code billery_app}.
   *
   * @throws IllegalStateException naming every problem found
   * @throws SQLException if the database cannot be asked
   */
  @PostConstruct
  public void verify() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      List<String> problems = problems(connection);
      if (!problems.isEmpty()) {
        throw new IllegalStateException(
            "The SaaS backend refuses to start: its database role could bypass the tenant"
                + " isolation ("
                + String.join("; ", problems)
                + "). Connect directly as a login role without special attributes whose only"
                + " membership is "
                + APP_ROLE
                + " (DB_USER); migrations run as DB_MIGRATION_USER."
                + " See docs/operations/saas-deployment.md.");
      }
    }
  }

  /**
   * Lists why the role of {@code connection} must not serve requests; empty when it is fit.
   *
   * @param connection a connection as the role to check
   * @return the problems, each naming the role and the reason
   * @throws SQLException if the catalogs cannot be read
   */
  static List<String> problems(Connection connection) throws SQLException {
    List<String> problems = new ArrayList<>();
    try (Statement stmt = connection.createStatement()) {
      String role;
      try (ResultSet rs = stmt.executeQuery(SESSION_ROLE)) {
        if (!rs.next()) {
          problems.add("the signed-in role is not in pg_roles");
          return problems;
        }
        role = rs.getString(1);
        String acting = rs.getString(2);
        List<String> attributes = attributes(rs, 3);
        if (!attributes.isEmpty()) {
          problems.add("role " + role + " has " + String.join(", ", attributes));
        }
        if (!role.equals(acting)) {
          problems.add(
              "the connection signed in as "
                  + role
                  + " but acts as "
                  + acting
                  + " (SET ROLE, a role in the URL's options or ALTER ROLE ... SET role);"
                  + " RESET ROLE would return to "
                  + role);
        }
      }

      boolean appMember = false;
      List<String> others = new ArrayList<>();
      try (ResultSet rs = stmt.executeQuery(MEMBERSHIPS)) {
        while (rs.next()) {
          String member = rs.getString(1);
          List<String> attributes = attributes(rs, 2);
          if (APP_ROLE.equals(member)) {
            appMember = true;
            if (!attributes.isEmpty()) {
              problems.add("role " + APP_ROLE + " has " + String.join(", ", attributes));
            }
          } else {
            others.add(
                attributes.isEmpty()
                    ? member
                    : member + " (" + String.join(", ", attributes) + ")");
          }
        }
      }
      if (!appMember) {
        problems.add("role " + role + " is not a member of " + APP_ROLE);
      }
      if (!others.isEmpty()) {
        problems.add(
            "role "
                + role
                + " may also act as (membership, SET ROLE) "
                + shortList(others)
                + "; only "
                + APP_ROLE
                + " is allowed");
      }

      List<String> owned = new ArrayList<>();
      try (ResultSet rs = stmt.executeQuery(OWNED_TABLES)) {
        while (rs.next()) {
          owned.add(rs.getString(1));
        }
      }
      if (!owned.isEmpty()) {
        problems.add("role " + role + " owns " + owned.size() + " table(s): " + shortList(owned));
      }
    }
    return problems;
  }

  /** The names of the special attributes set in the row, starting at column {@code first}. */
  private static List<String> attributes(ResultSet rs, int first) throws SQLException {
    List<String> set = new ArrayList<>();
    for (int i = 0; i < ATTRIBUTE_NAMES.length; i++) {
      if (rs.getBoolean(first + i)) {
        set.add(ATTRIBUTE_NAMES[i]);
      }
    }
    return set;
  }

  /** The first few names, and how many more there are. */
  private static String shortList(List<String> names) {
    String shown = String.join(", ", names.subList(0, Math.min(SHOWN, names.size())));
    return names.size() > SHOWN ? shown + " and " + (names.size() - SHOWN) + " more" : shown;
  }
}
