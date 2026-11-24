package de.billery.repository;

import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.data.MailAccount;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The companies' own SMTP accounts ({@code mail_account}). In SaaS row-level security limits every
 * statement to the active company. At most one account is the default: {@link #makeDefault} clears
 * the others, and the callers run it in the same transaction as the write it belongs to.
 */
@Repository
public class MailAccountRepository extends BaseRepository {

  private static final RowMapper<MailAccount> MAPPER =
      (rs, rowNum) ->
          new MailAccount(
              rs.getInt("id"),
              nullToEmpty(rs.getString("name")),
              nullToEmpty(rs.getString("host")),
              rs.getInt("port"),
              nullToEmpty(rs.getString("security")),
              nullToEmpty(rs.getString("username")),
              rs.getString("password_encrypted"),
              nullToEmpty(rs.getString("from_address")),
              nullToEmpty(rs.getString("from_name")),
              rs.getInt("is_default") == 1);

  private static final String COLUMNS =
      "id, name, host, port, security, username, password_encrypted, from_address, from_name,"
          + " is_default";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public MailAccountRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * @return the company's accounts, the default first, then by name
   */
  public List<MailAccount> findAll() {
    return run(
        "findAll mail accounts",
        () ->
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM mail_account ORDER BY is_default DESC, LOWER(name), id")
                .query(MAPPER)
                .list());
  }

  /**
   * @param id the account
   * @return the account, or null if the company has none with this id
   */
  public MailAccount findById(int id) {
    return run(
        "findById mail account",
        () ->
            jdbc.sql("SELECT " + COLUMNS + " FROM mail_account WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional()
                .orElse(null));
  }

  /**
   * Stores a new account.
   *
   * @param account the account (its id is ignored)
   * @return the account with its id
   */
  public MailAccount insert(MailAccount account) {
    return run(
        "insert mail account",
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          jdbc.sql(
                  """
                  INSERT INTO mail_account (name, host, port, security, username,
                      password_encrypted, from_address, from_name, is_default)
                  VALUES (:name, :host, :port, :security, :username, :password, :fromAddress,
                      :fromName, 0)
                  """)
              .param("name", account.name())
              .param("host", account.host())
              .param("port", account.port())
              .param("security", account.security())
              .param("username", account.username())
              .param("password", account.passwordEncrypted())
              .param("fromAddress", account.fromAddress())
              .param("fromName", account.fromName())
              .update(keys);
          return account.withId(generatedId(keys));
        });
  }

  /**
   * Changes an account (all columns but the default flag).
   *
   * @param account the account with its id
   * @return true if it existed
   */
  public boolean update(MailAccount account) {
    return run(
        "update mail account",
        () ->
            jdbc.sql(
                        """
                        UPDATE mail_account SET name = :name, host = :host, port = :port,
                            security = :security, username = :username,
                            password_encrypted = :password, from_address = :fromAddress,
                            from_name = :fromName
                        WHERE id = :id
                        """)
                    .param("id", account.id())
                    .param("name", account.name())
                    .param("host", account.host())
                    .param("port", account.port())
                    .param("security", account.security())
                    .param("username", account.username())
                    .param("password", account.passwordEncrypted())
                    .param("fromAddress", account.fromAddress())
                    .param("fromName", account.fromName())
                    .update()
                > 0);
  }

  /**
   * Makes one account the default and every other one not.
   *
   * @param id the account
   * @return true if it existed
   */
  public boolean makeDefault(int id) {
    return run(
        "makeDefault mail account",
        () -> {
          if (findById(id) == null) {
            return false;
          }
          jdbc.sql("UPDATE mail_account SET is_default = 0 WHERE is_default = 1 AND id <> :id")
              .param("id", id)
              .update();
          jdbc.sql("UPDATE mail_account SET is_default = 1 WHERE id = :id")
              .param("id", id)
              .update();
          return true;
        });
  }

  /**
   * @param id the account
   * @return true if it existed
   */
  public boolean delete(int id) {
    return run(
        "delete mail account",
        () -> jdbc.sql("DELETE FROM mail_account WHERE id = :id").param("id", id).update() > 0);
  }
}
