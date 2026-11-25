package de.billery.saas.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import de.billery.saas.tenant.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Billery on a managed PostgreSQL whose admin user is no superuser (Scaleway, OVHcloud, Aiven),
 * #38: an admin with only {@code CREATEROLE} / {@code CREATEDB} creates the database and runs
 * {@code deploy/saas/postgres/managed-roles.sql}; the owner without {@code BYPASSRLS} migrates the
 * whole schema, can read every tenant the way {@code pg_dump} does, and the retention function it
 * owns still reaches every tenant; the application login passes {@link AppRoleGuard} and sees only
 * its tenant.
 *
 * <p>Runs in its own database of the test cluster ({@code POSTGRES_TEST_URL}). Roles are
 * cluster-wide: where {@code billery_app} exists already (created by the other test classes as
 * superuser), the test admin gets the admin option on it, which the admin of a fresh managed
 * database has because it creates the role itself.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class ManagedDatabaseTest {

  private static final String SUPERUSER_URL = System.getenv("POSTGRES_TEST_URL");
  private static final String DATABASE = "billery_managed_test";
  private static final String ADMIN = "billery_test_managed_admin";
  // Built at run time: throw-away credentials of roles that only live during this test.
  private static final String OWNER_PASSWORD = UUID.randomUUID().toString();
  private static final String LOGIN_PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

  @BeforeAll
  static void managedDatabase() throws Exception {
    dropEverything();
    superuser(
        "CREATE ROLE "
            + ADMIN
            + " LOGIN PASSWORD '"
            + ADMIN_PASSWORD
            + "' CREATEROLE CREATEDB NOSUPERUSER",
        "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billery_app') THEN"
            + " GRANT billery_app TO "
            + ADMIN
            + " WITH ADMIN OPTION, INHERIT FALSE, SET FALSE; END IF; END $$");
    try (Connection admin = DriverManager.getConnection(url("postgres", ADMIN, ADMIN_PASSWORD));
        Statement stmt = admin.createStatement()) {
      stmt.execute("CREATE DATABASE " + DATABASE);
    }
    try (Connection admin = DriverManager.getConnection(url(DATABASE, ADMIN, ADMIN_PASSWORD));
        Statement stmt = admin.createStatement()) {
      for (String sql : managedRolesScript()) {
        stmt.execute(sql);
      }
    }
    Database.setDbPath(url(DATABASE, "billery_owner", OWNER_PASSWORD));
    SchemaMigrator.migrate();
    owner(
        "INSERT INTO tenant (id, name) VALUES (2, 'Zwei')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (1, 'Eins', 'A')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (2, 'Zwei', 'B')",
        "INSERT INTO app_user (id, email) VALUES ('00000000-0000-0000-0000-0000000000aa', 'a@x.de')",
        "INSERT INTO sign_in_log (tenant_id, user_id, session_hash, occurred_at) VALUES"
            + " (1, '00000000-0000-0000-0000-0000000000aa', repeat('a', 64),"
            + " CURRENT_TIMESTAMP - INTERVAL '100 days'),"
            + " (2, '00000000-0000-0000-0000-0000000000aa', repeat('b', 64),"
            + " CURRENT_TIMESTAMP - INTERVAL '100 days'),"
            + " (2, '00000000-0000-0000-0000-0000000000aa', repeat('c', 64), CURRENT_TIMESTAMP)");
  }

  @AfterAll
  static void cleanUp() throws SQLException {
    TenantContext.clear();
    TestDatabaseConfig.resetDatabaseConfig();
    dropEverything();
  }

  /** The SQL file of the runbook, with the psql variables and meta-commands filled in. */
  private static List<String> managedRolesScript() throws IOException {
    Path file = Path.of("..", "deploy", "saas", "postgres", "managed-roles.sql");
    String sql =
        Files.readString(file, StandardCharsets.UTF_8)
            .replace(":'owner_password'", "'" + OWNER_PASSWORD + "'")
            .replace(":'login_password'", "'" + LOGIN_PASSWORD + "'")
            .replace(":\"dbname\"", DATABASE)
            // psql's \gexec runs the result of the query; here the same as a DO block
            .replace(
                "SELECT 'CREATE ROLE billery_app NOLOGIN NOSUPERUSER NOBYPASSRLS'\n"
                    + "  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billery_app')"
                    + " \\gexec",
                "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billery_app')"
                    + " THEN CREATE ROLE billery_app NOLOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$;");
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inBody = false;
    for (String line : sql.split("\n")) {
      if (!inBody && (line.startsWith("--") || line.isBlank())) {
        continue;
      }
      current.append(line).append('\n');
      // A DO block's body ($$ … $$) contains semicolons of its own.
      if ((line.split("\\$\\$", -1).length - 1) % 2 == 1) {
        inBody = !inBody;
      }
      if (!inBody && line.trim().endsWith(";")) {
        statements.add(current.toString());
        current.setLength(0);
      }
    }
    assertTrue(current.toString().isBlank(), "unterminated statement: " + current);
    return statements;
  }

  @Test
  void theScriptRefusesAnExistingAppRoleWithMoreRights() throws Exception {
    String check =
        managedRolesScript().stream()
            .filter(statement -> statement.contains("RAISE EXCEPTION"))
            .findFirst()
            .orElseThrow();
    try (Connection admin = DriverManager.getConnection(url(DATABASE, ADMIN, ADMIN_PASSWORD));
        Statement stmt = admin.createStatement()) {
      stmt.execute(check); // the plain group role passes
      superuser("ALTER ROLE billery_app BYPASSRLS");
      try {
        SQLException e =
            org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class, () -> stmt.execute(check));
        assertTrue(e.getMessage().contains("plain NOLOGIN role"), e.getMessage());
      } finally {
        superuser("ALTER ROLE billery_app NOBYPASSRLS");
      }
    }
  }

  @Test
  void theOwnerHasNoBypassAndNoTableForcesRowLevelSecurity() throws SQLException {
    assertEquals(
        List.of("false|false|false|false"),
        owner1(
            "SELECT rolsuper || '|' || rolbypassrls || '|' || rolcreaterole || '|' || rolcreatedb"
                + " FROM pg_roles WHERE rolname = 'billery_owner'"));
    assertEquals(
        List.of(),
        owner1(
            "SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace"
                + " AND relforcerowsecurity"),
        "V10 removed FORCE everywhere");
    assertEquals(
        List.of("billery_owner"),
        owner1("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'"));
  }

  @Test
  void theOwnerReadsEveryTenantTheWayPgDumpDoes() throws SQLException {
    // pg_dump switches row security off and fails ("query would be affected by row-level security
    // policy") for any table the role cannot read completely.
    List<String> tables =
        owner1("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename");
    assertTrue(tables.contains("customer") && tables.contains("sign_in_log"), tables.toString());
    try (Connection conn =
            DriverManager.getConnection(url(DATABASE, "billery_owner", OWNER_PASSWORD));
        Statement stmt = conn.createStatement()) {
      stmt.execute("SET row_security = off");
      for (String table : tables) {
        try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM public.\"" + table + "\"")) {
          rs.next();
        }
      }
      try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM customer")) {
        rs.next();
        assertEquals(2, rs.getInt(1), "both tenants' customers");
      }
    }
  }

  @Test
  void theLoginPassesTheGuardAndSeesOnlyItsTenant() throws SQLException {
    try (Connection login =
        DriverManager.getConnection(url(DATABASE, "billery_login", LOGIN_PASSWORD))) {
      assertEquals(List.of(), AppRoleGuard.problems(login));
    }
    DriverManagerDataSource plain =
        new DriverManagerDataSource(url(DATABASE, "billery_login", LOGIN_PASSWORD));
    TenantAwareDataSource app = new TenantAwareDataSource(plain);
    TenantContext.set(2, null);
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT first_name FROM customer")) {
      List<String> names = new ArrayList<>();
      while (rs.next()) {
        names.add(rs.getString(1));
      }
      assertEquals(List.of("Zwei"), names);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void theRetentionFunctionStillReachesEveryTenant() throws SQLException {
    // prune_sign_in_log() is SECURITY DEFINER, owned by billery_owner. With FORCE and without
    // BYPASSRLS it would silently delete nothing; without FORCE it reaches every tenant.
    DriverManagerDataSource plain =
        new DriverManagerDataSource(url(DATABASE, "billery_login", LOGIN_PASSWORD));
    TenantContext.set(1, null);
    try (Connection conn = new TenantAwareDataSource(plain).getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT prune_sign_in_log()")) {
      rs.next();
      assertEquals(2, rs.getInt(1), "the expired rows of both tenants");
    } finally {
      TenantContext.clear();
    }
    assertEquals(List.of("1"), owner1("SELECT count(*) FROM sign_in_log"), "the recent one stays");
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static String url(String database, String user, String password) {
    String base = SUPERUSER_URL.replaceAll("\\?.*$", "");
    String withDatabase = base.substring(0, base.lastIndexOf('/') + 1) + database;
    return withDatabase + "?user=" + user + "&password=" + password;
  }

  private static void superuser(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(SUPERUSER_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static void owner(String... statements) throws SQLException {
    try (Connection conn =
            DriverManager.getConnection(url(DATABASE, "billery_owner", OWNER_PASSWORD));
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static List<String> owner1(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection conn =
            DriverManager.getConnection(url(DATABASE, "billery_owner", OWNER_PASSWORD));
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  private static void dropEverything() throws SQLException {
    superuser(
        "DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)",
        "DROP ROLE IF EXISTS billery_login",
        "DROP ROLE IF EXISTS billery_owner",
        "DROP ROLE IF EXISTS " + ADMIN);
  }
}
