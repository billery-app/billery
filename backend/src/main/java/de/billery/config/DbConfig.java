package de.billery.config;

import com.zaxxer.hikari.HikariDataSource;
import de.billery.data.Database;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pooled {@link javax.sql.DataSource} and the {@link JdbcClient} built on it.
 *
 * <p>The connection settings are resolved exactly like {@link Database} resolves them ({@code
 * DB_URL} when set — e.g. the desktop H2 file URL — otherwise the {@code DB_HOST}/{@code
 * DB_PORT}/{@code DB_NAME} quartet with {@code DB_USER}/{@code DB_PASSWORD}), so the pooled
 * repositories and the pre-Spring bootstrap ({@code MainApp.prepareDatabase()}) always talk to the
 * same database. When the URL carries its own credentials no user is set and the driver defaults
 * apply, matching {@link Database#getConnection()}.
 *
 * <p>The beans below depend on the {@link DataSource} interface, not on the pool class: the SaaS
 * profile wraps the pool so that every borrowed connection carries the active tenant (see {@code
 * docs/architecture/multi-tenancy.md}).
 *
 * <p>The single {@code DataSource} bean also backs the transaction manager. Services open
 * transactions through the injected {@link TransactionTemplate}; every {@link JdbcClient} call made
 * inside one joins it.
 */
@Configuration
public class DbConfig {

  /**
   * Pooled DataSource (HikariCP). Replaces the per-call {@code DriverManager} connections of the
   * bootstrap path for everything that runs inside Spring: it is handed to {@link
   * Database#useDataSource} so {@code Database.getConnection()} callers and the {@link JdbcClient}
   * share the pool and, inside a transaction, the connection.
   *
   * @return the application DataSource
   */
  @Bean
  public HikariDataSource dataSource() {
    Database.validateConfiguration();
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(Database.getUrl());
    String user = Database.getUser();
    if (user != null) {
      dataSource.setUsername(user);
      dataSource.setPassword(Database.getPassword());
    }
    Database.useDataSource(dataSource);
    return dataSource;
  }

  /**
   * The JdbcClient the repositories use. Built on the shared DataSource so repository calls take
   * part in Spring-managed transactions.
   *
   * @param dataSource the application DataSource
   * @return the application JdbcClient
   */
  @Bean
  public JdbcClient jdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }

  /**
   * The transaction manager on the application DataSource.
   *
   * @param dataSource the application DataSource
   * @return the transaction manager
   */
  @Bean
  public DataSourceTransactionManager transactionManager(DataSource dataSource) {
    return newTransactionManager(dataSource);
  }

  /**
   * The template services use to run a unit of work in one transaction ({@code
   * transactions.execute(status -> ...)}): commit when it returns, roll back when it throws, join
   * an already open transaction.
   *
   * @param transactionManager the transaction manager
   * @return the transaction template
   */
  @Bean
  public TransactionTemplate transactionTemplate(DataSourceTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  /**
   * Builds the transaction manager the application uses (also used by the tests, which run without
   * a Spring context). A nested unit of work that throws does not mark the outer transaction
   * rollback-only: if the caller handles the exception, the outer transaction still commits.
   *
   * @param dataSource the DataSource
   * @return a configured transaction manager
   */
  public static DataSourceTransactionManager newTransactionManager(DataSource dataSource) {
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    manager.setGlobalRollbackOnParticipationFailure(false);
    return manager;
  }
}
