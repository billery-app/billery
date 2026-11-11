package de.billery.data;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles database migrations to update schema between versions. */
public class DatabaseMigration {

  private static final Logger LOGGER = Logger.getLogger(DatabaseMigration.class.getName());

  private static final String COLUMN_ARCHIVED = "archived";
  private static final String COLUMN_PAID = "paid";
  private static final String COLUMN_PAID_DATE = "paid_date";

  private static final String COLUMN_IS_SMALL_BUSINESS = "is_small_business";

  /** {@code user_settings} key marking that the one-time VAT backfill has run. */
  private static final String VAT_PER_LINE_MARKER = "migration_vat_per_line";

  /**
   * Literal sample values that the template editor used to write into newly added blocks, mapped to
   * the variable that must replace them. The keys are the exact JSON fragments {@code
   * JSON.stringify} produced, so only untouched editor defaults match.
   */
  private static final Map<String, String> TEMPLATE_SAMPLE_LITERALS =
      Map.of(
          "\"invoiceId\":\"INV-2026-001\"", "\"invoiceId\":\"{{rechnungsnummer}}\"",
          "\"invoiceDate\":\"2026-03-17\"", "\"invoiceDate\":\"{{rechnungsdatum}}\"",
          "\"dueDate\":\"2026-03-31\"", "\"dueDate\":\"{{faelligkeitsdatum}}\"",
          "\"companyName\":\"Ihr Firmenname\"", "\"companyName\":\"{{firma}}\"");

  /**
   * Runs all migrations of the H2 (desktop) schema. Each one checks whether it is still needed, so
   * the whole list runs on every startup. PostgreSQL has no installs predating its schema, so it
   * skips them.
   *
   * @throws DatabaseException if a migration fails; the application must not start on a
   *     half-migrated schema
   */
  public static void runMigrations() {
    if (!Database.getUrl().startsWith("jdbc:h2:")) {
      LOGGER.info(
          "Skipping legacy migrations: the PostgreSQL schema is created at its latest state");
      return;
    }
    try (Connection conn = Database.getConnection()) {
      migratePaidToPaidDate(conn);
      migrateAddArchivedColumn(conn);
      migrateAddIsSmallBusinessColumn(conn);
      migrateAddRenderedHtmlColumn(conn);
      migrateAddEquipmentTable(conn);
      migrateAddParentItemIdColumn(conn);
      migrateParentItemIdCascade(conn);
      migrateAddCustomItemType(conn);
      migrateAddItemNameAndDescription(conn);
      migrateAddInvoiceTemplateId(conn);
      migrateAddTemplateIsDocumentSpecific(conn);
      migrateDropOverdueStatus(conn);
      migrateEnforcePaidDateStatusInvariant(conn);
      migrateAddOwnerUrlAndIban(conn);
      migrateAddCreditNoteAndPartialPayment(conn);
      migrateUniqueDocumentNumber(conn);
      migrateCustomerForeignKeyRestrict(conn);
      migrateBackfillLegacyPayments(conn);
      migrateVatPerLine(conn);
      migrateAddArticleNote(conn);
      migrateTemplateSampleLiterals(conn);
    } catch (SQLException e) {
      throw new DatabaseException("Database migration failed", e);
    }
  }

  /** Migration: Add archived column to invoice table for archive functionality. */
  private static void migrateAddArchivedColumn(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    boolean hasArchivedColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice"), null)) {
      if (columns == null) {
        return;
      }
      while (columns.next()) {
        if (COLUMN_ARCHIVED.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasArchivedColumn = true;
          break;
        }
      }
    }

    if (!hasArchivedColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding archived column to invoice");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("ALTER TABLE invoice ADD COLUMN archived INTEGER DEFAULT 0");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Archived column added successfully");
        }
      }
    }
  }

  /** Migration: Add is_small_business column to owner table for Kleinunternehmerregelung. */
  private static void migrateAddIsSmallBusinessColumn(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    boolean hasSmallBusinessColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "owner"), null)) {
      if (columns == null) {
        return;
      }
      while (columns.next()) {
        if (COLUMN_IS_SMALL_BUSINESS.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasSmallBusinessColumn = true;
          break;
        }
      }
    }

    if (!hasSmallBusinessColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding is_small_business column to owner");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "ALTER TABLE owner ADD COLUMN " + COLUMN_IS_SMALL_BUSINESS + " BOOLEAN DEFAULT FALSE");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("is_small_business column added successfully");
        }
      }
    }
  }

  /** Migration: Add rendered_html column to invoice table for frozen document snapshots. */
  private static void migrateAddRenderedHtmlColumn(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    boolean hasRenderedHtmlColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice"), null)) {
      if (columns == null) {
        return;
      }
      while (columns.next()) {
        if ("rendered_html".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasRenderedHtmlColumn = true;
          break;
        }
      }
    }

    if (!hasRenderedHtmlColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding rendered_html column to invoice");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("ALTER TABLE invoice ADD COLUMN rendered_html TEXT");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("rendered_html column added successfully");
        }
      }
    }
  }

  /**
   * Migration: Convert paid boolean column to paid_date text column. This migration runs if the old
   * 'paid' column exists and 'paid_date' doesn't.
   */
  private static void migratePaidToPaidDate(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    // Check if old 'paid' column exists
    boolean hasPaidColumn = false;
    boolean hasPaidDateColumn = false;

    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice"), null)) {
      if (columns == null) {
        return;
      }
      while (columns.next()) {
        String columnName = columns.getString("COLUMN_NAME");
        if (COLUMN_PAID.equalsIgnoreCase(columnName)) {
          hasPaidColumn = true;
        }
        if (COLUMN_PAID_DATE.equalsIgnoreCase(columnName)) {
          hasPaidDateColumn = true;
        }
      }
    }

    // If we have the old paid column but not the new paid_date column, run migration
    if (hasPaidColumn && !hasPaidDateColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: converting paid column to paid_date");
      }

      try (Statement stmt = conn.createStatement()) {
        // Add new paid_date column
        stmt.execute("ALTER TABLE invoice ADD COLUMN paid_date TEXT");

        // Migrate data: if paid was true (1), set paid_date to current date
        stmt.execute("UPDATE invoice SET paid_date = date('now') WHERE paid = 1");

        // Drop old paid column (SQLite doesn't support DROP COLUMN directly,
        // so we need to recreate the table)
        stmt.execute(
            """
            CREATE TABLE invoice_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
                invoice_date TEXT NOT NULL,
                due_date TEXT,
                total_price REAL NOT NULL,
                tax_rate REAL NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 1),
                paid_date TEXT,
                notes TEXT
            )
            """);

        // Copy data to new table
        stmt.execute(
            """
            INSERT INTO invoice_new (id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes)
            SELECT id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes
            FROM invoice
            """);

        // Drop old table and rename new one
        stmt.execute("DROP TABLE invoice");
        stmt.execute("ALTER TABLE invoice_new RENAME TO invoice");

        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Migration completed successfully");
        }
      }
    }
  }

  /** Migration: Add equipment table and article.equipment_id column. */
  private static void migrateAddEquipmentTable(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    boolean hasEquipmentTable = false;
    try (ResultSet tables =
        metaData.getTables(conn.getCatalog(), null, table(metaData, "equipment"), null)) {
      if (tables.next()) {
        hasEquipmentTable = true;
      }
    }

    if (!hasEquipmentTable) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: creating equipment table");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            """
            CREATE TABLE equipment (
                id INT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                purchase_value DECIMAL(10,2),
                acquisition_date DATE,
                expected_lifetime_months INT,
                monthly_operating_cost DECIMAL(10,2) DEFAULT 0.00,
                residual_value DECIMAL(10,2) DEFAULT 0.00,
                status VARCHAR(50) DEFAULT 'active',
                notes TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
            """);
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Equipment table created successfully");
        }
      }
    }

    boolean hasEquipmentIdColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "service"), null)) {
      while (columns.next()) {
        if ("equipment_id".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasEquipmentIdColumn = true;
          break;
        }
      }
    }

    if (!hasEquipmentIdColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding equipment_id to service");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("ALTER TABLE service ADD COLUMN equipment_id INT");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("equipment_id column added to service");
        }
      }
    }
  }

  /** Migration: Add parent_item_id column to invoice_item for hierarchical line items. */
  private static void migrateAddParentItemIdColumn(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();

    boolean hasParentItemIdColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice_item"), null)) {
      while (columns.next()) {
        if ("parent_item_id".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasParentItemIdColumn = true;
          break;
        }
      }
    }

    if (!hasParentItemIdColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding parent_item_id to invoice_item");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("ALTER TABLE invoice_item ADD COLUMN parent_item_id INT NULL");
        stmt.execute(
            "ALTER TABLE invoice_item ADD CONSTRAINT fk_invoice_item_parent"
                + " FOREIGN KEY (parent_item_id) REFERENCES invoice_item(id) ON DELETE CASCADE");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("parent_item_id column added to invoice_item");
        }
      }
    }
  }

  /**
   * Migration: ensure the {@code parent_item_id} foreign key is {@code ON DELETE CASCADE} rather
   * than the original {@code ON DELETE SET NULL}. Older installs (created by {@link
   * #migrateAddParentItemIdColumn(Connection)} before the cascade rule was decided) had a SET NULL
   * FK; sub-items must be removed with their parent, so we drop the existing FK and re-add it with
   * the correct rule.
   */
  private static void migrateParentItemIdCascade(Connection conn) throws SQLException {
    Set<String> fkNames = new HashSet<>();
    try (ResultSet fks =
        conn.getMetaData()
            .getImportedKeys(conn.getCatalog(), null, table(conn.getMetaData(), "invoice_item"))) {
      while (fks.next()) {
        String fkColumn = fks.getString("FKCOLUMN_NAME");
        String pkTable = fks.getString("PKTABLE_NAME");
        String fkName = fks.getString("FK_NAME");
        if ("parent_item_id".equalsIgnoreCase(fkColumn)
            && "invoice_item".equalsIgnoreCase(pkTable)
            && fkName != null) {
          fkNames.add(fkName);
        }
      }
    }
    // If the only parent_item_id FK is the canonical CASCADE one we add below, the migration
    // has already run on this database and we leave the schema alone.
    if (fkNames.size() == 1 && fkNames.contains("fk_invoice_item_parent")) {
      return;
    }
    if (fkNames.isEmpty()) {
      // No FK present yet (column not added). migrateAddParentItemIdColumn runs before this and
      // installs the CASCADE variant, so this is the happy path for fresh installs.
      return;
    }
    if (LOGGER.isLoggable(Level.INFO)) {
      LOGGER.info(
          "Running migration: converting parent_item_id FK to ON DELETE CASCADE ("
              + fkNames.size()
              + " existing FK(s) to drop)");
    }
    try (Statement stmt = conn.createStatement()) {
      for (String name : fkNames) {
        try {
          stmt.execute("ALTER TABLE invoice_item DROP CONSTRAINT " + quoteIdentifier(name));
        } catch (SQLException dropError) {
          if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(
                "Could not drop parent_item_id FK '" + name + "': " + dropError.getMessage());
          }
        }
      }
      stmt.execute(
          "ALTER TABLE invoice_item ADD CONSTRAINT fk_invoice_item_parent"
              + " FOREIGN KEY (parent_item_id) REFERENCES invoice_item(id) ON DELETE CASCADE");
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("parent_item_id FK converted to ON DELETE CASCADE");
      }
    }
  }

  /**
   * Migration: drop the legacy {@code item_type IN ('ARTICLE', 'SERVICE')} CHECK, which rejects
   * {@code CUSTOM} note rows. Item types are validated in the application. Only CHECKs on {@code
   * item_type} that do not allow {@code CUSTOM} are dropped; the quantity/price CHECKs stay.
   */
  private static void migrateAddCustomItemType(Connection conn) throws SQLException {
    List<String> legacy = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS"
                    + " WHERE CONSTRAINT_SCHEMA = SCHEMA()")) {
      while (rs.next()) {
        String clause = rs.getString("CHECK_CLAUSE").toLowerCase(Locale.ROOT);
        if (clause.contains("item_type") && clause.contains(" in ") && !clause.contains("custom")) {
          legacy.add(rs.getString("CONSTRAINT_NAME"));
        }
      }
    }
    try (Statement stmt = conn.createStatement()) {
      for (String name : legacy) {
        stmt.execute("ALTER TABLE invoice_item DROP CONSTRAINT " + quoteIdentifier(name));
        LOGGER.info("Dropped legacy item_type CHECK constraint " + name);
      }
    }
  }

  /**
   * Migration: add {@code item_name} and {@code item_description} columns to {@code invoice_item}
   * so CUSTOM ("Freitext") rows can persist their user-supplied text. The columns are nullable so
   * existing rows stay valid; the editor populates them on save.
   */
  private static void migrateAddItemNameAndDescription(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    Set<String> existing = new HashSet<>();
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice_item"), null)) {
      while (columns.next()) {
        existing.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
      }
    }
    try (Statement stmt = conn.createStatement()) {
      if (!existing.contains("item_name")) {
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Running migration: adding item_name to invoice_item");
        }
        stmt.execute("ALTER TABLE invoice_item ADD COLUMN item_name VARCHAR(255) NULL");
      }
      if (!existing.contains("item_description")) {
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Running migration: adding item_description to invoice_item");
        }
        stmt.execute("ALTER TABLE invoice_item ADD COLUMN item_description TEXT NULL");
      }
    }
  }

  /**
   * Migration: add {@code template_id} column to {@code invoice} for the per-document layout
   * override. Nullable so existing documents continue to use the type-default template picker. The
   * value is not enforced as a foreign key to match the rest of the invoice columns' loose
   * conventions.
   */
  private static void migrateAddInvoiceTemplateId(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    boolean hasTemplateIdColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice"), null)) {
      while (columns.next()) {
        if ("template_id".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasTemplateIdColumn = true;
          break;
        }
      }
    }
    if (!hasTemplateIdColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding template_id to invoice");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("ALTER TABLE invoice ADD COLUMN template_id INT NULL");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("template_id column added to invoice");
        }
      }
    }
  }

  /**
   * Migration: add {@code is_document_specific} column to {@code invoice_template} so per-document
   * template clones (created via {@code POST /api/templates/from-document/{id}}) can be hidden from
   * the global {@code /templates} list. Defaults to {@code 0} (false) so existing global templates
   * are unaffected.
   */
  private static void migrateAddTemplateIsDocumentSpecific(Connection conn) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    boolean hasColumn = false;
    try (ResultSet columns =
        metaData.getColumns(conn.getCatalog(), null, table(metaData, "invoice_template"), null)) {
      while (columns.next()) {
        if ("is_document_specific".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
          hasColumn = true;
          break;
        }
      }
    }
    if (!hasColumn) {
      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Running migration: adding is_document_specific to invoice_template");
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "ALTER TABLE invoice_template ADD COLUMN is_document_specific TINYINT(1) DEFAULT 0");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("is_document_specific column added to invoice_template");
        }
      }
    }
  }

  /**
   * Migration: drop the stored {@code OVERDUE} document status. An invoice is now considered
   * overdue purely as a derived property of {@code SENT + past due_date + no paid_date}; the UI
   * computes it at render time and the backend no longer carries a stored flag for it.
   *
   * <p>Any existing rows that still carry {@code document_status_id = 7} are re-pointed to {@code
   * 2} ({@code SENT}) so the visual "overdue" state remains intact (the date-based UI will still
   * flag them), and the lookup row itself is removed from {@code document_status}.
   */
  private static void migrateDropOverdueStatus(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("UPDATE invoice SET document_status_id = 2 WHERE document_status_id = 7");
      stmt.executeUpdate("DELETE FROM document_status WHERE id = 7 AND name = 'OVERDUE'");
    }
    if (LOGGER.isLoggable(Level.INFO)) {
      LOGGER.info("Running migration: dropping OVERDUE document status (rows repointed to SENT)");
    }
  }

  /**
   * Migration: enforce the {@code paid_date} ↔ {@code document_status_id} invariant. A paid date
   * may only be set when the document status is PAID (id = 6). Any legacy rows that violate the
   * rule are first repointed to PAID (the paid date was clearly intentional), then a CHECK
   * constraint is added so the database refuses future violations.
   */
  private static void migrateEnforcePaidDateStatusInvariant(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      int updated =
          stmt.executeUpdate(
              "UPDATE invoice SET document_status_id = 6"
                  + " WHERE paid_date IS NOT NULL"
                  + " AND document_status_id != 6");
      if (updated > 0 && LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Repointed " + updated + " invoice(s) with paid_date to status PAID");
      }
    }

    boolean constraintExists = constraintExists(conn, "chk_invoice_paid_date_status");

    if (!constraintExists) {
      try (Statement stmt = conn.createStatement()) {
        stmt.executeUpdate(
            "ALTER TABLE invoice ADD CONSTRAINT chk_invoice_paid_date_status"
                + " CHECK (paid_date IS NULL OR document_status_id = 6)");
        if (LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info("Added chk_invoice_paid_date_status CHECK constraint to invoice");
        }
      }
    }
  }

  /**
   * Migration: add {@code article.note}, the internal note of a catalog article (never printed).
   * Before, the field existed in the entity only and was silently dropped on save. {@code ADD
   * COLUMN IF NOT EXISTS} makes this safe to run on every start.
   */
  private static void migrateAddArticleNote(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE article ADD COLUMN IF NOT EXISTS note TEXT");
    }
  }

  /**
   * Migration: replace the editor's literal sample values in {@code invoice_template.blocks} by
   * variables. Before, an "invoice data" block added in the editor stored {@code INV-2026-001} and
   * fixed dates, and the PDF renderer printed them instead of the document's real number and dates
   * (it only falls back to the document when the field is empty). Issued documents are not affected
   * because their PDF comes from the frozen {@code rendered_html} snapshot.
   *
   * <p>Idempotent: once replaced, the fragments no longer match.
   */
  private static void migrateTemplateSampleLiterals(Connection conn) throws SQLException {
    String sql = "UPDATE invoice_template SET blocks = REPLACE(blocks, ?, ?) WHERE blocks LIKE ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (Map.Entry<String, String> entry : TEMPLATE_SAMPLE_LITERALS.entrySet()) {
        ps.setString(1, entry.getKey());
        ps.setString(2, entry.getValue());
        ps.setString(3, "%" + entry.getKey() + "%");
        int updated = ps.executeUpdate();
        if (updated > 0 && LOGGER.isLoggable(Level.INFO)) {
          LOGGER.info(
              "Replaced template sample literal " + entry.getKey() + " in " + updated + " rows");
        }
      }
    }
  }

  /**
   * Migration: add {@code owner.url} and {@code owner.iban}. Both H2 schemas (desktop and test)
   * lacked them, so saving the company data failed on every desktop install. {@code ADD COLUMN IF
   * NOT EXISTS} makes this safe to run on every start.
   */
  private static void migrateAddOwnerUrlAndIban(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE owner ADD COLUMN IF NOT EXISTS url VARCHAR(255)");
      stmt.execute("ALTER TABLE owner ADD COLUMN IF NOT EXISTS iban VARCHAR(255)");
    }
  }

  /**
   * Migration: support Storno documents and partial payments.
   *
   * <ul>
   *   <li>Lookup rows {@code document_type 6 = CREDIT_NOTE} (Stornorechnung) and {@code
   *       document_status 9 = PARTIALLY_PAID}. Id 7 is skipped on purpose: it was the dropped
   *       {@code OVERDUE} status, and {@link #migrateDropOverdueStatus} still repoints rows with 7.
   *   <li>{@code invoice.cancels_invoice_id}: the invoice a credit note cancels.
   *   <li>{@code payment.reverses_payment_id}: the payment a reversal row (negative amount)
   *       reverses.
   * </ul>
   *
   * <p>Existing installs get the two columns without foreign keys (like {@code template_id}); new
   * installs get them from the schema files.
   */
  private static void migrateAddCreditNoteAndPartialPayment(Connection conn) throws SQLException {
    insertLookupRowIfMissing(conn, LookupTable.DOCUMENT_TYPE, 6, "CREDIT_NOTE");
    insertLookupRowIfMissing(conn, LookupTable.DOCUMENT_STATUS, 9, "PARTIALLY_PAID");
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE invoice ADD COLUMN IF NOT EXISTS cancels_invoice_id INT NULL");
      stmt.execute("ALTER TABLE payment ADD COLUMN IF NOT EXISTS reverses_payment_id INT NULL");
    }
  }

  /**
   * Migration: make {@code invoice.document_number} unique. Empty strings are normalised to {@code
   * NULL} first (drafts of invoice types have no number until they are issued, and several {@code
   * NULL}s are allowed by a unique index on both databases).
   *
   * <p>If legacy data already contains duplicate numbers the index cannot be created; the
   * duplicates are logged and the start continues, because issued documents must not be renumbered
   * automatically. The number series still prevents new duplicates.
   */
  private static void migrateUniqueDocumentNumber(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("UPDATE invoice SET document_number = NULL WHERE document_number = ''");
    }
    List<String> duplicates = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT document_number FROM invoice WHERE document_number IS NOT NULL"
                    + " GROUP BY document_number HAVING COUNT(*) > 1")) {
      while (rs.next()) {
        duplicates.add(rs.getString(1));
      }
    }
    if (!duplicates.isEmpty()) {
      LOGGER.severe(
          "Duplicate document numbers "
              + duplicates
              + " found; uq_invoice_document_number was not created. Resolve them manually.");
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_document_number"
              + " ON invoice(document_number)");
    }
  }

  /**
   * Migration: replace {@code invoice.customer_id ... ON DELETE CASCADE} with a restricting foreign
   * key. Issued documents must be retained (GoBD / §147 AO), so deleting a customer must never
   * delete their documents. {@code CustomerController} already answers 409; this makes the database
   * enforce it too.
   */
  private static void migrateCustomerForeignKeyRestrict(Connection conn) throws SQLException {
    List<String> cascadingKeys = new ArrayList<>();
    for (Map.Entry<String, String> key : invoiceCustomerForeignKeys(conn).entrySet()) {
      if ("CASCADE".equalsIgnoreCase(key.getValue())) {
        cascadingKeys.add(key.getKey());
      }
    }
    if (cascadingKeys.isEmpty()) {
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      for (String name : cascadingKeys) {
        // Identifier read from information_schema and quoted; identifiers cannot be parameters.
        // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        stmt.execute("ALTER TABLE invoice DROP CONSTRAINT " + quoteIdentifier(name));
      }
      stmt.execute(
          "ALTER TABLE invoice ADD CONSTRAINT fk_invoice_customer"
              + " FOREIGN KEY (customer_id) REFERENCES customer(id)");
    }
    LOGGER.info("Replaced cascading invoice.customer_id foreign key with a restricting one");
  }

  /**
   * Migration: give every invoice that was marked PAID before payments were recorded one payment
   * row for its gross amount, dated on its {@code paid_date}. The paid amount and the status are
   * derived from payments now; without the row such invoices would show nothing paid. Invoices that
   * already have payments are left alone, so this is idempotent.
   *
   * <p>Gross = net + tax rounded to cents, exactly as {@code InvoiceLifecycleService.grossAmount}
   * computes it ({@code tax_rate} is stored as a fraction).
   */
  private static void migrateBackfillLegacyPayments(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      int added =
          stmt.executeUpdate(
              "INSERT INTO payment (invoice_id, payment_date, amount, notes)"
                  + " SELECT i.id, i.paid_date,"
                  + " ROUND(i.total_price, 2) + ROUND(ROUND(i.total_price, 2) * i.tax_rate, 2),"
                  + " 'Aus dem Altbestand übernommen (Status bezahlt)'"
                  + " FROM invoice i"
                  + " WHERE i.document_status_id = 6 AND i.paid_date IS NOT NULL"
                  + " AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.invoice_id = i.id)");
      if (added > 0) {
        LOGGER.info("Recorded " + added + " legacy paid invoice(s) as payments");
      }
    }
  }

  /**
   * Migration: VAT rate and unit per invoice line, tax treatment per invoice, default rate per
   * catalog entry, buyer reference per customer, frozen party data per invoice.
   *
   * <p>The columns are added with {@code IF NOT EXISTS} on every start. The data backfill runs
   * exactly once, guarded by the {@value #VAT_PER_LINE_MARKER} row in {@code user_settings}:
   * running it again would overwrite rates that were set per line afterwards. It copies each
   * invoice's single rate ({@code invoice.tax_rate}, a fraction) to its lines as a percentage, and
   * marks invoices with rate 0 as Kleinunternehmer documents ({@code SMALL_BUSINESS}): before this
   * change a rate of 0 was only ever set by the small-business rule. Demo data is loaded before the
   * migrations, so it is backfilled the same way.
   */
  private static void migrateVatPerLine(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("ALTER TABLE invoice_item ADD COLUMN IF NOT EXISTS unit VARCHAR(50) NULL");
      stmt.execute(
          "ALTER TABLE invoice_item ADD COLUMN IF NOT EXISTS"
              + " tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00");
      stmt.execute(
          "ALTER TABLE invoice ADD COLUMN IF NOT EXISTS"
              + " tax_treatment VARCHAR(20) NOT NULL DEFAULT 'STANDARD'");
      stmt.execute(
          "ALTER TABLE invoice ADD COLUMN IF NOT EXISTS tax_exemption_reason VARCHAR(255) NULL");
      stmt.execute("ALTER TABLE invoice ADD COLUMN IF NOT EXISTS party_snapshot TEXT NULL");
      stmt.execute(
          "ALTER TABLE article ADD COLUMN IF NOT EXISTS"
              + " tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00");
      stmt.execute(
          "ALTER TABLE service ADD COLUMN IF NOT EXISTS"
              + " tax_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 19.00");
      stmt.execute("ALTER TABLE customer ADD COLUMN IF NOT EXISTS buyer_reference VARCHAR(255)");
    }
    try (PreparedStatement check =
        conn.prepareStatement("SELECT 1 FROM user_settings WHERE setting_key = ?")) {
      check.setString(1, VAT_PER_LINE_MARKER);
      try (ResultSet rs = check.executeQuery()) {
        if (rs.next()) {
          return;
        }
      }
    }
    try (Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "UPDATE invoice_item SET tax_rate_percent ="
              + " (SELECT ROUND(i.tax_rate * 100, 2) FROM invoice i WHERE i.id = invoice_item.invoice_id)");
      stmt.executeUpdate("UPDATE invoice SET tax_treatment = 'SMALL_BUSINESS' WHERE tax_rate = 0");
      // Units were only ever read live from the catalog; freeze what the catalog says today.
      stmt.executeUpdate(
          "UPDATE invoice_item SET unit = (SELECT a.unit FROM article a WHERE a.id = invoice_item.item_id)"
              + " WHERE unit IS NULL AND UPPER(item_type) = 'ARTICLE'");
      stmt.executeUpdate(
          "UPDATE invoice_item SET unit = (SELECT s.unit FROM service s WHERE s.id = invoice_item.item_id)"
              + " WHERE unit IS NULL AND UPPER(item_type) = 'SERVICE'");
    }
    try (PreparedStatement mark =
        conn.prepareStatement(
            "INSERT INTO user_settings (setting_key, setting_value) VALUES (?, 'done')")) {
      mark.setString(1, VAT_PER_LINE_MARKER);
      mark.executeUpdate();
    }
    LOGGER.info("Copied invoice VAT rates and units to the invoice lines");
  }

  /**
   * Returns the foreign keys from {@code invoice} to {@code customer} with their delete rule.
   *
   * <p>H2's {@code REFERENTIAL_CONSTRAINTS} has no table columns but unique constraint names per
   * schema, so it is joined with {@code KEY_COLUMN_USAGE}.
   *
   * @param conn the connection
   * @return constraint name to delete rule (e.g. {@code CASCADE}, {@code RESTRICT})
   * @throws SQLException if the metadata cannot be read
   */
  public static Map<String, String> invoiceCustomerForeignKeys(Connection conn)
      throws SQLException {
    String sql =
        "SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE"
            + " FROM information_schema.REFERENTIAL_CONSTRAINTS rc"
            + " JOIN information_schema.KEY_COLUMN_USAGE k"
            + " ON k.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA"
            + " AND k.CONSTRAINT_NAME = rc.CONSTRAINT_NAME"
            + " WHERE rc.CONSTRAINT_SCHEMA = SCHEMA()"
            + " AND UPPER(k.TABLE_NAME) = 'INVOICE'"
            + " AND UPPER(k.COLUMN_NAME) = 'CUSTOMER_ID'";
    Map<String, String> keys = new LinkedHashMap<>();
    try (PreparedStatement stmt = conn.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery()) {
      while (rs.next()) {
        keys.put(rs.getString(1), rs.getString(2));
      }
    }
    return keys;
  }

  /**
   * Quotes an identifier read from {@code information_schema} with SQL double quotes (H2).
   *
   * @param name the identifier exactly as stored
   * @return the quoted identifier
   */
  public static String quoteIdentifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /**
   * Lookup tables that migrations add rows to. Each carries its SQL as constants, so no statement
   * is ever assembled from a table name at runtime.
   */
  private enum LookupTable {
    DOCUMENT_TYPE(
        "SELECT 1 FROM document_type WHERE id = ?",
        "INSERT INTO document_type (id, name) VALUES (?, ?)"),
    DOCUMENT_STATUS(
        "SELECT 1 FROM document_status WHERE id = ?",
        "INSERT INTO document_status (id, name) VALUES (?, ?)");

    private final String selectSql;
    private final String insertSql;

    LookupTable(String selectSql, String insertSql) {
      this.selectSql = selectSql;
      this.insertSql = insertSql;
    }
  }

  /** Inserts a row into a lookup table unless a row with that id exists. */
  private static void insertLookupRowIfMissing(
      Connection conn, LookupTable table, int id, String name) throws SQLException {
    try (PreparedStatement check = conn.prepareStatement(table.selectSql)) {
      check.setInt(1, id);
      try (ResultSet rs = check.executeQuery()) {
        if (rs.next()) {
          return;
        }
      }
    }
    try (PreparedStatement insert = conn.prepareStatement(table.insertSql)) {
      insert.setInt(1, id);
      insert.setString(2, name);
      insert.executeUpdate();
    }
  }

  /**
   * Returns the table name in the case the database stores identifiers in (H2: upper case), for use
   * as a {@link DatabaseMetaData} lookup pattern.
   */
  private static String table(DatabaseMetaData metaData, String name) throws SQLException {
    return metaData.storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT) : name;
  }

  /** Case-insensitive check for a named constraint in the current schema. */
  private static boolean constraintExists(Connection conn, String constraintName)
      throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement(
            "SELECT 1 FROM information_schema.TABLE_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = SCHEMA() AND UPPER(CONSTRAINT_NAME) = UPPER(?)")) {
      stmt.setString(1, constraintName);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }
}
