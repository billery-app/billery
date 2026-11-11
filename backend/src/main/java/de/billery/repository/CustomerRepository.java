package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.api.dto.SearchResult.SearchCustomer;
import de.billery.data.Customer;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository class for managing Customer entities in the database. Extends BaseRepository to
 * inherit common logging and error handling functionality.
 *
 * <p>This repository provides CRUD operations for customer data including:
 *
 * <ul>
 *   <li>Finding all customers or individual customers by ID
 *   <li>Saving new customers to the database
 *   <li>Updating existing customer information
 *   <li>Deleting customers from the database
 * </ul>
 *
 * <p>All statements run through the shared {@link JdbcClient}, so they join the caller's
 * transaction; database errors surface as {@code DatabaseException} (see {@link BaseRepository}).
 *
 * @author Invoice Management System
 * @version 1.0
 */
@Repository
public class CustomerRepository extends BaseRepository {

  private static final String COLUMNS =
      """
      first_name = :firstName, last_name = :lastName, postal_code = :postalCode, city = :city,
      street = :street, house_number = :houseNumber, phone = :phone, email = :email,
      tax_id = :taxId, note = :note, buyer_reference = :buyerReference
      """;

  /** Maps a {@code customer} row; optional text columns become empty strings. */
  /** Columns the customer search looks in (as the former in-browser filter). */
  private static final String[] SEARCHED_COLUMNS = {
    "first_name", "last_name", "street", "city", "postal_code", "phone", "email"
  };

  private static final RowMapper<Customer> CUSTOMER_MAPPER =
      (rs, rowNum) -> {
        Customer customer =
            new Customer(
                rs.getInt("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                nullToEmpty(rs.getString("postal_code")),
                nullToEmpty(rs.getString("city")),
                nullToEmpty(rs.getString("street")),
                rs.getInt("house_number"),
                nullToEmpty(rs.getString("phone")),
                nullToEmpty(rs.getString("email")),
                nullToEmpty(rs.getString("tax_id")),
                nullToEmpty(rs.getString("note")));
        customer.setBuyerReference(nullToEmpty(rs.getString("buyer_reference")));
        return customer;
      };

  /** Maps a {@code customer} row to a search hit; all text columns become empty strings. */
  private static final RowMapper<SearchCustomer> SEARCH_MAPPER =
      (rs, rowNum) ->
          new SearchCustomer(
              rs.getInt("id"),
              nullToEmpty(rs.getString("first_name")),
              nullToEmpty(rs.getString("last_name")),
              nullToEmpty(rs.getString("postal_code")),
              nullToEmpty(rs.getString("city")),
              nullToEmpty(rs.getString("street")),
              rs.getInt("house_number"),
              nullToEmpty(rs.getString("phone")),
              nullToEmpty(rs.getString("email")),
              nullToEmpty(rs.getString("tax_id")),
              nullToEmpty(rs.getString("note")));

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public CustomerRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Retrieves all customers from the database.
   *
   * @return a list of all customers, or an empty list if none exist
   */
  public List<Customer> findAll() {
    return run(
        "findAll customers",
        () -> jdbc.sql("SELECT * FROM customer").query(CUSTOMER_MAPPER).list());
  }

  /**
   * One page of the customer list, searched in SQL. The search matches like the former in-browser
   * filter of the customer list (first name, last name, street, city, postal code, phone, e-mail,
   * case-insensitive) plus "first last" together, so "Max Kunde" finds Max Kunde.
   *
   * @param search text to look for, or {@code null}/blank for all
   * @param byName {@code true}: last name, first name, id (the customer picker); {@code false}: id
   *     (the customer list, as before)
   * @param offset rows to skip
   * @param limit rows to return
   * @return the customers of the page
   */
  public List<Customer> findPage(String search, boolean byName, int offset, int limit) {
    String order =
        byName
            ? " ORDER BY LOWER(COALESCE(last_name, '')), LOWER(COALESCE(first_name, '')), id"
            : " ORDER BY id";
    return run(
        "findPage customers",
        () ->
            withSearch(
                    jdbc.sql(
                        "SELECT * FROM customer"
                            + searchCondition(search)
                            + order
                            + " LIMIT :limit OFFSET :offset"),
                    search)
                .param("limit", limit)
                .param("offset", offset)
                .query(CUSTOMER_MAPPER)
                .list());
  }

  /**
   * Number of customers matching the search, for the pager.
   *
   * @param search text to look for, or {@code null}/blank for all
   * @return the total across all pages
   */
  public long count(String search) {
    return run(
        "count customers",
        () ->
            withSearch(jdbc.sql("SELECT COUNT(*) FROM customer" + searchCondition(search)), search)
                .query(Long.class)
                .single());
  }

  private static String searchCondition(String search) {
    if (search == null || search.isBlank()) {
      return "";
    }
    StringBuilder where = new StringBuilder(" WHERE (");
    for (String column : SEARCHED_COLUMNS) {
      where.append("LOWER(COALESCE(").append(column).append(", '')) LIKE :search ESCAPE '!' OR ");
    }
    return where
        .append("LOWER(CONCAT(COALESCE(first_name, ''), ' ', COALESCE(last_name, '')))")
        .append(" LIKE :search ESCAPE '!')")
        .toString();
  }

  private static JdbcClient.StatementSpec withSearch(JdbcClient.StatementSpec spec, String search) {
    return search == null || search.isBlank()
        ? spec
        : spec.param("search", containsPattern(search.trim()));
  }

  /**
   * The customers with the given ids (the customers of one page of the document list).
   *
   * @param ids the customer ids
   * @return the customers found; unknown ids are absent
   */
  public List<Customer> findByIds(Collection<Integer> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return run(
        "findByIds customers",
        () ->
            jdbc.sql("SELECT * FROM customer WHERE id IN (:ids)")
                .param("ids", ids)
                .query(CUSTOMER_MAPPER)
                .list());
  }

  /**
   * Saves a new customer to the database. The customer's ID is generated by the database and set on
   * the passed object.
   *
   * @param customer the customer to save
   * @return the same customer, now carrying its ID
   */
  public Customer save(Customer customer) {
    return run(
        "save customer",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bind(
                  jdbc.sql(
                      """
                      INSERT INTO customer (first_name, last_name, postal_code, city, street,
                                            house_number, phone, email, tax_id, note,
                                            buyer_reference)
                      VALUES (:firstName, :lastName, :postalCode, :city, :street, :houseNumber,
                              :phone, :email, :taxId, :note, :buyerReference)
                      """),
                  customer)
              .update(keys);
          customer.setId(generatedId(keys));
          return customer;
        });
  }

  /**
   * Updates an existing customer in the database. The customer is identified by its ID and all
   * fields are updated.
   *
   * @param customer the customer with updated information
   */
  public void update(Customer customer) {
    run(
        "update customer",
        () ->
            bind(jdbc.sql("UPDATE customer SET " + COLUMNS + " WHERE id = :id"), customer)
                .param("id", customer.getId())
                .update());
  }

  /**
   * Deletes a customer from the database by ID.
   *
   * @param customerId the ID of the customer to delete
   */
  public void delete(int customerId) {
    run(
        "delete customer",
        () -> jdbc.sql("DELETE FROM customer WHERE id = :id").param("id", customerId).update());
  }

  /**
   * Finds a customer by their unique ID.
   *
   * @param id the ID of the customer to find
   * @return the customer if found, null otherwise
   */
  public Customer findById(int id) {
    return run(
        "findById customer",
        () ->
            jdbc.sql("SELECT * FROM customer WHERE id = :id")
                .param("id", id)
                .query(CUSTOMER_MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Full-text style search over the customer's id, names, contact and address fields. Each field is
   * matched case-insensitively against the raw query and against a variant with umlauts and ß
   * transliterated ({@code ä → a}, {@code ß → ss}).
   *
   * @param query the user's search text
   * @return up to 50 matches ordered by last name, first name
   */
  public List<SearchCustomer> search(String query) {
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
        SELECT * FROM customer
        WHERE CAST(id AS VARCHAR(50)) LIKE :pattern
           OR LOWER(first_name) LIKE LOWER(:pattern) OR LOWER(last_name) LIKE LOWER(:pattern)
           OR LOWER(email) LIKE LOWER(:pattern) OR LOWER(phone) LIKE LOWER(:pattern)
           OR LOWER(city) LIKE LOWER(:pattern) OR LOWER(postal_code) LIKE LOWER(:pattern)
           OR LOWER(street) LIKE LOWER(:pattern) OR LOWER(tax_id) LIKE LOWER(:normalized)
           OR LOWER(first_name) LIKE LOWER(:normalized) OR LOWER(last_name) LIKE LOWER(:normalized)
           OR LOWER(email) LIKE LOWER(:normalized) OR LOWER(phone) LIKE LOWER(:normalized)
           OR LOWER(city) LIKE LOWER(:normalized) OR LOWER(postal_code) LIKE LOWER(:normalized)
           OR LOWER(street) LIKE LOWER(:normalized) OR LOWER(tax_id) LIKE LOWER(:normalized)
        ORDER BY last_name, first_name
        LIMIT 50
        """;
    return run(
        "search customers",
        () ->
            jdbc.sql(sql)
                .param("pattern", "%" + query + "%")
                .param("normalized", "%" + normalizedQuery + "%")
                .query(SEARCH_MAPPER)
                .list());
  }

  /** Binds the customer's column values (empty strings stored as NULL). */
  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Customer customer) {
    return spec.param("firstName", emptyToNull(customer.getFirstName()))
        .param("lastName", emptyToNull(customer.getLastName()))
        .param("postalCode", emptyToNull(customer.getPostalCode()))
        .param("city", emptyToNull(customer.getCity()))
        .param("street", emptyToNull(customer.getStreet()))
        .param("houseNumber", customer.getHouseNumber())
        .param("phone", emptyToNull(customer.getPhone()))
        .param("email", emptyToNull(customer.getEmail()))
        .param("taxId", emptyToNull(customer.getTaxId()))
        .param("note", emptyToNull(customer.getNote()))
        .param("buyerReference", emptyToNull(customer.getBuyerReference()));
  }
}
