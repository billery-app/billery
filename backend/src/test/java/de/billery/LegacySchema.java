package de.billery;

import de.billery.data.DatabaseMigration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a freshly created database back into the shape installs had before the GoBD invoice flow,
 * so tests can check that the startup migrations repair it on H2: cascading {@code
 * invoice.customer_id} foreign key, no unique index on {@code document_number}, no {@code
 * CREDIT_NOTE} / {@code PARTIALLY_PAID} lookup rows, two legacy drafts numbered {@code ''}, and a
 * paid invoice without payment rows.
 */
final class LegacySchema {

  private LegacySchema() {}

  /** Applies the pre-change shape described in the class comment. */
  static void revert(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      for (String name : customerForeignKeys(conn, false)) {
        // Identifier from information_schema, quoted; identifiers cannot be bound as parameters.
        // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        stmt.execute(
            "ALTER TABLE invoice DROP CONSTRAINT " + DatabaseMigration.quoteIdentifier(name));
      }
      stmt.execute(
          "ALTER TABLE invoice ADD CONSTRAINT legacy_invoice_customer"
              + " FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE");
      stmt.execute("DROP INDEX IF EXISTS uq_invoice_document_number");
      stmt.execute("DELETE FROM document_status WHERE id = 9");
      stmt.execute("DELETE FROM document_type WHERE id = 6");
      stmt.execute(
          "INSERT INTO customer (first_name, last_name) VALUES ('Legacy', 'Kunde')",
          Statement.RETURN_GENERATED_KEYS);
      int customerId;
      try (ResultSet keys = stmt.getGeneratedKeys()) {
        keys.next();
        customerId = keys.getInt(1);
      }
      try (PreparedStatement draft =
          conn.prepareStatement(
              "INSERT INTO invoice (customer_id, invoice_date, total_price, tax_rate,"
                  + " document_number) VALUES (?, '2024-01-01', 0, 0.19, '')")) {
        draft.setInt(1, customerId);
        draft.executeUpdate();
        draft.executeUpdate();
      }
      // Marked paid before payments were recorded: net 100, 19 %, no payment row.
      try (PreparedStatement paid =
          conn.prepareStatement(
              "INSERT INTO invoice (customer_id, invoice_date, total_price, tax_rate, paid_date,"
                  + " document_status_id, document_number)"
                  + " VALUES (?, '2024-01-01', 100, 0.19, '2024-02-01', 6, ?)")) {
        paid.setInt(1, customerId);
        paid.setString(2, "LEGACY-PAID-" + customerId);
        paid.executeUpdate();
      }
    }
  }

  /**
   * Names of the foreign keys on {@code invoice.customer_id}.
   *
   * @param cascadingOnly only those with {@code ON DELETE CASCADE}
   */
  static List<String> customerForeignKeys(Connection conn, boolean cascadingOnly)
      throws SQLException {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, String> key :
        DatabaseMigration.invoiceCustomerForeignKeys(conn).entrySet()) {
      if (!cascadingOnly || "CASCADE".equalsIgnoreCase(key.getValue())) {
        names.add(key.getKey());
      }
    }
    return names;
  }

  /** Tells whether the unique index on {@code invoice.document_number} exists. */
  static boolean uniqueDocumentNumberIndexExists(Connection conn) throws SQLException {
    try (PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT 1 FROM information_schema.STATISTICS"
                    + " WHERE TABLE_SCHEMA = SCHEMA()"
                    + " AND UPPER(INDEX_NAME) = 'UQ_INVOICE_DOCUMENT_NUMBER'");
        ResultSet rs = stmt.executeQuery()) {
      return rs.next();
    } catch (SQLException e) {
      // H2 has no STATISTICS view; it lists indexes in INDEXES.
      try (PreparedStatement stmt =
              conn.prepareStatement(
                  "SELECT 1 FROM information_schema.INDEXES"
                      + " WHERE UPPER(INDEX_NAME) = 'UQ_INVOICE_DOCUMENT_NUMBER'");
          ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Counts invoices still numbered {@code ''}. */
  static int emptyDocumentNumbers(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT COUNT(*) FROM invoice WHERE document_number = ''")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** Tells whether a lookup row exists. */
  static boolean lookupRowExists(Connection conn, String table, int id) throws SQLException {
    try (Statement stmt = conn.createStatement();
        // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        ResultSet rs = stmt.executeQuery("SELECT 1 FROM " + table + " WHERE id = " + id)) {
      return rs.next();
    }
  }

  /** Returns the payments recorded for the legacy paid invoice created by {@link #revert}. */
  static List<java.math.BigDecimal> legacyPaidInvoicePayments(Connection conn) throws SQLException {
    List<java.math.BigDecimal> amounts = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT p.amount FROM payment p JOIN invoice i ON i.id = p.invoice_id"
                    + " WHERE i.document_number LIKE 'LEGACY-PAID-%'"
                    + " AND i.id = (SELECT MAX(id) FROM invoice"
                    + " WHERE document_number LIKE 'LEGACY-PAID-%')")) {
      while (rs.next()) {
        amounts.add(rs.getBigDecimal(1));
      }
    }
    return amounts;
  }

  /**
   * Simulates documents from before VAT per line: a 7 % invoice and a Kleinunternehmer invoice
   * (rate 0) whose lines still carry the column default 19 % and no unit, and removes the marker of
   * the one-time backfill so the next start runs it.
   *
   * @return the ids of the two items: {@code [sevenPercentItem, smallBusinessItem]}
   */
  static int[] revertVatPerLine(Connection conn) throws SQLException {
    int[] items = new int[2];
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM user_settings WHERE setting_key = 'migration_vat_per_line'");
      stmt.execute(
          "INSERT INTO customer (first_name, last_name) VALUES ('Vat', 'Legacy')",
          Statement.RETURN_GENERATED_KEYS);
      int customerId = generatedKey(stmt);
      double[] rates = {0.07, 0};
      for (int i = 0; i < 2; i++) {
        int invoiceId;
        try (PreparedStatement invoice =
            conn.prepareStatement(
                "INSERT INTO invoice (customer_id, invoice_date, total_price, tax_rate)"
                    + " VALUES (?, '2024-03-01', 10, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
          invoice.setInt(1, customerId);
          invoice.setDouble(2, rates[i]);
          invoice.executeUpdate();
          invoiceId = generatedKey(invoice);
        }
        try (PreparedStatement item =
            conn.prepareStatement(
                "INSERT INTO invoice_item (invoice_id, item_type, item_id, item_name, quantity,"
                    + " unit_price, total_price) VALUES (?, 'ARTICLE', 0, 'Alt', 1, 10, 10)",
                Statement.RETURN_GENERATED_KEYS)) {
          item.setInt(1, invoiceId);
          item.executeUpdate();
          items[i] = generatedKey(item);
        }
      }
    }
    return items;
  }

  /** The VAT rate (percent) stored on an item. */
  static java.math.BigDecimal itemRate(Connection conn, int itemId) throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement("SELECT tax_rate_percent FROM invoice_item WHERE id = ?")) {
      stmt.setInt(1, itemId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1).setScale(2);
      }
    }
  }

  /** The tax treatment of the invoice an item belongs to. */
  static String treatmentOfItem(Connection conn, int itemId) throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT i.tax_treatment FROM invoice i JOIN invoice_item ii ON ii.invoice_id = i.id"
                + " WHERE ii.id = ?")) {
      stmt.setInt(1, itemId);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** Sets an item's rate, as a user editing a line after the migration would. */
  static void setItemRate(Connection conn, int itemId, String rate) throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement("UPDATE invoice_item SET tax_rate_percent = ? WHERE id = ?")) {
      stmt.setBigDecimal(1, new java.math.BigDecimal(rate));
      stmt.setInt(2, itemId);
      stmt.executeUpdate();
    }
  }

  private static int generatedKey(Statement stmt) throws SQLException {
    try (ResultSet keys = stmt.getGeneratedKeys()) {
      keys.next();
      return keys.getInt(1);
    }
  }
}
