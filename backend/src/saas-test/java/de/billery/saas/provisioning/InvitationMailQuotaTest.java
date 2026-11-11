package de.billery.saas.provisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.config.DbConfig;
import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import de.billery.saas.SaasTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The limits of invitation mails on a real PostgreSQL, as the application's database role. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class InvitationMailQuotaTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");
  private static final int LARGE = 100_000;

  private static JdbcClient app;
  private static TransactionTemplate transactions;

  @BeforeAll
  static void prepare() throws SQLException {
    Database.setDbPath(ADMIN_URL);
    execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    DataSource source = SaasTestSupport.appRoleDataSource(ADMIN_URL);
    app = JdbcClient.create(source);
    transactions = new TransactionTemplate(DbConfig.newTransactionManager(source));
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void empty() throws SQLException {
    execute("DELETE FROM daily_quota");
    meters = new SimpleMeterRegistry();
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String statement : statements) {
        stmt.execute(statement);
      }
    }
  }

  private static SimpleMeterRegistry meters;

  private static InvitationMailQuota quota(int systemLimit) {
    return quota(systemLimit, 0);
  }

  private static InvitationMailQuota quota(int systemLimit, int perSource) {
    return new InvitationMailQuota(
        new DailyQuota(app, transactions, meters), systemLimit, perSource);
  }

  /** A source address of its own, so the limit per IP does not interfere. */
  private static String ip() {
    return "198.51.100." + (int) (Math.random() * 250) + "-" + UUID.randomUUID();
  }

  private static String address() {
    return UUID.randomUUID() + "@example.com";
  }

  private static void expireAll() throws SQLException {
    execute("UPDATE daily_quota SET window_start = CURRENT_TIMESTAMP - INTERVAL '25 hours'");
  }

  private static int systemCount() {
    return app.sql(
            "SELECT COALESCE(SUM(used), 0) FROM daily_quota"
                + " WHERE quota = 'invitation_mail' AND scope = 'system'")
        .query(Integer.class)
        .single();
  }

  @Test
  void theUserLimitHoldsAcrossInstancesAndStartsOverAfterADay() throws SQLException {
    UUID user = UUID.randomUUID();
    InvitationMailQuota first = quota(LARGE);
    InvitationMailQuota second = quota(LARGE);

    for (int i = 0; i < InvitationMailQuota.MAX_PER_USER; i++) {
      assertTrue((i % 2 == 0 ? first : second).claim(user, address(), ip()), "mail " + i);
    }
    assertFalse(first.claim(user, address(), ip()));
    assertFalse(second.claim(user, address(), ip()), "another instance sees the same count");
    assertTrue(first.claim(UUID.randomUUID(), address(), ip()), "another user has their own limit");

    expireAll();
    assertTrue(first.claim(user, address(), ip()), "a new day starts over");
    assertEquals(1, systemCount(), "expired rows are gone");
  }

  @Test
  void manyAccountsCannotFloodOneRecipient() {
    InvitationMailQuota quota = quota(LARGE);
    String victim = address();

    for (int i = 0; i < InvitationMailQuota.MAX_PER_RECIPIENT; i++) {
      assertTrue(quota.claim(UUID.randomUUID(), victim, ip()), "mail " + i);
    }
    assertFalse(quota.claim(UUID.randomUUID(), victim, ip()));
    assertTrue(
        quota.claim(UUID.randomUUID(), address(), ip()), "other recipients are not affected");
  }

  @Test
  void theSystemLimitStopsManyAccounts() {
    InvitationMailQuota quota = quota(3);

    for (int i = 0; i < 3; i++) {
      assertTrue(quota.claim(UUID.randomUUID(), address(), ip()), "mail " + i);
    }
    assertFalse(quota.claim(UUID.randomUUID(), address(), ip()));
  }

  @Test
  void aRefusedAttemptCountsNothing() {
    InvitationMailQuota quota = quota(LARGE);
    UUID abuser = UUID.randomUUID();
    for (int i = 0; i < InvitationMailQuota.MAX_PER_USER; i++) {
      assertTrue(quota.claim(abuser, address(), ip()));
    }
    String untouched = address();

    for (int i = 0; i < 20; i++) {
      assertFalse(quota.claim(abuser, untouched, ip()));
    }

    assertEquals(InvitationMailQuota.MAX_PER_USER, systemCount());
    for (int i = 0; i < InvitationMailQuota.MAX_PER_RECIPIENT; i++) {
      assertTrue(
          quota.claim(UUID.randomUUID(), untouched, ip()), "the recipient's count is unused");
    }
  }

  @Test
  void addressesAreNotStored() {
    String address = address();
    assertTrue(quota(LARGE, LARGE).claim(UUID.randomUUID(), address, ip()), "IP limit on");

    assertEquals(
        0,
        app.sql(
                "SELECT COUNT(*) FROM daily_quota WHERE quota_key LIKE '%@%'"
                    + " OR quota_key LIKE '%198.51.100%'")
            .query(Integer.class)
            .single());
  }

  @Test
  void parallelRequestsCannotExceedTheLimit() throws Exception {
    UUID user = UUID.randomUUID();
    InvitationMailQuota quota = quota(LARGE);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < InvitationMailQuota.MAX_PER_USER + 20; i++) {
        calls.add(pool.submit(() -> quota.claim(user, address(), ip())));
      }
      int allowed = 0;
      for (Future<Boolean> call : calls) {
        allowed += call.get() ? 1 : 0;
      }
      assertEquals(InvitationMailQuota.MAX_PER_USER, allowed);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void oneSourceWithManyAccountsIsStoppedByTheLimitPerIp() {
    InvitationMailQuota quota = quota(LARGE, 3);
    String office = "203.0.113.9";

    for (int i = 0; i < 3; i++) {
      assertTrue(quota.claim(UUID.randomUUID(), address(), office), "mail " + i);
    }
    assertFalse(quota.claim(UUID.randomUUID(), address(), office), "a fresh account, same source");
    assertTrue(quota.claim(UUID.randomUUID(), address(), "203.0.113.10"), "another source");
    assertTrue(quota(LARGE, 0).claim(UUID.randomUUID(), address(), office), "0 switches it off");
  }

  @Test
  void everyRefusalIsCountedForTheMonitoring() {
    InvitationMailQuota quota = quota(1);
    assertTrue(quota.claim(UUID.randomUUID(), address(), ip()));

    assertFalse(quota.claim(UUID.randomUUID(), address(), ip()));
    assertFalse(quota.claim(UUID.randomUUID(), address(), ip()));

    assertEquals(
        2.0,
        meters
            .get("billery.quota.refused")
            .tag("quota", "invitation_mail")
            .tag("scope", "system")
            .counter()
            .count());
  }

  @Test
  void aCountInsideAFailedTransactionIsRolledBack() {
    InvitationMailQuota quota = quota(LARGE);
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.executeWithoutResult(
                status -> {
                  assertTrue(quota.claim(UUID.randomUUID(), address(), ip()));
                  throw new IllegalStateException("the work after the claim fails");
                }));

    assertEquals(0, systemCount(), "the caller's failure takes the count with it");
  }

  @Test
  void aSystemLimitBelowOneStopsTheStart() {
    assertThrows(IllegalStateException.class, () -> quota(0));
    assertThrows(IllegalStateException.class, () -> quota(LARGE, -1));
  }
}
