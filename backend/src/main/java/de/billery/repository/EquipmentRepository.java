package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.data.Equipment;
import de.billery.data.EquipmentUsage;
import de.billery.data.EquipmentUsageTotals;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link Equipment} (machines and tools whose cost is spread over services). All
 * statements run through the shared {@link JdbcClient}, so they join the caller's transaction;
 * database errors surface as {@code DatabaseException} (see {@link BaseRepository}).
 */
@Repository
public class EquipmentRepository extends BaseRepository {

  /** Maps an {@code equipment} row; the decimal columns stay null when unset. */
  private static final RowMapper<Equipment> EQUIPMENT_MAPPER =
      (rs, rowNum) ->
          new Equipment(
              rs.getInt("id"),
              nullToEmpty(rs.getString("name")),
              rs.getBigDecimal("purchase_value"),
              nullToEmpty(rs.getString("acquisition_date")),
              rs.getInt("expected_lifetime_months"),
              rs.getBigDecimal("monthly_operating_cost"),
              rs.getBigDecimal("residual_value"),
              nullToEmpty(rs.getString("status")),
              nullToEmpty(rs.getString("notes")));

  /**
   * The lines that count as a use of equipment: service lines on paid invoices ({@code
   * document_status_id = 6}) whose service is linked to equipment. Shared by the totals and the
   * list, so the dialog's rows always add up to the figures in the table.
   */
  private static final String PAID_SERVICE_LINES =
      """
      FROM invoice_item ii
      JOIN service s ON ii.item_id = s.id
      JOIN invoice i ON ii.invoice_id = i.id
      WHERE UPPER(ii.item_type) = 'SERVICE' AND i.document_status_id = 6
      """;

  /** Maps one line of {@link #PAID_SERVICE_LINES} for the usage list. */
  private static final RowMapper<EquipmentUsage> USAGE_MAPPER =
      (rs, rowNum) ->
          new EquipmentUsage(
              nullToEmpty(rs.getString("document_number")),
              nullToEmpty(rs.getString("invoice_date")),
              nullToEmpty(rs.getString("servicename")),
              decimalOrZero(rs, "quantity"),
              decimalOrZero(rs, "unit_price"),
              decimalOrZero(rs, "total_price"),
              nullToEmpty(rs.getString("unit")));

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public EquipmentRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Retrieves all equipment ordered by id, like articles and services: renaming an item does not
   * move it in the catalog.
   *
   * @return all equipment, or an empty list
   */
  public List<Equipment> findAll() {
    return run(
        "Failed to load equipment",
        () -> jdbc.sql("SELECT * FROM equipment ORDER BY id").query(EQUIPMENT_MAPPER).list());
  }

  /**
   * Finds equipment by its ID.
   *
   * @param id the equipment id
   * @return the equipment if found, null otherwise
   */
  public Equipment findById(int id) {
    return run(
        "Failed to find equipment " + id,
        () ->
            jdbc.sql("SELECT * FROM equipment WHERE id = :id")
                .param("id", id)
                .query(EQUIPMENT_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Sums revenue and uses of every piece of equipment in <strong>one</strong> grouped query (the
   * catalog shows all equipment at once; a query per service made the list slower with every
   * service). Equipment without a paid line is not in the map: read it with {@code getOrDefault(id,
   * EquipmentUsageTotals.NONE)}.
   *
   * @return the totals by equipment id
   */
  public Map<Integer, EquipmentUsageTotals> findUsageTotals() {
    return run(
        "Failed to load the equipment usage totals",
        () -> {
          Map<Integer, EquipmentUsageTotals> totals = new HashMap<>();
          jdbc.sql(
                  "SELECT s.equipment_id AS equipment_id,"
                      + " COALESCE(SUM(ii.total_price), 0) AS revenue, COUNT(*) AS usage_count "
                      + PAID_SERVICE_LINES
                      + " AND s.equipment_id IS NOT NULL GROUP BY s.equipment_id")
              .query(
                  rs -> {
                    totals.put(
                        rs.getInt("equipment_id"),
                        new EquipmentUsageTotals(
                            decimalOrZero(rs, "revenue"), rs.getInt("usage_count")));
                  });
          return totals;
        });
  }

  /**
   * Lists the uses of one piece of equipment, newest invoice first (one query: the invoice's number
   * and date come from the join, not from a lookup per line).
   *
   * @param equipmentId the equipment id
   * @return the lines, or an empty list
   */
  public List<EquipmentUsage> findUsage(int equipmentId) {
    return run(
        "Failed to load the usage of equipment " + equipmentId,
        () ->
            jdbc.sql(
                    "SELECT i.document_number, i.invoice_date, s.servicename, s.unit,"
                        + " ii.quantity, ii.unit_price, ii.total_price "
                        + PAID_SERVICE_LINES
                        + " AND s.equipment_id = :equipmentId"
                        + " ORDER BY i.invoice_date DESC, ii.id DESC")
                .param("equipmentId", equipmentId)
                .query(USAGE_MAPPER)
                .list());
  }

  /**
   * Saves new equipment. The generated ID is set on the passed object.
   *
   * @param equipment the equipment to save
   * @return the same equipment, now carrying its ID
   */
  public Equipment save(Equipment equipment) {
    return run(
        "Failed to save equipment",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bind(
                  jdbc.sql(
                      """
                      INSERT INTO equipment (name, purchase_value, acquisition_date,
                          expected_lifetime_months, monthly_operating_cost, residual_value,
                          status, notes)
                      VALUES (:name, :purchaseValue, :acquisitionDate, :lifetimeMonths,
                              :monthlyCost, :residualValue, :status, :notes)
                      """),
                  equipment)
              .update(keys);
          equipment.setId(generatedId(keys));
          return equipment;
        });
  }

  /**
   * Updates all columns of existing equipment, identified by its ID.
   *
   * @param equipment the equipment with updated information
   */
  public void update(Equipment equipment) {
    run(
        "Failed to update equipment " + equipment.getId(),
        () ->
            bind(
                    jdbc.sql(
                        """
                        UPDATE equipment SET name = :name, purchase_value = :purchaseValue,
                            acquisition_date = :acquisitionDate,
                            expected_lifetime_months = :lifetimeMonths,
                            monthly_operating_cost = :monthlyCost,
                            residual_value = :residualValue, status = :status, notes = :notes
                        WHERE id = :id
                        """),
                    equipment)
                .param("id", equipment.getId())
                .update());
  }

  /**
   * Deletes equipment by ID.
   *
   * @param id the equipment id
   */
  public void delete(int id) {
    run(
        "Failed to delete equipment " + id,
        () -> jdbc.sql("DELETE FROM equipment WHERE id = :id").param("id", id).update());
  }

  /**
   * Binds the equipment's column values. An empty date or notes is stored as NULL, and a lifetime
   * of 0 months means "unknown" (NULL).
   */
  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Equipment e) {
    return spec.param("name", e.getName())
        .param("purchaseValue", e.getPurchaseValue())
        .param("acquisitionDate", dateOrNull(e.getAcquisitionDate()))
        .param(
            "lifetimeMonths",
            e.getExpectedLifetimeMonths() > 0 ? e.getExpectedLifetimeMonths() : null)
        .param("monthlyCost", e.getMonthlyOperatingCost())
        .param("residualValue", e.getResidualValue())
        .param("status", e.getStatus())
        .param("notes", emptyToNull(e.getNotes()));
  }
}
