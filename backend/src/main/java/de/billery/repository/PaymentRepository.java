package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.data.Payment;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code payment} table. The table is append-only: there is no update or delete
 * method on purpose (see {@link Payment}).
 */
@Repository
public class PaymentRepository extends BaseRepository {

  /** Maps a {@code payment} row; amounts are normalised to two decimals. */
  private static final RowMapper<Payment> PAYMENT_MAPPER =
      (rs, rowNum) -> {
        int reverses = rs.getInt("reverses_payment_id");
        Integer reversesPaymentId = rs.wasNull() ? null : reverses;
        return new Payment(
            rs.getInt("id"),
            rs.getInt("invoice_id"),
            rs.getDate("payment_date").toLocalDate(),
            rs.getBigDecimal("amount").setScale(2),
            nullToEmpty(rs.getString("payment_method")),
            nullToEmpty(rs.getString("reference")),
            nullToEmpty(rs.getString("notes")),
            reversesPaymentId);
      };

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public PaymentRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Inserts a payment.
   *
   * @param payment the payment; its id is ignored
   * @return the saved payment with its generated id
   */
  public Payment save(Payment payment) {
    return run(
        "save payment",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          jdbc.sql(
                  """
                  INSERT INTO payment (invoice_id, payment_date, amount, payment_method, reference,
                                       notes, reverses_payment_id)
                  VALUES (:invoiceId, :paymentDate, :amount, :method, :reference, :notes,
                          :reversesPaymentId)
                  """)
              .param("invoiceId", payment.invoiceId())
              .param("paymentDate", payment.paymentDate())
              .param("amount", payment.amount())
              .param("method", emptyToNull(payment.paymentMethod()))
              .param("reference", emptyToNull(payment.reference()))
              .param("notes", emptyToNull(payment.notes()))
              .param("reversesPaymentId", payment.reversesPaymentId())
              .update(keys);
          return new Payment(
              generatedId(keys),
              payment.invoiceId(),
              payment.paymentDate(),
              payment.amount(),
              payment.paymentMethod(),
              payment.reference(),
              payment.notes(),
              payment.reversesPaymentId());
        });
  }

  /**
   * Returns all payments of an invoice, reversals included, oldest first.
   *
   * @param invoiceId the invoice
   * @return the payments (empty if none)
   */
  public List<Payment> findByInvoiceId(int invoiceId) {
    return run(
        "findByInvoiceId payments",
        () ->
            jdbc.sql(
                    "SELECT * FROM payment WHERE invoice_id = :invoiceId ORDER BY payment_date, id")
                .param("invoiceId", invoiceId)
                .query(PAYMENT_MAPPER)
                .list());
  }

  /**
   * Finds one payment.
   *
   * @param id the payment id
   * @return the payment, or {@code null} if it does not exist
   */
  public Payment findById(int id) {
    return run(
        "findById payment",
        () ->
            jdbc.sql("SELECT * FROM payment WHERE id = :id")
                .param("id", id)
                .query(PAYMENT_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Tells whether a payment has already been reversed.
   *
   * @param id the payment id
   * @return {@code true} if a reversal row points at it
   */
  public boolean isReversed(int id) {
    return run(
        "isReversed payment",
        () ->
            !jdbc.sql("SELECT 1 FROM payment WHERE reverses_payment_id = :id")
                .param("id", id)
                .query()
                .listOfRows()
                .isEmpty());
  }

  /**
   * Sums the payments of an invoice (reversals count negatively).
   *
   * @param invoiceId the invoice
   * @return the amount received so far, 0 if none
   */
  public BigDecimal sumByInvoiceId(int invoiceId) {
    return run(
        "sumByInvoiceId payments",
        () ->
            jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM payment WHERE invoice_id = :invoiceId")
                .param("invoiceId", invoiceId)
                .query(BigDecimal.class)
                .single()
                .setScale(2));
  }

  /**
   * Sums the payments of every document in one query (reversals count negative).
   *
   * @return document id → paid amount (scale 2); documents without payments are absent
   */
  public Map<Integer, BigDecimal> sumsByInvoice() {
    return run(
        "sumsByInvoice payments",
        () -> {
          Map<Integer, BigDecimal> result = new HashMap<>();
          jdbc.sql("SELECT invoice_id, SUM(amount) AS paid FROM payment GROUP BY invoice_id")
              .query(
                  rs -> {
                    result.put(rs.getInt("invoice_id"), rs.getBigDecimal("paid").setScale(2));
                  });
          return result;
        });
  }

  /**
   * Like {@link #sumsByInvoice()}, restricted to the given documents (one page of the list).
   *
   * @param invoiceIds the document ids
   * @return document id → paid amount (scale 2); documents without payments are absent
   */
  public Map<Integer, BigDecimal> sumsByInvoiceIds(Collection<Integer> invoiceIds) {
    if (invoiceIds.isEmpty()) {
      return new HashMap<>();
    }
    return run(
        "sumsByInvoiceIds payments",
        () -> {
          Map<Integer, BigDecimal> result = new HashMap<>();
          jdbc.sql(
                  "SELECT invoice_id, SUM(amount) AS paid FROM payment"
                      + " WHERE invoice_id IN (:ids) GROUP BY invoice_id")
              .param("ids", invoiceIds)
              .query(
                  rs -> {
                    result.put(rs.getInt("invoice_id"), rs.getBigDecimal("paid").setScale(2));
                  });
          return result;
        });
  }
}
