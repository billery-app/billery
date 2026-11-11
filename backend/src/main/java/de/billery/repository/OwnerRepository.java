package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.data.Owner;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository class for managing Owner entities in the database. Extends BaseRepository to inherit
 * common logging and error handling functionality.
 *
 * <p>This repository provides CRUD operations for owner data including:
 *
 * <ul>
 *   <li>Finding the primary owner or all owners
 *   <li>Saving new owners to the database
 *   <li>Updating existing owner information
 *   <li>Deleting owners from the database
 * </ul>
 *
 * <p>The owner represents the business entity that issues invoices. Typically, there is only one
 * owner per system, but multiple owners are supported. All statements run through the shared {@link
 * JdbcClient}, so they join the caller's transaction; database errors surface as {@code
 * DatabaseException} (see {@link BaseRepository}).
 *
 * @author Invoice Management System
 * @version 1.0
 */
@Repository
public class OwnerRepository extends BaseRepository {

  /** Maps an {@code owner} row; optional text columns become empty strings. */
  private static final RowMapper<Owner> OWNER_MAPPER =
      (rs, rowNum) ->
          new Owner(
              rs.getInt("id"),
              nullToEmpty(rs.getString("first_name")),
              nullToEmpty(rs.getString("last_name")),
              nullToEmpty(rs.getString("company_name")),
              nullToEmpty(rs.getString("company_type")),
              nullToEmpty(rs.getString("owner_postal_code")),
              nullToEmpty(rs.getString("owner_city")),
              nullToEmpty(rs.getString("owner_street")),
              rs.getInt("owner_house_number"),
              nullToEmpty(rs.getString("phone")),
              nullToEmpty(rs.getString("email")),
              nullToEmpty(rs.getString("url")),
              nullToEmpty(rs.getString("tax_id")),
              nullToEmpty(rs.getString("iban")),
              rs.getBoolean("is_small_business"));

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public OwnerRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Finds the primary owner (first owner in the database), typically the main business owner for
   * display on invoices and in the application footer.
   *
   * @return the primary owner if found, null otherwise
   */
  public Owner findOwner() {
    return run(
        "findOwner",
        () -> jdbc.sql("SELECT * FROM owner LIMIT 1").query(OWNER_MAPPER).optional().orElse(null));
  }

  /**
   * Retrieves all owners from the database.
   *
   * @return a list of all owners, or an empty list if none exist
   */
  public List<Owner> findAll() {
    return run("findAll owners", () -> jdbc.sql("SELECT * FROM owner").query(OWNER_MAPPER).list());
  }

  /**
   * Saves a new owner to the database and stores the generated ID on it, so a caller that returns
   * the owner (POST /api/owners) answers with the real ID instead of 0.
   *
   * @param owner the owner to save
   * @return the same owner, now carrying its ID
   */
  public Owner save(Owner owner) {
    return run(
        "save owner",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bind(
                  jdbc.sql(
                      """
                        INSERT INTO owner (first_name, last_name, company_name, company_type,
                                           owner_postal_code, owner_city, owner_street,
                                           owner_house_number, phone, email, url, tax_id, iban,
                                           is_small_business)
                        VALUES (:firstName, :lastName, :companyName, :companyType, :postalCode,
                                :city, :street, :houseNumber, :phone, :email, :url, :taxId, :iban,
                                :smallBusiness)
                        """),
                  owner)
              .update(keys);
          owner.setId(generatedId(keys));
          return owner;
        });
  }

  /**
   * Updates an existing owner in the database. The owner is identified by its ID and all fields are
   * updated.
   *
   * @param owner the owner with updated information
   */
  public void update(Owner owner) {
    run(
        "update owner",
        () ->
            bind(
                    jdbc.sql(
                        """
                        UPDATE owner SET first_name = :firstName, last_name = :lastName,
                                         company_name = :companyName, company_type = :companyType,
                                         owner_postal_code = :postalCode, owner_city = :city,
                                         owner_street = :street, owner_house_number = :houseNumber,
                                         phone = :phone, email = :email, url = :url,
                                         tax_id = :taxId, iban = :iban,
                                         is_small_business = :smallBusiness
                        WHERE id = :id
                        """),
                    owner)
                .param("id", owner.getId())
                .update());
  }

  /**
   * Deletes an owner from the database by ID.
   *
   * @param id the ID of the owner to delete
   */
  public void delete(int id) {
    run(
        "delete owner",
        () -> jdbc.sql("DELETE FROM owner WHERE id = :id").param("id", id).update());
  }

  /**
   * Finds an owner by their unique ID.
   *
   * @param id the ID of the owner to find
   * @return the owner if found, null otherwise
   */
  public Owner findById(int id) {
    return run(
        "findById owner",
        () ->
            jdbc.sql("SELECT * FROM owner WHERE id = :id")
                .param("id", id)
                .query(OWNER_MAPPER)
                .optional()
                .orElse(null));
  }

  /** Binds the owner's column values (empty strings stored as NULL). */
  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Owner owner) {
    return spec.param("firstName", emptyToNull(owner.getFirstName()))
        .param("lastName", emptyToNull(owner.getLastName()))
        .param("companyName", emptyToNull(owner.getCompanyName()))
        .param("companyType", emptyToNull(owner.getCompanyType()))
        .param("postalCode", emptyToNull(owner.getPostalCode()))
        .param("city", emptyToNull(owner.getCity()))
        .param("street", emptyToNull(owner.getStreet()))
        .param("houseNumber", owner.getHouseNumber())
        .param("phone", emptyToNull(owner.getPhone()))
        .param("email", emptyToNull(owner.getEmail()))
        .param("url", emptyToNull(owner.getUrl()))
        .param("taxId", emptyToNull(owner.getTaxId()))
        .param("iban", emptyToNull(owner.getIban()))
        .param("smallBusiness", owner.isSmallBusiness());
  }
}
