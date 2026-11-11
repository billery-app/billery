package de.billery.repository;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Database;
import de.billery.data.TestDatabaseConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.*;

class DocumentNumberSeriesRepositoryTest {

  private static DocumentNumberSeriesRepository repository;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    repository = TestWiring.seriesRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws SQLException {
    TestDatabaseConfig.clearData();
  }

  @Test
  void getNextNumber_startsAt1WhenNoRowExists() {
    int result = repository.getNextNumber(1, 2026);
    assertEquals(1, result, "First invoice number should start at 1");
  }

  @Test
  void getNextNumber_incrementsCorrectly() {
    int first = repository.getNextNumber(1, 2026);
    int second = repository.getNextNumber(1, 2026);
    int third = repository.getNextNumber(1, 2026);

    assertEquals(1, first, "First number should be 1");
    assertEquals(2, second, "Second number should be 2");
    assertEquals(3, third, "Third number should be 3");
  }

  @Test
  void getNextNumber_handlesExistingRowWithLastNumberZero() throws SQLException {
    insertSeriesRow(1, 2026, 0);

    int result = repository.getNextNumber(1, 2026);
    assertEquals(1, result, "Should return 1 when last_number is 0");
  }

  @Test
  void getNextNumber_handlesExistingRowWithLastNumberGreaterThanZero() throws SQLException {
    insertSeriesRow(1, 2026, 5);

    int result = repository.getNextNumber(1, 2026);
    assertEquals(6, result, "Should return last_number + 1");
  }

  @Test
  void getNextNumber_isIndependentPerDocumentType() {
    int invoice1 = repository.getNextNumber(1, 2026);
    int offer1 = repository.getNextNumber(2, 2026);
    int invoice2 = repository.getNextNumber(1, 2026);

    assertEquals(1, invoice1);
    assertEquals(1, offer1);
    assertEquals(2, invoice2);
  }

  @Test
  void getNextNumber_isIndependentPerYear() {
    int y2025 = repository.getNextNumber(1, 2025);
    int y2026 = repository.getNextNumber(1, 2026);
    int y2025_2 = repository.getNextNumber(1, 2025);

    assertEquals(1, y2025);
    assertEquals(1, y2026);
    assertEquals(2, y2025_2);
  }

  @Test
  void getLastNumber_returns0WhenNoRowExists() {
    int result = repository.getLastNumber(1, 2026);
    assertEquals(0, result, "Should return 0 when no row exists");
  }

  @Test
  void getLastNumber_returnsLastNumber() throws SQLException {
    insertSeriesRow(1, 2026, 7);

    int result = repository.getLastNumber(1, 2026);
    assertEquals(7, result, "Should return the last_number from the row");
  }

  private void insertSeriesRow(int documentTypeId, int year, int lastNumber) throws SQLException {
    try (Connection conn = Database.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO document_number_series (document_type_id, doc_year, last_number) VALUES (?, ?, ?)")) {
      stmt.setInt(1, documentTypeId);
      stmt.setInt(2, year);
      stmt.setInt(3, lastNumber);
      stmt.executeUpdate();
    }
  }
}
