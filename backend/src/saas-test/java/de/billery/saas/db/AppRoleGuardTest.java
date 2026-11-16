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
 * {@link AppRoleGuard}: the role the production backend connects as passes; every role that could
 * bypass the tenant isolation is refused, each for its own reason.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class AppRoleGuardTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");

  /** Roles a test creates; dropped after each test. */
  private static final List<String> ROLES =
      List.of(
          "billery_guard_bypass",
          "billery_guard_outsider",
          "billery_guard_owner",
          "billery_guard_tableowner",
          "billery_guard_switcher",
          "billery_guard_privileged");

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
    try (Connection connection = DriverManager.getConnection(ADMIN_URL)) {
      List<String> problems = AppRoleGuard.problems(connection);
      assertTrue(problems.stream().anyMatch(p -> p.endsWith("is a superuser")), problems::toString);
    }
    DataSource admin = new DriverManagerDataSource(ADMIN_URL);
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new AppRoleGuard(admin).verify());
    assertTrue(refused.getMessage().contains("refuses to start"), refused.getMessage());
  }

  @Test
  void aRoleWithBypassRlsIsRefusedEvenAsMemberOfTheAppRole() throws SQLException {
    admin("CREATE ROLE billery_guard_bypass LOGIN PASSWORD 'g' BYPASSRLS IN ROLE billery_app");

    assertEquals(
        List.of("role billery_guard_bypass has BYPASSRLS"), problemsOf("billery_guard_bypass"));
  }

  @Test
  void aRoleOutsideTheAppRoleIsRefused() throws SQLException {
    admin("CREATE ROLE billery_guard_outsider LOGIN PASSWORD 'g'");

    assertEquals(
        List.of("role billery_guard_outsider is not a member of billery_app"),
        problemsOf("billery_guard_outsider"));
  }

  @Test
  void aRoleThatMayActAsATableOwnerIsRefused() throws SQLException {
    // an ordinary role (no superuser, no BYPASSRLS) that owns a table in public
    admin("CREATE ROLE billery_guard_tableowner NOLOGIN");
    admin("CREATE TABLE billery_guard_scratch (id INT)");
    admin("ALTER TABLE billery_guard_scratch OWNER TO billery_guard_tableowner");
    // membership in the owner's role gives the owner's rights over the table (SET ROLE)
    admin(
        "CREATE ROLE billery_guard_owner LOGIN PASSWORD 'g'"
            + " IN ROLE billery_app, billery_guard_tableowner");

    assertEquals(
        List.of(
            "role billery_guard_owner owns or may act as the owner of 1 table(s), e.g."
                + " billery_guard_scratch"),
        problemsOf("billery_guard_owner"));
  }

  @Test
  void aRoleThatMaySwitchToARoleWithBypassRlsIsRefused() throws SQLException {
    admin("CREATE ROLE billery_guard_privileged NOLOGIN BYPASSRLS");
    admin(
        "CREATE ROLE billery_guard_switcher LOGIN PASSWORD 'g'"
            + " IN ROLE billery_app, billery_guard_privileged");

    assertEquals(
        List.of(
            "role billery_guard_switcher may switch (SET ROLE) to billery_guard_privileged"
                + " (BYPASSRLS)"),
        problemsOf("billery_guard_switcher"));
  }

  @Test
  void aRoleThatMaySwitchToASuperuserIsRefused() throws SQLException {
    admin("CREATE ROLE billery_guard_switcher LOGIN PASSWORD 'g' IN ROLE billery_app");
    admin("GRANT " + adminRole() + " TO billery_guard_switcher");

    List<String> problems = problemsOf("billery_guard_switcher");
    assertTrue(
        problems.contains(
            "role billery_guard_switcher may switch (SET ROLE) to "
                + adminRole().replace("\"", "")
                + " (superuser)"),
        problems::toString);
  }

  @Test
  void aConnectionThatSignedInAsAdminAndSwitchedToTheAppRoleIsRefused() throws SQLException {
    // the role set in the connection options, as an operator might put it into DB_URL
    Properties options = new Properties();
    options.setProperty("options", "-c role=" + SaasTestSupport.APP_LOGIN);
    try (Connection connection = DriverManager.getConnection(ADMIN_URL, options)) {
      List<String> problems = AppRoleGuard.problems(connection);
      String admin = adminRole().replace("\"", "");
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
      assertTrue(problems.contains("role " + admin + " is a superuser"), problems::toString);
    }
  }

  private static List<String> problemsOf(String role) throws SQLException {
    DriverManagerDataSource source = new DriverManagerDataSource(withoutCredentials(ADMIN_URL));
    source.setUsername(role);
    source.setPassword("g");
    try (Connection connection = source.getConnection()) {
      return AppRoleGuard.problems(connection);
    }
  }

  /** The (superuser) role of the admin connection, quoted. */
  private static String adminRole() throws SQLException {
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = admin.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      rs.next();
      return "\"" + rs.getString(1) + "\"";
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
