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
 * Refuses to start the SaaS backend when its database role would bypass the tenant isolation.
 *
 * <p>The isolation is forced row-level security. A role that is a superuser or has {@code
 * BYPASSRLS} skips every policy, so every tenant would see every other tenant's data, without any
 * error, and so does a role that may switch to such a role ({@code SET ROLE}, membership). A role
 * that owns a table, or may act as its owner, can switch the policies off. All of these are
 * configuration mistakes (connecting with the migration account, a wrong {@code DB_USER}) that
 * nothing else would notice. The backend must connect as a login role that is a member of {@code
 * billery_app} and nothing more (see {@code docs/operations/saas-deployment.md}).
 *
 * <p>The check runs once while the application context starts, before the server accepts requests.
 * It reads only the system catalogs, so it needs no tenant.
 */
@Component
@Profile("saas")
public class AppRoleGuard {

  /** The group role whose members the application connects as (created by the SaaS migrations). */
  static final String APP_ROLE = "billery_app";

  /** The tables of schema public the current role owns or may act as the owner of. */
  private static final String OWNED_TABLES =
      " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
          + " AND pg_has_role(current_user, c.relowner, 'MEMBER')";

  private static final String ROLE_FACTS =
      "SELECT current_user, r.rolsuper, r.rolbypassrls,"
          + " COALESCE((SELECT pg_has_role(current_user, a.oid, 'MEMBER')"
          + " FROM pg_roles a WHERE a.rolname = '"
          + APP_ROLE
          + "'), false),"
          + " (SELECT count(*) FROM pg_class c"
          + OWNED_TABLES
          + "),"
          + " (SELECT string_agg(t.relname, ', ') FROM (SELECT c.relname FROM pg_class c"
          + OWNED_TABLES
          + " ORDER BY c.relname LIMIT 5) t),"
          // roles it may switch to with SET ROLE that skip row-level security
          + " (SELECT string_agg(o.rolname || CASE WHEN o.rolsuper THEN ' (superuser)'"
          + " ELSE ' (BYPASSRLS)' END, ', ' ORDER BY o.rolname) FROM pg_roles o"
          + " WHERE o.rolname <> current_user AND (o.rolsuper OR o.rolbypassrls)"
          + " AND pg_has_role(current_user, o.oid, 'MEMBER'))"
          + " FROM pg_roles r WHERE r.rolname = current_user";

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
   * Checks the role of the application's connections and stops the start if it could bypass the
   * tenant isolation.
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
            "The SaaS backend refuses to start: its database role would bypass the tenant"
                + " isolation ("
                + String.join("; ", problems)
                + "). Connect as a login role that is a member of "
                + APP_ROLE
                + " and nothing else (DB_USER); migrations run as DB_MIGRATION_USER."
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
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(ROLE_FACTS)) {
      if (!rs.next()) {
        problems.add("the current role is not in pg_roles");
        return problems;
      }
      String role = rs.getString(1);
      if (rs.getBoolean(2)) {
        problems.add("role " + role + " is a superuser");
      }
      if (rs.getBoolean(3)) {
        problems.add("role " + role + " has BYPASSRLS");
      }
      if (!rs.getBoolean(4)) {
        problems.add("role " + role + " is not a member of " + APP_ROLE);
      }
      long owned = rs.getLong(5);
      if (owned > 0) {
        problems.add(
            "role "
                + role
                + " owns or may act as the owner of "
                + owned
                + " table(s), e.g. "
                + rs.getString(6));
      }
      String privileged = rs.getString(7);
      if (privileged != null) {
        problems.add("role " + role + " may switch (SET ROLE) to " + privileged);
      }
    }
    return problems;
  }
}
