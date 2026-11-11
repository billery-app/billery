package de.billery.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.data.Database;
import de.billery.data.TestDatabaseConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Guards the premise of the {@code -Psaas} suite: the repositories and services are exercised as
 * the application login role (no superuser, no BYPASSRLS) with tenant 1, not as the admin.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class SuiteRunsAsAppRoleTest {

  @BeforeAll
  static void setup() throws SQLException {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void theSuiteConnectsAsTheApplicationRoleWithTenantOne() throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT current_user || '|' || current_setting('app.tenant_id')"
                    + " || '|' || (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)")) {
      rs.next();
      assertEquals(SaasTestSupport.APP_LOGIN + "|1|false", rs.getString(1));
    }
  }
}
