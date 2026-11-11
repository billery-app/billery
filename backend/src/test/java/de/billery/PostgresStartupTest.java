package de.billery;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.data.Customer;
import de.billery.data.Database;
import de.billery.data.Invoice;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.service.DocumentService;
import de.billery.service.InvoiceLifecycleService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Startup and a document round-trip against a real PostgreSQL (the test resets the {@code public}
 * schema first). H2 differs from PostgreSQL in strictness (GROUP BY, implicit casts), identity
 * columns and triggers, which is exactly where the server schema and the repositories' SQL break.
 *
 * <p>Runs only when {@code POSTGRES_TEST_URL} is set, e.g.
 *
 * <pre>
 * docker run -d --rm --name rp-test-db -p 55432:5432 -e POSTGRES_USER=rp \
 *   -e POSTGRES_PASSWORD=rp -e POSTGRES_DB=rp postgres:17
 * POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:55432/rp?user=rp&amp;password=rp' \
 *   mvn test -Dtest=PostgresStartupTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PostgresStartupTest {

  @BeforeAll
  static void connect() {
    Database.setDbPath(System.getenv("POSTGRES_TEST_URL"));
    // Start from an empty database: other suites may have left rows with explicit ids behind.
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP SCHEMA public CASCADE");
      stmt.execute("CREATE SCHEMA public");
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot reset the PostgreSQL test database", e);
    }
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void startTwiceThenIssueRenderAndCancelAnInvoice() throws Exception {
    MainApp.prepareDatabase();
    MainApp.prepareDatabase();
    // In the SaaS build the application runs as the application role under row-level security.
    TestDatabaseConfig.useAppRoleIfSaas();

    TestWiring.ownerRepository()
        .save(
            new Owner(
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
                "a@b.de",
                "https://example.de",
                "DE1",
                "DE02120300000000202051",
                false));
    int customerId =
        TestWiring.customerRepository()
            .save(new Customer("Max", "<Kunde>", "1", "Ort", "Weg", 1, "", "", "", ""))
            .getId();

    int lastNumber = TestWiring.seriesRepository().getLastNumber(1, LocalDate.now().getYear());
    DocumentService service = TestWiring.documentService();
    InvoiceItemDto item =
        new InvoiceItemDto(
            null,
            null,
            "article",
            0,
            "Beratung & Umsetzung",
            null,
            "Std",
            bd(3.0),
            BigDecimal.valueOf(0.335),
            null,
            null,
            List.of(),
            null);
    Invoice invoice =
        service.createDocument(
            new InvoiceDto(
                null,
                customerId,
                null,
                null,
                1,
                LocalDate.now(),
                LocalDate.now(),
                null,
                BigDecimal.valueOf(19),
                null,
                null,
                null,
                null,
                1,
                List.of(item),
                false,
                LocalDate.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    assertEquals("", invoice.getDocumentNumber(), "drafts are numbered on issue");
    InvoiceLifecycleService lifecycle = TestWiring.lifecycleService();
    Invoice issued = lifecycle.issue(invoice.getId());

    String expectedSuffix = String.format("-%03d", lastNumber + 1);
    assertTrue(issued.getDocumentNumber().endsWith(expectedSuffix), issued.getDocumentNumber());
    assertAmount(1.02, issued.getTotalPrice(), 0.0); // 0.335 is stored as 0.34
    byte[] pdf = TestWiring.pdfGenerator().generatePdfInvoice(issued);
    assertTrue(pdf.length > 1000);

    Invoice creditNote = lifecycle.cancel(issued.getId());
    assertEquals(issued.getId(), creditNote.getCancelsInvoiceId());
    assertTrue(creditNote.getRenderedHtml().contains("Stornorechnung"));
  }
}
