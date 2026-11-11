package de.billery.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who did what: the administration log of a tenant names the acting user for every team action, the
 * document history names its user, and the application can change neither log.
 */
class TenantAuditTest extends SaasApiTestBase {

  /** "ACTION | actor | details" of the log, oldest first. */
  private List<String> log(String token, int tenant) throws Exception {
    Reply reply = get("/api/tenant/audit", token, tenant);
    assertEquals(200, reply.status(), reply.body());
    List<String> lines = new ArrayList<>();
    for (JsonNode entry : reply.json()) {
      lines.add(
          0,
          entry.get("action").asText()
              + " | "
              + entry.get("actor").asText()
              + " | "
              + entry.get("details").asText());
    }
    return lines;
  }

  @Test
  void everyTeamActionIsLoggedWithTheActingUser() throws Exception {
    String tina = token(idpA, "tina", "tina@example.com");
    int tenant = signUp(tina, "Tina Audit GmbH");
    assertEquals(
        201,
        post(
                "/api/tenant/invitations",
                tina,
                tenant,
                "{\"email\":\"tom@example.com\",\"role\":\"READER\"}")
            .status());
    Reply carlInvite =
        post(
            "/api/tenant/invitations",
            tina,
            tenant,
            "{\"email\":\"carl@example.com\",\"role\":\"READER\"}");
    assertEquals(
        200,
        call(
                "DELETE",
                "/api/tenant/invitations/" + carlInvite.json().get("id").asText(),
                tina,
                tenant,
                null)
            .status());
    String tom = token(idpA, "tom", "tom@example.com");
    String invitation =
        get("/api/me", tom, null).json().get("invitations").get(0).get("id").asText();
    assertEquals(200, post("/api/invitations/" + invitation + "/accept", tom, null, null).status());
    String tomId = userId(tom);
    assertEquals(
        200,
        call("PUT", "/api/tenant/members/" + tomId, tina, tenant, "{\"role\":\"ACCOUNTANT\"}")
            .status());
    assertEquals(200, call("DELETE", "/api/tenant/members/" + tomId, tina, tenant, null).status());
    assertEquals(200, post("/api/tenant/close", tina, tenant, null).status());

    assertEquals(
        List.of(
            "TENANT_CREATED | Test tina | Tina Audit GmbH",
            "MEMBER_INVITED | Test tina | tom@example.com (READER)",
            "MEMBER_INVITED | Test tina | carl@example.com (READER)",
            "INVITATION_REVOKED | Test tina | carl@example.com (READER)",
            "INVITATION_ACCEPTED | Test tom | tom@example.com (READER)",
            "ROLE_CHANGED | Test tina | tom@example.com: READER → ACCOUNTANT",
            "MEMBER_REMOVED | Test tina | tom@example.com (ACCOUNTANT)",
            "TENANT_CLOSED | Test tina | null"),
        log(tina, tenant),
        "readable after closing too: the log is part of what an owner exports");
  }

  @Test
  void aMemberWhoLeavesIsLoggedAsLeavingAndAReaderCannotReadTheLog() throws Exception {
    String una = token(idpA, "una", "una@example.com");
    int tenant = signUp(una, "Una Audit GmbH");
    String ulf = token(idpA, "ulf", "ulf@example.com");
    String ulfId = userId(ulf);
    member(ulfId, tenant, "READER");

    assertEquals(403, get("/api/tenant/audit", ulf, tenant).status(), "a reader does not see it");
    assertEquals(200, call("DELETE", "/api/tenant/members/" + ulfId, ulf, tenant, null).status());

    List<String> lines = log(una, tenant);
    assertEquals("MEMBER_LEFT | Test ulf | ulf@example.com (READER)", lines.get(lines.size() - 1));
  }

  @Test
  void theDocumentHistoryNamesTheUserWhoActed() throws Exception {
    String vera = token(idpA, "vera", "vera@example.com");
    int tenant = signUp(vera, "Vera Audit GmbH");
    Reply owner =
        post(
            "/api/owners",
            vera,
            tenant,
            "{\"firstName\":\"V\",\"lastName\":\"A\",\"companyName\":\"Vera Audit GmbH\","
                + "\"companyType\":\"GmbH\",\"postalCode\":\"12345\",\"city\":\"Berlin\","
                + "\"street\":\"Weg\",\"houseNumber\":1,\"phone\":\"1\",\"email\":\"v@x.de\","
                + "\"taxId\":\"DE123456789\",\"iban\":\"DE02120300000000202051\","
                + "\"isSmallBusiness\":false}");
    assertEquals(2, owner.status() / 100, owner.body());
    int customer =
        post(
                "/api/customers",
                vera,
                tenant,
                "{\"firstName\":\"Kai\",\"lastName\":\"Kunde\",\"postalCode\":\"1\",\"city\":\"O\","
                    + "\"street\":\"S\",\"houseNumber\":1}")
            .json()
            .get("id")
            .asInt();
    int draft =
        post(
                "/api/invoices",
                vera,
                tenant,
                "{\"customerId\":"
                    + customer
                    + ",\"invoiceDate\":\"2026-03-01\",\"dueDate\":\"2026-03-15\","
                    + "\"workStartDate\":\"2026-03-01\",\"taxRate\":19,\"documentTypeId\":2,"
                    + "\"items\":[{\"itemType\":\"service\",\"itemName\":\"Beratung\",\"unit\":\"Std\","
                    + "\"quantity\":1,\"unitPrice\":100,\"taxRatePercent\":19}]}")
            .json()
            .get("id")
            .asInt();

    JsonNode history = get("/api/invoices/" + draft + "/history", vera, tenant).json();
    assertTrue(history.size() >= 1, history.toString());
    assertEquals("CREATED", history.get(0).get("action").asText());
    assertEquals("Test vera", history.get(0).get("performedByName").asText(), history.toString());
  }

  @Test
  void theApplicationCanNeitherChangeNorDeleteTheLogs() throws SQLException {
    admin("INSERT INTO tenant_audit (tenant_id, action) VALUES (1, 'TENANT_CREATED')");
    try (Connection conn = SaasTestSupport.appRoleDataSource(ADMIN_URL).getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT set_config('app.tenant_id', '1', false)");
      assertThrows(SQLException.class, () -> stmt.execute("UPDATE tenant_audit SET details = 'x'"));
      assertThrows(SQLException.class, () -> stmt.execute("DELETE FROM tenant_audit"));
      assertThrows(
          SQLException.class, () -> stmt.execute("UPDATE document_history SET notes = 'x'"));
      stmt.execute("INSERT INTO tenant_audit (action, details) VALUES ('TENANT_CLOSED', 'test')");
    }
  }
}
