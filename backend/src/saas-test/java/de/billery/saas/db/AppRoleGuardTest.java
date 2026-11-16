package de.billery.saas.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.data.TestDatabaseConfig;
import de.billery.saas.SaasTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link AppRoleGuard}: the role the production backend connects as passes; anything beyond a plain
 * member of {@code billery_app} is refused, each with its reason.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class AppRoleGuardTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");

  /** The login role every refusal test creates and checks. */
  private static final String LOGIN = "billery_guard_login";

  /** Roles a test creates; dropped after each test. */
  private static final List<String> ROLES =
      List.of(LOGIN, "billery_guard_tableowner", "billery_guard_privileged");

  @BeforeAll
  static void setup() throws SQLException {
    TestDatabaseConfig.setupH2Database(); // migrates and creates the application login role
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @AfterEach
  void dropRoles() throws SQLException {
    admin("DROP TABLE IF EXISTS billery_guard_scratch");
    for (String role : ROLES) {
      admin("DROP ROLE IF EXISTS " + role);
    }
  }

  @Test
  void theApplicationRoleMayServeRequests() throws SQLException {
    DataSource app = SaasTestSupport.appRoleDataSource(ADMIN_URL);
    try (Connection connection = app.getConnection()) {
      assertEquals(List.of(), AppRoleGuard.problems(connection));
    }
    assertDoesNotThrow(() -> new AppRoleGuard(app).verify());
  }

  @Test
  void theMigrationOrAdminAccountIsRefused() throws SQLException {
    String superuser = "role " + adminRole() + " has SUPERUSER";
    try (Connection connection = DriverManager.getConnection(ADMIN_URL)) {
      List<String> problems = AppRoleGuard.problems(connection);
      assertTrue(problems.stream().anyMatch(p -> p.startsWith(superuser)), problems::toString);
    }
    DataSource admin = new DriverManagerDataSource(ADMIN_URL);
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new AppRoleGuard(admin).verify());
    assertTrue(refused.getMessage().contains("refuses to start"), refused.getMessage());
  }

  @Test
  void aRoleWithBypassRlsIsRefusedEvenAsMemberOfTheAppRole() throws SQLException {
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g' BYPASSRLS IN ROLE billery_app");

    assertEquals(List.of("role " + LOGIN + " has BYPASSRLS"), problemsOfLogin());
  }

  @Test
  void aRoleWithReplicationIsRefused() throws SQLException {
    // logical decoding reads every change of every table, past row-level security
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g' REPLICATION IN ROLE billery_app");

    assertEquals(List.of("role " + LOGIN + " has REPLICATION"), problemsOfLogin());
  }

  @Test
  void aRoleOutsideTheAppRoleIsRefused() throws SQLException {
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g'");

    assertEquals(List.of("role " + LOGIN + " is not a member of billery_app"), problemsOfLogin());
  }

  @Test
  void aRoleThatOwnsATableIsRefused() throws SQLException {
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g' IN ROLE billery_app");
    admin("CREATE TABLE billery_guard_scratch (id INT)");
    admin("ALTER TABLE billery_guard_scratch OWNER TO " + LOGIN);

    assertEquals(
        List.of(
            "role " + LOGIN + " owns or may act as the owner of 1 table(s): billery_guard_scratch"),
        problemsOfLogin());
  }

  @Test
  void aRoleThatMayActAsATableOwnerIsRefused() throws SQLException {
    // an ordinary role that owns a table; membership gives its rights (SET ROLE)
    admin("CREATE ROLE billery_guard_tableowner NOLOGIN");
    admin("CREATE TABLE billery_guard_scratch (id INT)");
    admin("ALTER TABLE billery_guard_scratch OWNER TO billery_guard_tableowner");
    admin(
        "CREATE ROLE "
            + LOGIN
            + " LOGIN PASSWORD 'g' IN ROLE billery_app, billery_guard_tableowner");

    assertEquals(
        List.of(
            "role "
                + LOGIN
                + " may also act as (membership, SET ROLE) billery_guard_tableowner;"
                + " only billery_app is allowed",
            "role " + LOGIN + " owns or may act as the owner of 1 table(s): billery_guard_scratch"),
        problemsOfLogin());
  }

  @Test
  void aTableOwnedByTheAppRoleItselfIsRefused() throws SQLException {
    // the login may SET ROLE billery_app and, as the owner, switch the policies off
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g' IN ROLE billery_app");
    admin("CREATE TABLE billery_guard_scratch (id INT)");
    admin("ALTER TABLE billery_guard_scratch OWNER TO billery_app");

    assertEquals(
        List.of(
            "role " + LOGIN + " owns or may act as the owner of 1 table(s): billery_guard_scratch"),
        problemsOfLogin());
  }

  @Test
  void aRoleThatMaySwitchToARoleWithBypassRlsIsRefused() throws SQLException {
    admin("CREATE ROLE billery_guard_privileged NOLOGIN BYPASSRLS");
    admin(
        "CREATE ROLE "
            + LOGIN
            + " LOGIN PASSWORD 'g' IN ROLE billery_app, billery_guard_privileged");

    assertEquals(
        List.of(
            "role "
                + LOGIN
                + " may also act as (membership, SET ROLE) billery_guard_privileged (BYPASSRLS);"
                + " only billery_app is allowed"),
        problemsOfLogin());
  }

  @Test
  void aMemberOfAPredefinedRoleIsRefused() throws SQLException {
    // e.g. reading any file of the server, which no tenant data check covers
    admin("CREATE ROLE " + LOGIN + " LOGIN PASSWORD 'g' IN ROLE billery_app, pg_read_server_files");

    assertEquals(
        List.of(
            "role "
                + LOGIN
                + " may also act as (membership, SET ROLE) pg_read_server_files;"
                + " only billery_app is allowed"),
        problemsOfLogin());
  }

  @Test
  void aConnectionThatSignedInAsAdminAndSwitchedToTheAppRoleIsRefused() throws SQLException {
    // the role set in the connection options, as an operator might put it into DB_URL
    Properties options = new Properties();
    options.setProperty("options", "-c role=" + SaasTestSupport.APP_LOGIN);
    try (Connection connection = DriverManager.getConnection(ADMIN_URL, options)) {
      List<String> problems = AppRoleGuard.problems(connection);
      String admin = adminRole();
      assertTrue(
          problems.contains(
              "the connection signed in as "
                  + admin
                  + " but acts as "
                  + SaasTestSupport.APP_LOGIN
                  + " (SET ROLE, a role in the URL's options or ALTER ROLE ... SET role);"
                  + " RESET ROLE would return to "
                  + admin),
          problems::toString);
      assertTrue(
          problems.stream().anyMatch(p -> p.startsWith("role " + admin + " has SUPERUSER")),
          problems::toString);
    }
  }

  private static List<String> problemsOfLogin() throws SQLException {
    DriverManagerDataSource source = new DriverManagerDataSource(withoutCredentials(ADMIN_URL));
    source.setUsername(LOGIN);
    source.setPassword("g");
    try (Connection connection = source.getConnection()) {
      return AppRoleGuard.problems(connection);
    }
  }

  /** The (superuser) role of the admin connection. */
  private static String adminRole() throws SQLException {
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = admin.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String withoutCredentials(String url) {
    return url.replaceAll("[?&](user|password)=[^&]*", "").replaceFirst("&", "?");
  }

  private static void admin(String sql) throws SQLException {
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = admin.createStatement()) {
      stmt.execute(sql);
    }
  }
}
