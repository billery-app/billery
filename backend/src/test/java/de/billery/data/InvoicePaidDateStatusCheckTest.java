package de.billery.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code chk_invoice_paid_date_status} CHECK constraint: a paid date may only be set when
 * the document status is PAID (id = 6). The constraint is declared in {@code scheme-h2.sql} and
 * enforced on every INSERT and UPDATE.
 */
class InvoicePaidDateStatusCheckTest {

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearInvoiceTable() throws SQLException {
    TestDatabaseConfig.clearData();
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO customer (id, first_name, last_name) VALUES (1, 'Test', 'Customer')");
    }
  }

  @Test
  void insertingPaidInvoiceSucceeds() {
    assertDoesNotThrow(
        () ->
            insertInvoice(
                "INSERT INTO invoice (id, customer_id, invoice_date, total_price, tax_rate,"
                    + " paid_date, document_type_id, document_status_id)"
                    + " VALUES (1, 1, '2024-01-01', 100, 0.19, '2024-01-15', 1, 6)"));
  }

  @Test
  void insertingSentInvoiceWithoutPaidDateSucceeds() {
    assertDoesNotThrow(
        () ->
            insertInvoice(
                "INSERT INTO invoice (id, customer_id, invoice_date, total_price, tax_rate,"
                    + " paid_date, document_type_id, document_status_id)"
                    + " VALUES (1, 1, '2024-01-01', 100, 0.19, NULL, 1, 2)"));
  }

  @Test
  void insertingSentInvoiceWithPaidDateIsRejected() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () ->
                insertInvoice(
                    "INSERT INTO invoice (id, customer_id, invoice_date, total_price, tax_rate,"
                        + " paid_date, document_type_id, document_status_id)"
                        + " VALUES (1, 1, '2024-01-01', 100, 0.19, '2024-01-15', 1, 2)"));
    assertTrue(
        ex.getMessage().toLowerCase().contains("check")
            || ex.getMessage().toLowerCase().contains("constraint"),
        "Expected CHECK-constraint violation, got: " + ex.getMessage());
  }

  @Test
  void updatingPaidDateOnSentInvoiceIsRejected() throws SQLException {
    insertInvoice(
        "INSERT INTO invoice (id, customer_id, invoice_date, total_price, tax_rate,"
            + " paid_date, document_type_id, document_status_id)"
            + " VALUES (1, 1, '2024-01-01', 100, 0.19, NULL, 1, 2)");

    SQLException ex =
        assertThrows(
            SQLException.class,
            () -> insertInvoice("UPDATE invoice SET paid_date = '2024-01-15' WHERE id = 1"));
    assertTrue(
        ex.getMessage().toLowerCase().contains("check")
            || ex.getMessage().toLowerCase().contains("constraint"),
        "Expected CHECK-constraint violation, got: " + ex.getMessage());
  }

  private static void insertInvoice(String sql) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(sql);
    }
  }
}
