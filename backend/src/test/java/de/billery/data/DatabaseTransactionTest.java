package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentNumberSeriesRepository;
import java.sql.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction semantics the services rely on, with the application's transaction configuration
 * ({@code DbConfig.newTransactionManager}): commit, rollback of every repository write, nesting,
 * plain-JDBC code inside a transaction, and number reservations released on failure.
 */
class DatabaseTransactionTest {

  private CustomerRepository customers;
  private TransactionTemplate transactions;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clear() throws Exception {
    TestDatabaseConfig.clearData();
    customers = TestWiring.customerRepository();
    transactions = TestWiring.transactions();
  }

  @Test
  void commitsWhenWorkReturns() {
    transactions.execute(status -> customers.save(customer("A")));

    assertEquals(1, customers.findAll().size());
  }

  @Test
  void rollsBackEveryRepositoryWriteWhenWorkThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  customers.save(customer("A"));
                  customers.save(customer("B"));
                  throw new IllegalStateException("boom");
                }));

    assertTrue(customers.findAll().isEmpty());
  }

  @Test
  void nestedCallsJoinTheOuterTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  transactions.execute(inner -> customers.save(customer("inner")));
                  throw new IllegalStateException("outer fails after inner returned");
                }));

    assertTrue(customers.findAll().isEmpty(), "inner work must not commit on its own");
  }

  @Test
  void handledNestedFailureDoesNotDoomTheOuterTransaction() {
    transactions.execute(
        status -> {
          customers.save(customer("kept"));
          assertThrows(
              IllegalStateException.class,
              () ->
                  transactions.execute(
                      inner -> {
                        throw new IllegalStateException("handled by the caller");
                      }));
          return null;
        });

    assertEquals(1, customers.findAll().size());
  }

  @Test
  void plainJdbcCodeCannotEndTheTransactionEarly() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  customers.save(customer("A"));
                  try (Connection conn = Database.getConnection()) {
                    conn.setAutoCommit(true); // ignored inside a transaction
                    conn.commit(); // ignored
                    assertFalse(conn.isClosed());
                  } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                  }
                  throw new IllegalStateException("roll back after the attempted commit");
                }));

    assertTrue(customers.findAll().isEmpty(), "the ignored commit must not have stored anything");
  }

  @Test
  void numberReservedInAFailedTransactionIsReleased() {
    DocumentNumberSeriesRepository series = TestWiring.seriesRepository();

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  series.getNextNumber(1, 2030);
                  throw new IllegalStateException("document save failed");
                }));

    assertEquals(1, series.getNextNumber(1, 2030));
  }

  private static Customer customer(String name) {
    return new Customer("Max", name, "1", "Ort", "Weg", 1, "", "", "", "");
  }
}
