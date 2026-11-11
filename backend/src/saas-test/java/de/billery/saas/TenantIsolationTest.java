package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import de.billery.saas.db.TenantAwareDataSource;
import de.billery.saas.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tenant isolation inside the database, proven as the application role: what row-level security,
 * the column defaults and the grants of {@code V2__tenant_isolation.sql} guarantee no matter what
 * the application code does. Plain SQL only; the repositories are covered by running the whole
 * suite under this role.
 *
 * <p>Runs only when {@code POSTGRES_TEST_URL} is set (it wipes the {@code public} schema).
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class TenantIsolationTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");

  private static DataSource app;

  @BeforeAll
  static void prepare() throws SQLException {
    Database.setDbPath(ADMIN_URL);
    admin("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    app = new TenantAwareDataSource(SaasTestSupport.appRoleDataSource(ADMIN_URL));
    admin(
        "INSERT INTO tenant (id, name) VALUES (2, 'Second')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (1, 'Eins', 'A')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (2, 'Zwei', 'B')");
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  @AfterEach
  void noLeftoverTenant() {
    TenantContext.clear();
  }

  private static void admin(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  /**
   * Runs {@code sql} as the application role with {@code tenant} active; returns the first column.
   */
  private static List<String> query(Integer tenant, String sql) throws SQLException {
    if (tenant != null) {
      TenantContext.set(tenant, null);
    }
    List<String> values = new ArrayList<>();
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    } finally {
      TenantContext.clear();
    }
    return values;
  }

  private static int update(Integer tenant, String sql) throws SQLException {
    if (tenant != null) {
      TenantContext.set(tenant, null);
    }
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement()) {
      return stmt.executeUpdate(sql);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void theApplicationRoleCannotBypassRowLevelSecurity() throws SQLException {
    assertEquals(
        List.of("false|false"),
        queryAdmin(
            "SELECT rolsuper || '|' || rolbypassrls FROM pg_roles WHERE rolname = '"
                + SaasTestSupport.APP_LOGIN
                + "'"));
    assertEquals(
        List.of("false|false"),
        queryAdmin(
            "SELECT rolsuper || '|' || rolbypassrls FROM pg_roles WHERE rolname = 'billery_app'"));
  }

  private static List<String> queryAdmin(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  @Test
  void everyTableWithATenantColumnHasForcedRowLevelSecurityAndAPolicy() throws SQLException {
    List<String> unprotected =
        queryAdmin(
            "SELECT c.relname FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'"
                + " WHERE c.relkind = 'r' AND EXISTS ("
                + "   SELECT 1 FROM information_schema.columns col"
                + "   WHERE col.table_schema = 'public' AND col.table_name = c.relname"
                + "     AND col.column_name = 'tenant_id')"
                + " AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity"
                + "      OR NOT EXISTS (SELECT 1 FROM pg_policies p"
                + "                     WHERE p.schemaname = 'public' AND p.tablename = c.relname))"
                + " ORDER BY c.relname");
    assertTrue(
        unprotected.isEmpty(), "tenant tables without forced RLS and a policy: " + unprotected);
    assertEquals(
        List.of("tenant_create", "tenant_visibility"),
        queryAdmin(
            "SELECT policyname FROM pg_policies WHERE schemaname = 'public'"
                + " AND tablename = 'tenant' ORDER BY policyname"),
        "sign-up may insert a tenant; reading is limited to the active tenant, its members and invitees");
  }

  @Test
  void aTenantSeesOnlyItsOwnRows() throws SQLException {
    assertEquals(List.of("Eins"), query(1, "SELECT first_name FROM customer"));
    assertEquals(List.of("Zwei"), query(2, "SELECT first_name FROM customer"));
    assertEquals(List.of("1"), query(1, "SELECT id FROM tenant"));
  }

  @Test
  void rowsOfAnotherTenantCannotBeChangedOrDeletedByTheirId() throws SQLException {
    String otherId = queryAdmin("SELECT id FROM customer WHERE tenant_id = 2").get(0);

    assertEquals(0, update(1, "UPDATE customer SET first_name = 'x' WHERE id = " + otherId));
    assertEquals(0, update(1, "DELETE FROM customer WHERE id = " + otherId));
    assertEquals(
        List.of("Zwei"), queryAdmin("SELECT first_name FROM customer WHERE tenant_id = 2"));
  }

  @Test
  void aTenantCannotWriteOrMoveRowsIntoAnotherTenant() throws SQLException {
    assertThrows(
        SQLException.class,
        () ->
            update(
                1, "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (2, 'x', 'y')"));
    assertThrows(SQLException.class, () -> update(1, "UPDATE customer SET tenant_id = 2"));
  }

  @Test
  void aRowWithoutTenantColumnBelongsToTheActiveTenant() throws SQLException {
    update(2, "INSERT INTO customer (first_name, last_name) VALUES ('Default', 'Tenant')");
    try {
      assertEquals(
          List.of("2"), queryAdmin("SELECT tenant_id FROM customer WHERE first_name = 'Default'"));
    } finally {
      admin("DELETE FROM customer WHERE first_name = 'Default'");
    }
  }

  @Test
  void withoutATenantTenantDataIsUnreachable() {
    assertThrows(SQLException.class, () -> query(null, "SELECT * FROM customer"));
    assertThrows(
        SQLException.class,
        () -> update(null, "INSERT INTO customer (first_name, last_name) VALUES ('x', 'y')"));
  }

  @Test
  void aReferenceToAnotherTenantsRowFailsEvenThoughItIsInvisible() throws SQLException {
    String foreignCustomer = queryAdmin("SELECT id FROM customer WHERE tenant_id = 2").get(0);
    assertThrows(
        SQLException.class,
        () ->
            update(
                1,
                "INSERT INTO invoice (customer_id, invoice_date, total_price, tax_rate)"
                    + " VALUES ("
                    + foreignCustomer
                    + ", '2026-01-01', 0, 0.19)"));
  }

  @Test
  void bookkeepingAndLookupTablesAreProtectedFromTheApplicationRole() {
    assertThrows(SQLException.class, () -> query(1, "SELECT * FROM flyway_schema_history"));
    assertThrows(SQLException.class, () -> query(1, "SELECT * FROM flyway_schema_history_saas"));
    assertThrows(
        SQLException.class,
        () -> update(1, "INSERT INTO document_type (id, name) VALUES (99, 'X')"));
    assertDoesNotThrow(() -> query(1, "SELECT COUNT(*) FROM document_type"));
  }

  @Test
  void aPooledConnectionNeverCarriesATenantToTheNextBorrower() throws SQLException {
    HikariConfig config = new HikariConfig();
    config.setDataSource(SaasTestSupport.appRoleDataSource(ADMIN_URL));
    config.setMaximumPoolSize(1); // the same physical connection is lent again every time
    try (HikariDataSource pool = new HikariDataSource(config)) {
      DataSource tenantAware = new TenantAwareDataSource(pool);

      TenantContext.set(2, "someone");
      try (Connection conn = tenantAware.getConnection();
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT current_setting('app.tenant_id')")) {
        rs.next();
        assertEquals("2", rs.getString(1));
      }
      TenantContext.clear();

      try (Connection conn = tenantAware.getConnection();
          Statement stmt = conn.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT current_setting('app.tenant_id', true) || '|'"
                      + " || current_setting('app.user_id', true)")) {
        rs.next();
        assertEquals("|", rs.getString(1), "settings are cleared on return");
        assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT * FROM customer"));
      }
    }
  }

  @Test
  void historyRowsTakeTheActingUserFromTheConnection() throws SQLException {
    String customer = queryAdmin("SELECT id FROM customer WHERE tenant_id = 1").get(0);
    admin(
        "INSERT INTO invoice (id, tenant_id, customer_id, invoice_date, total_price, tax_rate)"
            + " VALUES (7001, 1, "
            + customer
            + ", '2026-01-01', 0, 0.19) ON CONFLICT DO NOTHING");
    TenantContext.set(1, "11111111-1111-1111-1111-111111111111");
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("INSERT INTO document_history (invoice_id, action) VALUES (7001, 'TEST')");
    } finally {
      TenantContext.clear();
    }
    update(1, "INSERT INTO document_history (invoice_id, action) VALUES (7001, 'SYSTEM')");

    assertEquals(
        List.of("11111111-1111-1111-1111-111111111111"),
        queryAdmin("SELECT performed_by FROM document_history WHERE action = 'TEST'"));
    assertEquals(
        java.util.Arrays.asList((String) null),
        queryAdmin("SELECT performed_by FROM document_history WHERE action = 'SYSTEM'"),
        "no user in the context means no user recorded");
  }
}
