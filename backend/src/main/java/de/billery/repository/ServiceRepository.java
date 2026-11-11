package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.api.dto.SearchResult.SearchService;
import de.billery.data.Service;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository class for managing Service entities in the database. Extends BaseRepository to inherit
 * common logging and error handling functionality.
 *
 * <p>This repository provides CRUD operations for service data including:
 *
 * <ul>
 *   <li>Finding all services or individual services by ID
 *   <li>Saving new services to the database
 *   <li>Updating existing service information
 *   <li>Deleting services from the database
 * </ul>
 *
 * <p>Services can optionally be linked to articles through the article_id field. All database
 * operations include proper error handling and logging through the BaseRepository class.
 *
 * @author Invoice Management System
 * @version 1.0
 */
@Repository
public class ServiceRepository extends BaseRepository {

  /** Maps a {@code service} row; NULL article / equipment references become 0. */
  private static final RowMapper<Service> SERVICE_MAPPER =
      (rs, rowNum) -> {
        Service service =
            new Service(
                rs.getInt("id"),
                rs.getString("servicename"),
                rs.getInt("article_id"),
                nullToEmpty(rs.getString("service_description")),
                decimalOrZero(rs, "sales_value"),
                nullToEmpty(rs.getString("unit")),
                ""); // note - not stored in the database
        service.setEquipmentId(rs.getInt("equipment_id"));
        service.setTaxRatePercent(decimalOrZero(rs, "tax_rate_percent"));
        return service;
      };

  /** Maps a {@code service} row to a search hit. */
  private static final RowMapper<SearchService> SEARCH_MAPPER =
      (rs, rowNum) ->
          new SearchService(
              rs.getInt("id"),
              nullToEmpty(rs.getString("servicename")),
              nullToEmpty(rs.getString("service_description")),
              decimalOrZero(rs, "sales_value"),
              nullToEmpty(rs.getString("unit")),
              rs.getInt("article_id"));

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public ServiceRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Retrieves all services ordered by id, so the catalog keeps its order when a service is edited.
   *
   * @return all services, or an empty list
   */
  public List<Service> findAll() {
    return run(
        "findAll services",
        () -> jdbc.sql("SELECT * FROM service ORDER BY id").query(SERVICE_MAPPER).list());
  }

  /**
   * Saves a new service. The generated ID is set on the passed object.
   *
   * @param service the service to save
   * @return the same service, now carrying its ID
   */
  public Service save(Service service) {
    return run(
        "save service",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bind(
                  jdbc.sql(
                      """
                      INSERT INTO service (servicename, sales_value, article_id, equipment_id,
                                           unit, service_description, tax_rate_percent)
                      VALUES (:name, :price, :articleId, :equipmentId, :unit, :description,
                              :taxRate)
                      """),
                  service)
              .update(keys);
          service.setId(generatedId(keys));
          return service;
        });
  }

  /**
   * Updates all columns of an existing service, identified by its ID.
   *
   * @param service the service with updated information
   */
  public void update(Service service) {
    run(
        "update service",
        () ->
            bind(
                    jdbc.sql(
                        """
                        UPDATE service SET servicename = :name, sales_value = :price,
                                           article_id = :articleId, equipment_id = :equipmentId,
                                           unit = :unit, service_description = :description,
                                           tax_rate_percent = :taxRate
                        WHERE id = :id
                        """),
                    service)
                .param("id", service.getId())
                .update());
  }

  /**
   * Deletes a service by ID.
   *
   * @param serviceId the ID of the service to delete
   */
  public void delete(int serviceId) {
    run(
        "delete service",
        () -> jdbc.sql("DELETE FROM service WHERE id = :id").param("id", serviceId).update());
  }

  /**
   * Finds a service by its ID.
   *
   * @param id the ID of the service to find
   * @return the service if found, null otherwise
   */
  public Service findById(int id) {
    return run(
        "findById service",
        () ->
            jdbc.sql("SELECT * FROM service WHERE id = :id")
                .param("id", id)
                .query(SERVICE_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Searches services by id, name, description and price, case-insensitively, against the raw query
   * and a variant with umlauts and ß transliterated.
   *
   * @param query the user's search text
   * @return up to 50 matches ordered by name
   */
  public List<SearchService> search(String query) {
    String normalizedQuery =
        query
            .replace("ä", "a")
            .replace("ö", "o")
            .replace("ü", "u")
            .replace("Ä", "A")
            .replace("Ö", "O")
            .replace("Ü", "U")
            .replace("ß", "ss");
    String sql =
        """
        SELECT * FROM service
        WHERE CAST(id AS VARCHAR(50)) LIKE :pattern
           OR LOWER(servicename) LIKE LOWER(:pattern)
           OR LOWER(service_description) LIKE LOWER(:pattern)
           OR CAST(sales_value AS VARCHAR(50)) LIKE :pattern
           OR LOWER(servicename) LIKE LOWER(:normalized)
           OR LOWER(service_description) LIKE LOWER(:normalized)
           OR CAST(sales_value AS VARCHAR(50)) LIKE :normalized
        ORDER BY servicename
        LIMIT 50
        """;
    return run(
        "search services",
        () ->
            jdbc.sql(sql)
                .param("pattern", "%" + query + "%")
                .param("normalized", "%" + normalizedQuery + "%")
                .query(SEARCH_MAPPER)
                .list());
  }

  /**
   * Binds the service's column values. Empty strings are stored as NULL, and an article or
   * equipment id of 0 means "no reference" (NULL).
   */
  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Service service) {
    return spec.param("name", emptyToNull(service.getName()))
        .param("price", service.getPrice())
        .param("articleId", service.getArticleId() == 0 ? null : service.getArticleId())
        .param("equipmentId", service.getEquipmentId() == 0 ? null : service.getEquipmentId())
        .param("unit", emptyToNull(service.getUnit()))
        .param("description", emptyToNull(service.getDescription()))
        .param("taxRate", service.getTaxRatePercent());
  }
}
