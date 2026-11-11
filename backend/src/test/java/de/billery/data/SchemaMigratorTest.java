package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Flyway against a real PostgreSQL: a fresh database, a re-run, a database that predates Flyway,
 * several instances starting at once, and the demo data on top of the migrated schema.
 *
 * <p>Runs only when {@code POSTGRES_TEST_URL} is set (it wipes the {@code public} schema).
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class SchemaMigratorTest {

  /** Core migrations; grows with every {@code V*.sql} file in {@code db/migration}. */
  private static final int CORE_FILES = migrationFiles("db/migration");

  /**
   * Migrations one run applies to an empty database: the core ones, plus the SaaS ones when the
   * {@code saas} profile is active (the {@code -Psaas} build runs the whole suite with it).
   */
  private static final int MIGRATION_FILES =
      CORE_FILES + (AppProfiles.isSaas() ? migrationFiles("db/migration-saas") : 0);

  private static int migrationFiles(String location) {
    try {
      return new PathMatchingResourcePatternResolver()
          .getResources("classpath:" + location + "/V*.sql")
          .length;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @BeforeAll
  static void connect() {
    Database.setDbPath(System.getenv("POSTGRES_TEST_URL"));
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void emptyDatabase() throws SQLException {
    execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static int count(String sql) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static boolean loadRealDemoData() {
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/dummies.sql"))) {
      return DatabaseInitializer.loadDemoData(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void emptyDatabaseGetsTheBaselineAndASecondRunHasNothingToDo() throws SQLException {
    assertEquals(MIGRATION_FILES, SchemaMigrator.migrate());
    assertEquals(
        CORE_FILES, count("SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'SQL'"));
    assertEquals(4, count("SELECT COUNT(*) FROM invoice_template"), "seed templates");
    assertEquals(0, SchemaMigrator.migrate());
  }

  @Test
  void databaseWithoutFlywayHistoryIsBaselinedNotMigratedAgain() throws SQLException {
    // A database created from the baseline before Flyway was introduced: V1's schema, no history.
    Flyway.configure()
        .dataSource(Database.dataSource())
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();
    execute("DROP TABLE flyway_schema_history");

    assertEquals(
        MIGRATION_FILES - 1,
        SchemaMigrator.migrate(),
        "V1 is the baseline and must not run twice; every later migration is applied");
    assertEquals(1, count("SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE'"));
    assertEquals(4, count("SELECT COUNT(*) FROM invoice_template"), "seed data untouched");
  }

  /**
   * Several instances starting against an empty database, many rounds in a row: the race between
   * creating the history table and baselining it is narrow, so a single round can pass by luck.
   */
  @Test
  void instancesStartingAtOnceMigrateExactlyOnce() throws Exception {
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int round = 0; round < 15; round++) {
        emptyDatabase();
        List<Callable<Integer>> starts =
            java.util.Collections.nCopies(threads, SchemaMigrator::migrate);
        int applied = 0;
        for (Future<Integer> result : pool.invokeAll(starts)) {
          applied += result.get();
        }
        assertEquals(
            MIGRATION_FILES, applied, "round " + round + ": each migration is applied once");
        assertEquals(
            CORE_FILES, count("SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'SQL'"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void demoDataLoadsOntoTheMigratedSchemaOnce() throws SQLException {
    assumeFalse(AppProfiles.isSaas(), "demo data is a self-host / development feature");
    SchemaMigrator.migrate();

    // The real file, not the classpath: src/test/resources/dummies.sql (H2 fixture) shadows it.
    assertTrue(loadRealDemoData());
    assertEquals(100, count("SELECT COUNT(*) FROM customer"));
    assertEquals(1420, count("SELECT COUNT(*) FROM invoice"));
    assertFalse(loadRealDemoData(), "a second load must be skipped");
  }

  /**
   * The demo data is what people see in a demo: every customer name and every catalog entry once,
   * real umlauts, no "Demo"/"Dummy" placeholder texts, every catalog entry used on a document, and
   * a history that consists of the entries the application writes and matches each document's
   * status.
   */
  @Test
  void demoDataReadsLikeRealDataInADemo() throws SQLException {
    assumeFalse(AppProfiles.isSaas(), "demo data is a self-host / development feature");
    SchemaMigrator.migrate();
    assertTrue(loadRealDemoData());

    assertEquals(
        100, count("SELECT COUNT(DISTINCT CONCAT(first_name, ' ', last_name)) FROM customer"));
    assertEquals(100, count("SELECT COUNT(DISTINCT email) FROM customer"));
    assertEquals(30, count("SELECT COUNT(DISTINCT article_name) FROM article"));
    assertEquals(30, count("SELECT COUNT(*) FROM article"));
    assertEquals(30, count("SELECT COUNT(DISTINCT servicename) FROM service"));
    assertEquals(30, count("SELECT COUNT(*) FROM service"));
    assertEquals(15, count("SELECT COUNT(DISTINCT name) FROM equipment"));
    assertEquals(15, count("SELECT COUNT(*) FROM equipment"));

    // Every catalog entry appears on a document, so the pickers and statistics are not half empty.
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM article a WHERE NOT EXISTS (SELECT 1 FROM invoice_item i"
                + " WHERE i.item_type = 'ARTICLE' AND i.item_id = a.id)"));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM service s WHERE NOT EXISTS (SELECT 1 FROM invoice_item i"
                + " WHERE i.item_type = 'SERVICE' AND i.item_id = s.id)"));

    // No placeholder texts and no spelled-out umlauts in what the screens and PDFs show.
    String visible =
        "SELECT CONCAT_WS(' ', first_name, last_name, city, street, note) AS t FROM customer"
            + " UNION ALL SELECT CONCAT_WS(' ', article_name, unit, article_description)"
            + " FROM article"
            + " UNION ALL SELECT CONCAT_WS(' ', servicename, unit, service_description)"
            + " FROM service"
            + " UNION ALL SELECT CONCAT_WS(' ', name, notes) FROM equipment"
            + " UNION ALL SELECT CONCAT_WS(' ', notes, cost_calculation_basis) FROM invoice"
            + " UNION ALL SELECT CONCAT_WS(' ', item_name, item_description) FROM invoice_item"
            + " UNION ALL SELECT CONCAT_WS(' ', payment_method, notes) FROM payment"
            + " UNION ALL SELECT notes FROM document_history"
            + " UNION ALL SELECT note FROM owner";
    assertEquals(
        0,
        count("SELECT COUNT(*) FROM (" + visible + ") v WHERE t ~* '(demo|dummy)'"),
        "placeholder texts");
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM ("
                + visible
                + ") v WHERE t ~ '(Mue|Stueck|fuer|strasse|Strasse|Geraet|ueber|Koeln|Nuern)'"),
        "spelled-out umlauts");

    // History: only actions the application writes, one creation per document, nothing but the
    // creation for a draft, and a payment entry that matches the payment row.
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM document_history WHERE action NOT IN"
                + " ('CREATED', 'ISSUED', 'STATUS_CHANGED', 'PAYMENT_RECORDED', 'CANCELLED')"));
    assertEquals(
        1420,
        count("SELECT COUNT(DISTINCT invoice_id) FROM document_history WHERE action = 'CREATED'"));
    assertEquals(1420, count("SELECT COUNT(*) FROM document_history WHERE action = 'CREATED'"));
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM document_history h JOIN invoice i ON i.id = h.invoice_id"
                + " WHERE i.document_status_id = 1 AND h.action <> 'CREATED'"),
        "a draft has nothing but its creation");
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM document_history h JOIN invoice i ON i.id = h.invoice_id"
                + " WHERE i.document_type_id IN (1, 4) AND i.document_status_id <> 1"
                + " AND NOT EXISTS (SELECT 1 FROM document_history x"
                + " WHERE x.invoice_id = i.id AND x.action = 'ISSUED')"),
        "every issued invoice has its ISSUED entry");
    assertEquals(
        count("SELECT COUNT(*) FROM payment"),
        count("SELECT COUNT(*) FROM document_history WHERE action = 'PAYMENT_RECORDED'"));
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT p.amount, p.payment_date, h.notes, CAST(h.action_date AS DATE)"
                    + " FROM payment p JOIN document_history h ON h.invoice_id = p.invoice_id"
                    + " AND h.action = 'PAYMENT_RECORDED'")) {
      int rows = 0;
      while (rs.next()) {
        java.time.LocalDate paid = rs.getObject(2, java.time.LocalDate.class);
        // The same text InvoiceLifecycleService.recordPayment writes.
        String expected =
            String.format(java.util.Locale.GERMANY, "%,.2f €", rs.getBigDecimal(1))
                + " am "
                + paid.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertEquals(expected, rs.getString(3));
        assertEquals(paid, rs.getObject(4, java.time.LocalDate.class));
        rows++;
      }
      assertTrue(rows > 300, "paid invoices with their payment entry: " + rows);
    }
  }

  @Test
  void saasProfileWithoutTheSaasCodeFailsFastInsteadOfRunningWithoutTenancy() {
    assumeTrue(
        SchemaMigrator.class.getResource("/db/migration-saas") == null,
        "only meaningful in a build without -Psaas");
    String before = System.getProperty("spring.profiles.active");
    System.setProperty("spring.profiles.active", "saas");
    try {
      DatabaseException failure = assertThrows(DatabaseException.class, SchemaMigrator::migrate);
      assertTrue(failure.getCause().getMessage().contains("-Psaas"), failure.getMessage());
    } finally {
      if (before == null) {
        System.clearProperty("spring.profiles.active");
      } else {
        System.setProperty("spring.profiles.active", before);
      }
    }
  }
}
