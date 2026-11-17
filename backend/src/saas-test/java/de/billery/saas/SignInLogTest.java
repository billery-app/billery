package de.billery.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Sign-ins for the operator ({@code sign_in_log}): one row per provider session, user and company,
 * with the source address and a hash instead of anything from the token; the application can write
 * but not read, change or delete them; rows past 90 days go.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class SignInLogTest extends SaasApiTestBase {

  /** A token of {@code subject} at idpA with further claims. */
  private static String token(String subject, Map<String, Object> claims) {
    return idpA.token(
        subject, subject + "@example.com", true, AUDIENCE, Duration.ofMinutes(10), claims);
  }

  /** "tenant | ip | hash length" of the user's rows, read as the operator (admin), oldest first. */
  private static List<String> rows(String userId) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        PreparedStatement stmt =
            admin.prepareStatement(
                "SELECT tenant_id, source_ip, length(session_hash) FROM sign_in_log"
                    + " WHERE user_id = ?::uuid ORDER BY id")) {
      stmt.setString(1, userId);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          rows.add(rs.getInt(1) + " | " + rs.getString(2) + " | " + rs.getInt(3));
        }
      }
    }
    return rows;
  }

  private void touch(String token, int tenant) throws Exception {
    assertEquals(200, get("/api/customers/page", token, tenant).status());
  }

  @Test
  void aProviderSessionIsRecordedOncePerCompanyWithItsSourceAddress() throws Exception {
    long signedInAt = java.time.Instant.now().getEpochSecond() - 60;
    String first = token("sina", Map.of("auth_time", signedInAt));
    int company = signUp(first, "Sina GmbH");
    String user = userId(first);

    touch(first, company);
    touch(first, company);
    // a renewed token of the same session at the provider
    touch(token("sina", Map.of("auth_time", signedInAt)), company);
    assertEquals(List.of(company + " | 127.0.0.1 | 64"), rows(user));

    // signed in again at the provider: a new session
    touch(token("sina", Map.of("auth_time", signedInAt + 3600)), company);
    assertEquals(2, rows(user).size());

    // the same session reaching a second company of the user
    int second = signUp(first, "Sina Zwei GmbH");
    touch(token("sina", Map.of("auth_time", signedInAt + 3600)), second);
    assertEquals(
        List.of(company, company, second),
        rows(user).stream().map(row -> Integer.parseInt(row.split(" ")[0])).toList());
  }

  @Test
  void theSessionIdServesWhenTheTokenHasNoAuthTime() throws Exception {
    String token = token("sid-user", Map.of("sid", "session-1"));
    int company = signUp(token, "Sid GmbH");
    String user = userId(token);

    touch(token, company);
    touch(token("sid-user", Map.of("sid", "session-1")), company);
    assertEquals(1, rows(user).size());

    touch(token("sid-user", Map.of("sid", "session-2")), company);
    assertEquals(2, rows(user).size());
  }

  @Test
  void withoutAuthTimeOrSidTheFirstRequestCountsAndTheTokenIsNeverStored() throws Exception {
    String token = token("plain-user", Map.of());
    int company = signUp(token, "Plain GmbH");
    String user = userId(token);

    touch(token, company);
    touch(token, company);
    assertEquals(1, rows(user).size(), "requests within the idle gap continue the session");

    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = admin.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM sign_in_log")) {
      while (rs.next()) {
        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
          String value = rs.getString(column);
          assertFalse(value != null && value.contains("eyJ"), "a token was stored: " + value);
        }
      }
    }
  }

  @Test
  void aSignedInUserWithoutACompanyIsNotRecorded() throws Exception {
    String token = token("lonely", Map.of("auth_time", 1_700_000_000L));
    String user = userId(token); // GET /api/me needs no company

    assertEquals(List.of(), rows(user));
  }

  @Test
  void theApplicationMayWriteButNotReadChangeOrDelete() throws Exception {
    var app = SaasTestSupport.appRoleDataSource(ADMIN_URL);
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT set_config('app.tenant_id', '1', false)");
      for (String sql :
          List.of(
              "SELECT count(*) FROM sign_in_log",
              "UPDATE sign_in_log SET source_ip = NULL",
              "DELETE FROM sign_in_log",
              "TRUNCATE sign_in_log")) {
        SQLException refused = assertThrows(SQLException.class, () -> stmt.execute(sql), sql);
        assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
      }
    }
  }

  @Test
  void rowsPastNinetyDaysAreRemovedAndTheApplicationCannotChooseThePeriod() throws Exception {
    String token = token("old-user", Map.of("auth_time", 1_700_000_000L));
    int company = signUp(token, "Alt GmbH");
    String user = userId(token);
    admin(
        "INSERT INTO sign_in_log (tenant_id, user_id, session_hash, occurred_at) VALUES ("
            + company
            + ", '"
            + user
            + "', repeat('o', 64), CURRENT_TIMESTAMP - INTERVAL '91 days')",
        "INSERT INTO sign_in_log (tenant_id, user_id, session_hash, occurred_at) VALUES ("
            + company
            + ", '"
            + user
            + "', repeat('n', 64), CURRENT_TIMESTAMP - INTERVAL '89 days')");

    var app = SaasTestSupport.appRoleDataSource(ADMIN_URL);
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT prune_sign_in_log()")) {
      rs.next();
      assertEquals(1, rs.getInt(1), "only the row past 90 days");
    }
    assertEquals(1, rows(user).size(), "the row of 89 days stays");
    try (Connection conn = app.getConnection();
        Statement stmt = conn.createStatement()) {
      assertThrows(
          SQLException.class,
          () -> stmt.execute("SELECT prune_sign_in_log(0)"),
          "no period can be passed in");
    }
  }

  @Test
  void anAccountWithSignInsIsNotDeletedBeforeTheyExpire() throws Exception {
    java.util.UUID user = java.util.UUID.randomUUID();
    try (Connection admin = DriverManager.getConnection(ADMIN_URL)) {
      // an account that nothing but a sign-in refers to (no membership, no log entry)
      try (PreparedStatement insert =
          admin.prepareStatement("INSERT INTO app_user (id, email) VALUES (?, ?)")) {
        insert.setObject(1, user);
        insert.setString(2, user + "@example.com");
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          admin.prepareStatement(
              "INSERT INTO sign_in_log (tenant_id, user_id, session_hash)"
                  + " VALUES (1, ?, repeat('d', 64))")) {
        insert.setObject(1, user);
        insert.executeUpdate();
      }
      try (PreparedStatement delete = admin.prepareStatement("DELETE FROM app_user WHERE id = ?")) {
        delete.setObject(1, user);
        SQLException refused =
            assertThrows(SQLException.class, delete::executeUpdate, "the evidence outlives it");
        assertTrue(refused.getMessage().contains("sign_in_log"), refused.getMessage());
      }
    }
    assertEquals(1, rows(user.toString()).size());
  }

  @org.springframework.beans.factory.annotation.Autowired
  private de.billery.saas.identity.SignInLog signInLog;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.scheduling.config.ScheduledTaskHolder scheduler;

  @Test
  void theRetentionRunsHourlyOnItsOwnWithoutACompany() throws Exception {
    assertTrue(
        scheduler.getScheduledTasks().stream()
            .anyMatch(task -> task.toString().contains("SignInLog.prune")),
        "prune is scheduled: " + scheduler.getScheduledTasks());

    String token = token("expired-user", Map.of("auth_time", 1_700_000_456L));
    int company = signUp(token, "Verfall GmbH");
    String user = userId(token);
    try (Connection admin = DriverManager.getConnection(ADMIN_URL);
        PreparedStatement insert =
            admin.prepareStatement(
                "INSERT INTO sign_in_log (tenant_id, user_id, session_hash, occurred_at)"
                    + " VALUES (?, ?::uuid, repeat('x', 64), CURRENT_TIMESTAMP - INTERVAL '95 days')")) {
      insert.setInt(1, company);
      insert.setString(2, user);
      insert.executeUpdate();
    }
    assertEquals(1, rows(user).size());

    // as the scheduler calls it: on a thread of its own, without a company or a user
    Thread task = new Thread(signInLog::prune);
    task.start();
    task.join();

    assertEquals(List.of(), rows(user), "the record past 90 days is gone");
  }
}
