package de.billery.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-type, per-year counters for document numbers ({@code document_number_series}).
 *
 * <p>{@link #getNextNumber} increments with a single {@code UPDATE} first, which takes the row lock
 * and so serialises concurrent callers at the database level. It runs in a transaction of its own
 * or, when called inside one (a document save), joins it, so the increment is rolled back together
 * with a failed save and a failed save leaves no gap in the number sequence.
 */
@Repository
public class DocumentNumberSeriesRepository extends BaseRepository {

  private static final String INCREMENT_SQL =
      "UPDATE document_number_series SET last_number = last_number + 1"
          + " WHERE document_type_id = :type AND doc_year = :year";
  private static final String INSERT_SQL =
      "INSERT INTO document_number_series (document_type_id, doc_year, last_number)"
          + " VALUES (:type, :year, 1)";
  private static final String SELECT_SQL =
      "SELECT last_number FROM document_number_series"
          + " WHERE document_type_id = :type AND doc_year = :year";

  private final JdbcClient jdbc;

  /** Runs each write in one database transaction. */
  private final TransactionTemplate transactions;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   * @param transactions runs each write in one database transaction
   */
  public DocumentNumberSeriesRepository(JdbcClient jdbc, TransactionTemplate transactions) {
    super();
    this.transactions = transactions;
    this.jdbc = jdbc;
  }

  /**
   * Reserves and returns the next number for the given document type and year (1 for the first
   * document of a year).
   *
   * @param documentTypeId the document type
   * @param year the calendar year
   * @return the reserved number
   * @throws de.billery.data.DatabaseException if the counter cannot be updated
   */
  public int getNextNumber(int documentTypeId, int year) {
    return transactions.execute(
        status ->
            run(
                "getNextNumber",
                () -> {
                  if (execute(INCREMENT_SQL, documentTypeId, year) == 0) {
                    execute(INSERT_SQL, documentTypeId, year);
                  }
                  return readLastNumber(documentTypeId, year);
                }));
  }

  /**
   * Returns the last number handed out for the given type and year, without reserving one.
   *
   * @param documentTypeId the document type
   * @param year the calendar year
   * @return the last number, or 0 if none has been issued yet
   */
  public int getLastNumber(int documentTypeId, int year) {
    return run("getLastNumber", () -> readLastNumber(documentTypeId, year));
  }

  private int execute(String sql, int documentTypeId, int year) {
    return jdbc.sql(sql).param("type", documentTypeId).param("year", year).update();
  }

  private int readLastNumber(int documentTypeId, int year) {
    return jdbc.sql(SELECT_SQL)
        .param("type", documentTypeId)
        .param("year", year)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }
}
