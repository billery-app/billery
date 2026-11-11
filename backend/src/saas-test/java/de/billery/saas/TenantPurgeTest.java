package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@code purge_tenant}: the operator's way to remove a tenant's data after the retention period. It
 * must cope with the circular references between documents, periods and payments, remove everything
 * of the tenant, and touch nothing of any other tenant. Only the operator can run it.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class TenantPurgeTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");

  private static final List<String> TENANT_TABLES =
      List.of(
          "payment",
          "document_history",
          "invoice_item",
          "invoice_period",
          "invoice",
          "service",
          "article",
          "equipment",
          "customer",
          "invoice_template",
          "document_number_series",
          "user_settings",
          "owner",
          "tenant_invitation",
          "tenant_member",
          "tenant_audit");

  @BeforeAll
  static void prepare() throws SQLException {
    Database.setDbPath(ADMIN_URL);
    admin("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    admin(
        "INSERT INTO app_user (id, email) VALUES ('00000000-0000-0000-0000-00000000000a', 'a@x.de')");
    populate(5);
    populate(6);
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  private static void admin(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static int count(String sql) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** One of everything for a tenant, with the circular references the purge has to break. */
  private static void populate(int t) throws SQLException {
    String user = "'00000000-0000-0000-0000-00000000000a'";
    admin(
        "INSERT INTO tenant (id, name) VALUES (" + t + ", 'T" + t + "')",
        "INSERT INTO tenant_member (tenant_id, user_id, role) VALUES ("
            + t
            + ", "
            + user
            + ", 'OWNER')",
        "INSERT INTO tenant_invitation (tenant_id, email, role, invited_by)"
            + " VALUES ("
            + t
            + ", 'i@x.de', 'READER', "
            + user
            + ")",
        "INSERT INTO tenant_audit (tenant_id, action) VALUES (" + t + ", 'TENANT_CREATED')",
        "INSERT INTO owner (tenant_id, first_name, last_name, company_name)"
            + " VALUES ("
            + t
            + ", 'A', 'B', 'C')",
        "INSERT INTO user_settings (tenant_id, setting_key, setting_value) VALUES ("
            + t
            + ", 'k', 'v')",
        "INSERT INTO document_number_series (tenant_id, document_type_id, doc_year, last_number)"
            + " VALUES ("
            + t
            + ", 1, 2026, 1)",
        "INSERT INTO invoice_template (tenant_id, name, document_type_id) VALUES ("
            + t
            + ", 'T', 1)",
        "INSERT INTO customer (id, tenant_id, first_name, last_name)"
            + " VALUES ("
            + (t * 100)
            + ", "
            + t
            + ", 'K', 'L')",
        "INSERT INTO article (id, tenant_id, article_name, sales_value)"
            + " VALUES ("
            + (t * 100)
            + ", "
            + t
            + ", 'Art', 1)",
        "INSERT INTO equipment (id, tenant_id, name) VALUES (" + (t * 100) + ", " + t + ", 'Eq')",
        "INSERT INTO service (tenant_id, servicename, sales_value, article_id, equipment_id)"
            + " VALUES ("
            + t
            + ", 'S', 1, "
            + (t * 100)
            + ", "
            + (t * 100)
            + ")",
        // invoice 1 is cancelled by invoice 2 (self reference) and belongs to a period
        "INSERT INTO invoice (id, tenant_id, customer_id, invoice_date, total_price, tax_rate)"
            + " VALUES ("
            + (t * 100 + 1)
            + ", "
            + t
            + ", "
            + (t * 100)
            + ", '2026-01-01', 1, 0.19)",
        "INSERT INTO invoice_period (id, tenant_id, parent_invoice_id, period_start_date,"
            + " period_end_date) VALUES ("
            + (t * 100 + 1)
            + ", "
            + t
            + ", "
            + (t * 100 + 1)
            + ", '2026-01-01', '2026-01-31')",
        "INSERT INTO invoice_period (id, tenant_id, parent_invoice_id, period_start_date,"
            + " period_end_date, previous_period_id) VALUES ("
            + (t * 100 + 2)
            + ", "
            + t
            + ", "
            + (t * 100 + 1)
            + ", '2026-02-01', '2026-02-28', "
            + (t * 100 + 1)
            + ")",
        "UPDATE invoice SET period_id = " + (t * 100 + 1) + " WHERE id = " + (t * 100 + 1),
        "INSERT INTO invoice (id, tenant_id, customer_id, invoice_date, total_price, tax_rate,"
            + " cancels_invoice_id, parent_invoice_id) VALUES ("
            + (t * 100 + 2)
            + ", "
            + t
            + ", "
            + (t * 100)
            + ", '2026-01-02', 1, 0.19, "
            + (t * 100 + 1)
            + ", "
            + (t * 100 + 1)
            + ")",
        "INSERT INTO invoice_item (id, tenant_id, invoice_id, item_type, item_id, quantity,"
            + " unit_price, total_price) VALUES ("
            + (t * 100 + 1)
            + ", "
            + t
            + ", "
            + (t * 100 + 1)
            + ", 'ARTICLE', 1, 1, 1, 1)",
        "INSERT INTO invoice_item (tenant_id, invoice_id, item_type, item_id, quantity, unit_price,"
            + " total_price, parent_item_id) VALUES ("
            + t
            + ", "
            + (t * 100 + 1)
            + ", 'CUSTOM', 0, 1,"
            + " 0, 0, "
            + (t * 100 + 1)
            + ")",
        "INSERT INTO document_history (tenant_id, invoice_id, action) VALUES ("
            + t
            + ", "
            + (t * 100 + 1)
            + ", 'CREATED')",
        // a payment and its reversal (self reference)
        "INSERT INTO payment (id, tenant_id, invoice_id, payment_date, amount)"
            + " VALUES ("
            + (t * 100 + 1)
            + ", "
            + t
            + ", "
            + (t * 100 + 1)
            + ", '2026-01-05', 1)",
        "INSERT INTO payment (tenant_id, invoice_id, payment_date, amount, reverses_payment_id)"
            + " VALUES ("
            + t
            + ", "
            + (t * 100 + 1)
            + ", '2026-01-06', -1, "
            + (t * 100 + 1)
            + ")");
  }

  private static int rows(int tenant) throws SQLException {
    int total = count("SELECT COUNT(*) FROM tenant WHERE id = " + tenant);
    for (String table : TENANT_TABLES) {
      total += count("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = " + tenant);
    }
    return total;
  }

  @Test
  void purgeRemovesTheWholeTenantAndLeavesTheOthersAlone() throws SQLException {
    int bystanderBefore = rows(6);
    assertTrue(rows(5) > 15, "the fixture is populated: " + rows(5));

    admin("SELECT purge_tenant(5)");

    assertEquals(0, rows(5), "nothing of the tenant is left");
    assertEquals(bystanderBefore, rows(6), "the other tenant is untouched");
    assertEquals(
        1, count("SELECT COUNT(*) FROM app_user"), "users stay: they may be members elsewhere");
    assertEquals(
        1, count("SELECT COUNT(*) FROM customer WHERE tenant_id = 6"), "bystander data readable");
  }

  @Test
  void theSeedTenantIsNeverPurged() {
    assertThrows(SQLException.class, () -> admin("SELECT purge_tenant(1)"));
  }

  @Test
  void theApplicationRoleCannotPurgeNorDeleteTenants() throws SQLException {
    var source = SaasTestSupport.appRoleDataSource(ADMIN_URL);
    try (Connection conn = source.getConnection();
        Statement stmt = conn.createStatement()) {
      assertThrows(SQLException.class, () -> stmt.execute("SELECT purge_tenant(6)"));
      assertThrows(SQLException.class, () -> stmt.execute("DELETE FROM tenant WHERE id = 6"));
    }
    assertEquals(1, count("SELECT COUNT(*) FROM tenant WHERE id = 6"));
  }
}
