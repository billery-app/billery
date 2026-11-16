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
      List.of("billery_guard_bypass", "billery_guard_outsider", "billery_guard_owner");

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
  void aRoleThatMayActAsTheTableOwnerIsRefused() throws SQLException {
    admin("CREATE ROLE billery_guard_owner LOGIN PASSWORD 'g' IN ROLE billery_app");
    // Membership in the owner's role gives the owner's rights over the tables (SET ROLE).
    admin("GRANT " + tableOwner() + " TO billery_guard_owner");

    List<String> problems = problemsOf("billery_guard_owner");
    assertEquals(1, problems.size(), problems::toString);
    assertTrue(
        problems.get(0).startsWith("role billery_guard_owner owns or may act as the owner of "),
        problems.get(0));
    assertTrue(problems.get(0).contains("e.g. "), problems.get(0));
  }

  private static List<String> problemsOf(String role) throws SQLException {
    DriverManagerDataSource source = new DriverManagerDataSource(withoutCredentials(ADMIN_URL));
    source.setUsername(role);
    source.setPassword("g");
    try (Connection connection = source.getConnection()) {
      return AppRoleGuard.problems(connection);
    }
  }

  private static String tableOwner() throws SQLException {
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = admin.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE tablename = 'invoice'")) {
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
