package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The tenant columns, the per-tenant uniqueness and the tenant-consistent foreign keys of {@code
 * V2__tenant_id.sql} on a real PostgreSQL. There is no row-level security at this stage, so the
 * tests insert rows of a second tenant explicitly.
 *
 * <p>Runs only when {@code POSTGRES_TEST_URL} is set (it wipes the {@code public} schema).
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class TenantSchemaTest {

  @BeforeAll
  static void connect() {
    assumeFalse(
        AppProfiles.isSaas(),
        "asserts the core schema (tenant_id default 1); the SaaS schema is covered by saas/");
    Database.setDbPath(System.getenv("POSTGRES_TEST_URL"));
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void migratedEmptyDatabase() throws SQLException {
    execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static List<String> strings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  private static void assertRejected(String sql) {
    assertThrows(SQLException.class, () -> execute(sql), sql);
  }

  private static int customer(int tenant, String name) throws SQLException {
    execute(
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES ("
            + tenant
            + ", '"
            + name
            + "', 'X')");
    return Integer.parseInt(
        strings("SELECT MAX(id) FROM customer WHERE tenant_id = " + tenant).get(0));
  }

  @Test
  void everyTenantTableHasTheColumnWithDefaultOneAndTheLookupTablesDoNot() throws SQLException {
    List<String> withColumn =
        strings(
            "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND column_name = 'tenant_id' AND is_nullable = 'NO'"
                + " AND column_default = '1' ORDER BY table_name");
    assertEquals(
        List.of(
            "article",
            "customer",
            "document_history",
            "document_number_series",
            "equipment",
            "invoice",
            "invoice_item",
            "invoice_period",
            "invoice_template",
            "owner",
            "payment",
            "service",
            "user_settings"),
        withColumn);
    assertTrue(
        strings(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND column_name = 'tenant_id' AND table_name IN"
                    + " ('document_type', 'document_status')")
            .isEmpty());
  }

  @Test
  void existingSeedRowsBelongToTheDefaultTenant() throws SQLException {
    assertEquals(List.of("1"), strings("SELECT DISTINCT tenant_id FROM invoice_template"));
  }

  @Test
  void twoTenantsHaveIndependentNumberRangesDocumentNumbersAndSettings() throws SQLException {
    execute(
        "INSERT INTO document_number_series (tenant_id, document_type_id, doc_year, last_number)"
            + " VALUES (1, 1, 2026, 5), (2, 1, 2026, 7)",
        "INSERT INTO user_settings (tenant_id, setting_key, setting_value)"
            + " VALUES (1, 'prefix_invoice', 'RE'), (2, 'prefix_invoice', 'AB')");
    int c1 = customer(1, "A");
    int c2 = customer(2, "B");
    execute(
        "INSERT INTO invoice (tenant_id, customer_id, invoice_date, total_price, tax_rate,"
            + " document_number) VALUES (1, "
            + c1
            + ", '2026-01-01', 0, 0.19, 'RE-2026-001'),"
            + " (2, "
            + c2
            + ", '2026-01-01', 0, 0.19, 'RE-2026-001')");

    assertRejected(
        "INSERT INTO document_number_series (tenant_id, document_type_id, doc_year, last_number)"
            + " VALUES (1, 1, 2026, 9)");
    assertRejected(
        "INSERT INTO user_settings (tenant_id, setting_key, setting_value)"
            + " VALUES (2, 'prefix_invoice', 'XX')");
    assertRejected(
        "INSERT INTO invoice (tenant_id, customer_id, invoice_date, total_price, tax_rate,"
            + " document_number) VALUES (1, "
            + c1
            + ", '2026-01-02', 0, 0.19, 'RE-2026-001')");
  }

  @Test
  void ownerIsUniquePerTenant() throws SQLException {
    String owner =
        "INSERT INTO owner (tenant_id, first_name, last_name, company_name)"
            + " VALUES (%d, 'A', 'B', 'C')";
    execute(String.format(owner, 1), String.format(owner, 2));
    assertRejected(String.format(owner, 2));
  }

  @Test
  void aReferenceToAnotherTenantsRowIsRejectedByTheForeignKey() throws SQLException {
    int customerOfTenant2 = customer(2, "B");

    // Tenant 1 must not be able to attach an invoice to tenant 2's customer.
    assertRejected(
        "INSERT INTO invoice (tenant_id, customer_id, invoice_date, total_price, tax_rate)"
            + " VALUES (1, "
            + customerOfTenant2
            + ", '2026-01-01', 0, 0.19)");
    // The same insert for the owning tenant works.
    execute(
        "INSERT INTO invoice (tenant_id, customer_id, invoice_date, total_price, tax_rate)"
            + " VALUES (2, "
            + customerOfTenant2
            + ", '2026-01-01', 0, 0.19)");
  }

  @Test
  void deleteRulesSurvivedTheCompositeKeys() throws SQLException {
    int c = customer(1, "A");
    execute(
        "INSERT INTO invoice (id, tenant_id, customer_id, invoice_date, total_price, tax_rate)"
            + " VALUES (9001, 1, "
            + c
            + ", '2026-01-01', 0, 0.19)",
        "INSERT INTO invoice_item (invoice_id, item_type, item_id, quantity, unit_price,"
            + " total_price) VALUES (9001, 'ARTICLE', 1, 1, 1, 1)",
        "INSERT INTO article (id, article_name, sales_value) VALUES (9002, 'Art', 1)",
        "INSERT INTO service (servicename, sales_value, article_id) VALUES ('Svc', 1, 9002)");

    assertRejected("DELETE FROM customer WHERE id = " + c); // restricted: invoice exists
    execute("DELETE FROM invoice WHERE id = 9001"); // cascades to the line
    assertEquals(List.of("0"), strings("SELECT COUNT(*) FROM invoice_item"));
    execute("DELETE FROM article WHERE id = 9002"); // service keeps existing, article id nulled
    assertEquals(
        List.of("1"),
        strings("SELECT COUNT(*) FROM service WHERE article_id IS NULL AND tenant_id = 1"));
  }
}
