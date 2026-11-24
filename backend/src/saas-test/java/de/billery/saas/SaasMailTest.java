package de.billery.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Mail to customers in SaaS, over HTTP: a company sends through its own account, the daily limit
 * the operator set for it holds (and the application cannot raise it), the password is never
 * returned, and the dispatch is in the document's history.
 */
class SaasMailTest extends SaasApiTestBase {

  private static String owner;
  private static int tenant;

  private void ensureTenant() throws Exception {
    if (owner != null) {
      return;
    }
    owner = token(idpA, "mail-owner", "mail-owner@example.com");
    tenant = signUp(owner, "Mail GmbH");
    assertEquals(
        2,
        post(
                    "/api/owners",
                    owner,
                    tenant,
                    "{\"firstName\":\"O\",\"lastName\":\"W\",\"companyName\":\"Mail GmbH\","
                        + "\"companyType\":\"GmbH\",\"postalCode\":\"12345\",\"city\":\"Berlin\","
                        + "\"street\":\"Weg\",\"houseNumber\":1,\"email\":\"o@x.de\","
                        + "\"taxId\":\"DE123456789\",\"iban\":\"DE02120300000000202051\","
                        + "\"isSmallBusiness\":false}")
                .status()
            / 100);
  }

  private int issuedInvoice() throws Exception {
    int customer =
        post(
                "/api/customers",
                owner,
                tenant,
                "{\"firstName\":\"Kunde\",\"lastName\":\"K\",\"postalCode\":\"1\",\"city\":\"x\","
                    + "\"street\":\"x\",\"houseNumber\":1,\"email\":\"kunde@example.com\"}")
            .json()
            .get("id")
            .asInt();
    int id =
        post(
                "/api/invoices",
                owner,
                tenant,
                "{\"customerId\":"
                    + customer
                    + ",\"invoiceDate\":\"2026-03-01\",\"dueDate\":\"2026-03-31\","
                    + "\"workStartDate\":\"2026-03-01\",\"documentTypeId\":1,\"items\":[{"
                    + "\"itemType\":\"ARTICLE\",\"itemId\":0,\"itemName\":\"Pos\",\"unit\":\"Stk\","
                    + "\"quantity\":1,\"unitPrice\":10,\"taxRate\":19}]}")
            .json()
            .get("id")
            .asInt();
    assertEquals(
        200,
        call("PATCH", "/api/invoices/" + id + "/status", owner, tenant, "{\"documentStatusId\":2}")
            .status());
    return id;
  }

  private int account(String password) throws Exception {
    Reply created =
        post(
            "/api/mail/accounts",
            owner,
            tenant,
            "{\"name\":\"Konto\",\"host\":\"127.0.0.1\",\"port\":"
                + smtp.port()
                + ",\"security\":\"NONE\",\"fromAddress\":\"rechnung@example.com\""
                + (password == null ? "" : ",\"password\":\"" + password + "\"")
                + "}");
    assertEquals(201, created.status(), created.body());
    return created.json().get("id").asInt();
  }

  private Reply send(int invoice, int account) throws Exception {
    return post(
        "/api/invoices/" + invoice + "/mail",
        owner,
        tenant,
        "{\"accountId\":"
            + account
            + ",\"to\":[\"kunde@example.com\"],\"subject\":\"Rechnung {{rechnungsnummer}}\","
            + "\"text\":\"Anbei\"}");
  }

  @Test
  void theOperatorsDailyLimitHoldsAndTheApplicationCannotRaiseIt() throws Exception {
    ensureTenant();
    int account = account(null);
    int invoice = issuedInvoice();
    admin("UPDATE tenant SET mail_daily_limit = 1 WHERE id = " + tenant);
    try {
      Reply first = send(invoice, account);
      assertEquals(200, first.status(), first.body());
      Reply second = send(invoice, account);
      assertEquals(429, second.status(), second.body());
      assertTrue(second.body().contains("Tageslimit"), second.body());

      // The application role may not change the limit (only the lifecycle columns of its row).
      try (Connection conn = SaasTestSupport.appRoleDataSource(ADMIN_URL).getConnection();
          Statement stmt = conn.createStatement()) {
        SQLException e =
            assertThrows(
                SQLException.class,
                () -> stmt.execute("UPDATE tenant SET mail_daily_limit = 100000"));
        assertTrue(e.getMessage().contains("permission denied"), e.getMessage());
      }

      admin("UPDATE tenant SET mail_daily_limit = 5 WHERE id = " + tenant);
      assertEquals(200, send(invoice, account).status());
    } finally {
      admin("UPDATE tenant SET mail_daily_limit = NULL WHERE id = " + tenant);
    }
  }

  @Test
  void thePasswordIsEncryptedAndNeverReturned() throws Exception {
    ensureTenant();
    // NONE with a password is refused; a password needs encryption.
    Reply refused =
        post(
            "/api/mail/accounts",
            owner,
            tenant,
            "{\"name\":\"K\",\"host\":\"127.0.0.1\",\"port\":"
                + smtp.port()
                + ",\"security\":\"NONE\",\"username\":\"u\",\"password\":\"Geheim\","
                + "\"fromAddress\":\"r@example.com\"}");
    assertEquals(400, refused.status(), refused.body());

    Reply created =
        post(
            "/api/mail/accounts",
            owner,
            tenant,
            "{\"name\":\"Mit Passwort\",\"host\":\"smtp.example.com\",\"port\":587,"
                + "\"security\":\"STARTTLS\",\"username\":\"u\",\"password\":\"Geheim-987\","
                + "\"fromAddress\":\"r@example.com\"}");
    assertEquals(201, created.status(), created.body());
    assertTrue(created.json().get("hasPassword").asBoolean());
    Reply list = get("/api/mail/accounts", owner, tenant);
    assertFalse(list.body().contains("Geheim-987"), list.body());
    assertFalse(list.body().contains("password\""), list.body());
    assertEquals(
        "0",
        count(
            "SELECT COUNT(*) FROM mail_account WHERE password_encrypted LIKE '%Geheim%'"
                + " OR password_encrypted NOT LIKE 'v1:%' AND password_encrypted IS NOT NULL"));
  }

  @Test
  void theDispatchIsInTheHistory() throws Exception {
    ensureTenant();
    admin("UPDATE tenant SET mail_daily_limit = NULL WHERE id = " + tenant);
    int account = account(null);
    int invoice = issuedInvoice();
    int before = smtp.received().size();
    Reply sent = send(invoice, account);
    assertEquals(200, sent.status(), sent.body());
    assertEquals(before + 1, smtp.received().size());
    Reply history = get("/api/invoices/" + invoice + "/history", owner, tenant);
    assertTrue(history.body().contains("MAIL_SENT"), history.body());
    assertTrue(history.body().contains("kunde@example.com"), history.body());
  }

  private static String count(String sql) throws SQLException {
    try (Connection conn = java.sql.DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
