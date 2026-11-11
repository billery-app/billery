package de.billery;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.data.Database;
import de.billery.data.DatabaseException;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real startup sequence ({@link MainApp#prepareDatabase()}) against an H2 file database
 * with the same URL shape the desktop shell uses. Migrations abort the start on failure, so they
 * must succeed on a fresh database and again on every later start.
 */
class MainAppDatabaseTest {

  @TempDir Path tempDir;

  @BeforeEach
  void useFreshFileDatabase() {
    System.clearProperty("LOAD_DEMO_DATA");
    Database.setDbPath("jdbc:h2:file:" + tempDir.resolve("database") + ";MODE=MariaDB");
  }

  @AfterEach
  void restoreTestDatabase() {
    System.clearProperty("LOAD_DEMO_DATA");
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void freshDatabaseStartsEmptyAndRestartIsIdempotent() throws Exception {
    MainApp.prepareDatabase();
    MainApp.prepareDatabase(); // second start: schema exists, migrations must be no-ops

    assertEquals(0, count("customer"), "no demo customers without LOAD_DEMO_DATA");
    assertEquals(0, count("invoice"));
    assertEquals(0, count("document_number_series"), "numbering must start at 1");
    assertEquals(4, count("invoice_template"), "seed templates are part of the schema");
  }

  @Test
  void ownerWithUrlAndIbanCanBeSaved() throws Exception {
    MainApp.prepareDatabase();

    TestWiring.ownerRepository().save(owner());

    Owner saved = TestWiring.ownerRepository().findOwner();
    assertNotNull(saved);
    assertEquals("DE02120300000000202051", saved.getIban());
    assertEquals("https://example.de", saved.getUrl());
  }

  @Test
  void migrationAddsOwnerColumnsMissingFromOlderDesktopDatabases() throws Exception {
    MainApp.prepareDatabase();
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE owner DROP COLUMN url");
      stmt.execute("ALTER TABLE owner DROP COLUMN iban");
    }

    MainApp.prepareDatabase();

    TestWiring.ownerRepository().save(owner());
    assertEquals("DE02120300000000202051", TestWiring.ownerRepository().findOwner().getIban());
  }

  @Test
  void vatPerLineBackfillRunsOnceAndKeepsLaterChanges() throws Exception {
    MainApp.prepareDatabase();
    int[] items;
    try (Connection conn = Database.getConnection()) {
      items = LegacySchema.revertVatPerLine(conn);
    }

    MainApp.prepareDatabase();

    try (Connection conn = Database.getConnection()) {
      assertEquals(new java.math.BigDecimal("7.00"), LegacySchema.itemRate(conn, items[0]));
      assertEquals("STANDARD", LegacySchema.treatmentOfItem(conn, items[0]));
      assertEquals(new java.math.BigDecimal("0.00"), LegacySchema.itemRate(conn, items[1]));
      assertEquals("SMALL_BUSINESS", LegacySchema.treatmentOfItem(conn, items[1]));
      LegacySchema.setItemRate(conn, items[0], "19.00");
    }

    MainApp.prepareDatabase();

    try (Connection conn = Database.getConnection()) {
      assertEquals(
          new java.math.BigDecimal("19.00"),
          LegacySchema.itemRate(conn, items[0]),
          "a later start must not overwrite per-line rates");
    }
  }

  /**
   * Blocks added by the old template editor stored literal sample values; the PDF printed them
   * instead of the document's number and dates. The migration rewrites them to variables, only for
   * untouched defaults, and is a no-op on the next start.
   */
  @Test
  void migrationReplacesEditorSampleLiteralsInTemplates() throws Exception {
    MainApp.prepareDatabase();
    String legacyBlocks =
        "[{\"type\":\"invoice-meta\",\"data\":{\"invoiceId\":\"INV-2026-001\","
            + "\"invoiceDate\":\"2026-03-17\",\"dueDate\":\"2026-03-31\"}},"
            + "{\"type\":\"header\",\"data\":{\"companyName\":\"Ihr Firmenname\"}},"
            + "{\"type\":\"footer\",\"data\":{\"text\":\"INV-2026-001 stays in free text\"}}]";
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("UPDATE invoice_template SET blocks = '" + legacyBlocks + "'");
    }

    MainApp.prepareDatabase();
    MainApp.prepareDatabase();

    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT blocks FROM invoice_template")) {
      assertTrue(rs.next());
      String blocks = rs.getString(1);
      assertTrue(blocks.contains("\"invoiceId\":\"{{rechnungsnummer}}\""), blocks);
      assertTrue(blocks.contains("\"invoiceDate\":\"{{rechnungsdatum}}\""), blocks);
      assertTrue(blocks.contains("\"dueDate\":\"{{faelligkeitsdatum}}\""), blocks);
      assertTrue(blocks.contains("\"companyName\":\"{{firma}}\""), blocks);
      assertTrue(blocks.contains("INV-2026-001 stays in free text"), "free text is untouched");
    }
  }

  @Test
  void migrationRepairsDatabasesFromBeforeTheGobdInvoiceFlow() throws Exception {
    MainApp.prepareDatabase();
    try (Connection conn = Database.getConnection()) {
      LegacySchema.revert(conn);
      assertFalse(LegacySchema.customerForeignKeys(conn, true).isEmpty(), "setup: cascading FK");
    }

    MainApp.prepareDatabase();
    MainApp.prepareDatabase(); // and idempotent afterwards

    try (Connection conn = Database.getConnection()) {
      assertTrue(LegacySchema.customerForeignKeys(conn, true).isEmpty(), "no cascade left");
      assertEquals(1, LegacySchema.customerForeignKeys(conn, false).size(), "one restricting FK");
      assertTrue(LegacySchema.uniqueDocumentNumberIndexExists(conn));
      assertEquals(0, LegacySchema.emptyDocumentNumbers(conn), "'' normalised to NULL");
      assertTrue(LegacySchema.lookupRowExists(conn, "document_type", 6));
      assertTrue(LegacySchema.lookupRowExists(conn, "document_status", 9));
      assertEquals(
          List.of(new java.math.BigDecimal("119.00")),
          LegacySchema.legacyPaidInvoicePayments(conn),
          "one backfilled payment, not one per start");
    }
  }

  @Test
  void demoDataIsLoadedOnlyWhenRequested() throws Exception {
    System.setProperty("LOAD_DEMO_DATA", "true");

    MainApp.prepareDatabase();

    // The test classpath's dummies.sql seeds the number series and the prefixes.
    assertTrue(count("document_number_series") > 0);
  }

  @Test
  void unreachableDatabaseAbortsStartInsteadOfInitializing() {
    Database.setDbPath("jdbc:h2:file:" + tempDir.resolve("db") + ";IFEXISTS=TRUE");

    assertThrows(DatabaseException.class, MainApp::prepareDatabase);
  }

  private static Owner owner() {
    return new Owner(
        0,
        "Erika",
        "Muster",
        "Muster & Söhne GmbH",
        "GmbH",
        "12345",
        "Ort",
        "Weg",
        1,
        "",
        "info@example.de",
        "https://example.de",
        "DE123456789",
        "DE02120300000000202051",
        false);
  }

  private static int count(String table) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) { // test constants
      rs.next();
      return rs.getInt(1);
    }
  }
}
