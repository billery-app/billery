package de.billery.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Roles inside one tenant, for every endpoint of the API (threat model T6, G3).
 *
 * <p>{@link SaasIsolationTest} proves that a user cannot reach another tenant's data. This test
 * covers the other axis: what the four roles of <b>one</b> tenant may do. One tenant has an owner,
 * an admin, an accountant and a reader; every endpoint is called with each of them and the answer
 * must match the table below:
 *
 * <ul>
 *   <li>a role the table <b>allows</b> must not be refused (not 401, not 403; the request itself
 *       may fail for other reasons, the placeholders are not real ids);
 *   <li>a role the table <b>denies</b> must get exactly 403.
 * </ul>
 *
 * Every denied cell sits in a row where at least one role is allowed, so the same request is known
 * to get past the role check for someone. A guard test fails for any {@code /api} endpoint that is
 * not in the table: a new endpoint needs a decision about who may call it.
 *
 * <p>Expectations in one place:
 *
 * <pre>
 *                             owner  admin  accountant  reader
 * READ         (read data)      yes    yes     yes         yes
 * WRITE        (change data)    yes    yes     yes         no   (the filter: a reader only reads)
 * TEAM         (members,        yes    yes     no          no   (MemberService, InvitationService)
 *               invitations)
 * OWNER_ONLY   (close tenant)   yes    no      no          no   (TenantProvisioningService)
 * </pre>
 *
 * Removing oneself ({@code DELETE /api/tenant/members/<own id>}) is open to every role and is
 * covered by {@code SaasApiTest#membersAreManagedByRoleAndTheLastOwnerStays}.
 */
class SaasRoleMatrixTest extends SaasApiTestBase {

  /** The tenant of this test (created by the base class). */
  private static final int TENANT = 2;

  /** The four roles, in the order of the table. */
  private enum Who {
    OWNER,
    ADMIN,
    ACCOUNTANT,
    READER
  }

  /** What each group of endpoints allows. */
  private static final Set<Who> ALL = Set.of(Who.OWNER, Who.ADMIN, Who.ACCOUNTANT, Who.READER);

  private static final Set<Who> WRITERS = Set.of(Who.OWNER, Who.ADMIN, Who.ACCOUNTANT);
  private static final Set<Who> MANAGERS = Set.of(Who.OWNER, Who.ADMIN);
  private static final Set<Who> OWNER = Set.of(Who.OWNER);

  /** Endpoints that read tenant data: every role. */
  private static final Set<String> READ =
      Set.of(
          "GET /api/articles",
          "GET /api/articles/{id}",
          "GET /api/customers/page",
          "GET /api/customers/{id}",
          "GET /api/documents/variables/list",
          "GET /api/equipment",
          "GET /api/equipment/summary",
          "GET /api/equipment/{id}/usage",
          "GET /api/export/gobd",
          "GET /api/invoices/by-customer/{customerId}",
          "GET /api/invoices/next-number",
          "GET /api/invoices/page",
          "GET /api/invoices/statistics",
          "GET /api/invoices/{id}",
          "GET /api/invoices/{id}/history",
          "GET /api/invoices/{id}/payments",
          "GET /api/invoices/{id}/pdf",
          "GET /api/invoices/{id}/xrechnung",
          "GET /api/invoices/{id}/xrechnung/check",
          "GET /api/owners",
          "GET /api/owners/primary",
          "GET /api/search",
          "GET /api/services",
          "GET /api/services/{id}",
          "GET /api/templates",
          "GET /api/templates/by-type/{documentTypeId}",
          "GET /api/templates/default/{documentTypeId}",
          "GET /api/templates/{id}");

  /**
   * Endpoints that change data or render with a request body: owner, admin and accountant. A reader
   * is refused by the tenant filter for every method but GET, also where the call itself would be
   * harmless (preview, export, variable check).
   */
  private static final Set<String> WRITE =
      Set.of(
          "DELETE /api/articles/{id}",
          "DELETE /api/customers/{id}",
          "DELETE /api/equipment/{id}",
          "DELETE /api/invoices/{id}",
          "DELETE /api/owners/{id}",
          "DELETE /api/services/{id}",
          "DELETE /api/templates/{id}",
          "PATCH /api/invoices/{id}/status",
          "POST /api/articles",
          "POST /api/customers",
          "POST /api/documents/export/new",
          "POST /api/documents/export/{templateId}",
          "POST /api/documents/import",
          "POST /api/documents/variables/validate",
          "POST /api/equipment",
          "POST /api/invoices",
          "POST /api/invoices/{id}/archive",
          "POST /api/invoices/{id}/cancel",
          "POST /api/invoices/{id}/clone",
          "POST /api/invoices/{id}/payments",
          "POST /api/invoices/{id}/payments/{paymentId}/reverse",
          "POST /api/invoices/{id}/unarchive",
          "POST /api/owners",
          "POST /api/services",
          "POST /api/templates",
          "POST /api/templates/from-document/{documentId}",
          "POST /api/templates/preview",
          "POST /api/templates/{id}/set-default",
          "POST /api/upload",
          "PUT /api/articles/{id}",
          "PUT /api/customers/{id}",
          "PUT /api/equipment/{id}",
          "PUT /api/invoices/{id}",
          "PUT /api/owners/{id}",
          "PUT /api/services/{id}",
          "PUT /api/templates/{id}");

  /** Members and invitations: owner and admin only, readers and accountants may not even list. */
  private static final Set<String> TEAM =
      Set.of(
          "DELETE /api/tenant/invitations/{id}",
          "DELETE /api/tenant/members/{userId}",
          "GET /api/tenant/audit",
          "GET /api/tenant/invitations",
          "GET /api/tenant/members",
          "POST /api/tenant/invitations",
          "PUT /api/tenant/members/{userId}");

  /** Closing the tenant: the owner. */
  private static final Set<String> OWNER_ONLY = Set.of("POST /api/tenant/close");

  /** Endpoints that act for the signed-in user alone: no tenant, so no role. */
  private static final Set<String> NO_ROLE =
      Set.of(
          "GET /api/config",
          "GET /api/health",
          "GET /api/health/ready",
          "GET /api/me",
          "POST /api/invitations/{id}/accept",
          "POST /api/tenants");

  @Autowired RequestMappingHandlerMapping mappings;

  private final Map<Who, String> tokens = new EnumMap<>(Who.class);

  /** Creates the four users of the tenant once. */
  private void ensureUsers() throws Exception {
    if (!tokens.isEmpty()) {
      return;
    }
    for (Who who : Who.values()) {
      String name = who.name().toLowerCase(java.util.Locale.ROOT);
      String token = token(idpA, "matrix-" + name, "matrix-" + name + "@example.com");
      member(userId(token), TENANT, who.name());
      tokens.put(who, token);
    }
  }

  /** The table: for every endpoint the roles that may call it. */
  private static Map<String, Set<Who>> table() {
    Map<String, Set<Who>> table = new LinkedHashMap<>();
    READ.forEach(endpoint -> table.put(endpoint, ALL));
    WRITE.forEach(endpoint -> table.put(endpoint, WRITERS));
    TEAM.forEach(endpoint -> table.put(endpoint, MANAGERS));
    OWNER_ONLY.forEach(endpoint -> table.put(endpoint, OWNER));
    return table;
  }

  @Test
  void everyApiEndpointHasARoleRule() {
    Set<String> inApplication = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      if (info.getPathPatternsCondition() == null) {
        continue;
      }
      for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
        if (!pattern.startsWith("/api/")) {
          continue;
        }
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        assertFalse(methods.isEmpty(), pattern + " has no explicit HTTP method");
        for (RequestMethod method : methods) {
          inApplication.add(method + " " + pattern);
        }
      }
    }
    Set<String> declared = new TreeSet<>(table().keySet());
    declared.addAll(NO_ROLE);

    Set<String> missing = new TreeSet<>(inApplication);
    missing.removeAll(declared);
    Set<String> stale = new TreeSet<>(declared);
    stale.removeAll(inApplication);
    assertTrue(
        missing.isEmpty(),
        "endpoints without a role rule (add them to READ, WRITE, TEAM, OWNER_ONLY or NO_ROLE):\n"
            + String.join("\n", missing));
    assertTrue(
        stale.isEmpty(), "listed here but not in the application:\n" + String.join("\n", stale));
    int listed = READ.size() + WRITE.size() + TEAM.size() + OWNER_ONLY.size();
    assertEquals(listed, table().size(), "an endpoint is listed in two groups");
  }

  @Test
  void everyRoleIsAllowedOrRefusedExactlyAsTheTableSays() throws Exception {
    ensureUsers();
    List<String> problems = new ArrayList<>();
    int allowedCells = 0;
    int deniedCells = 0;
    for (Map.Entry<String, Set<Who>> row : table().entrySet()) {
      String endpoint = row.getKey();
      for (Who who : Who.values()) {
        boolean allowed = row.getValue().contains(who);
        Reply reply = send(endpoint, tokens.get(who));
        if (allowed) {
          allowedCells++;
          if (reply.status() == 401 || reply.status() == 403) {
            problems.add(
                who + " is refused on " + endpoint + ": " + reply.status() + " " + reply.body());
          }
        } else {
          deniedCells++;
          if (reply.status() != 403) {
            problems.add(
                who
                    + " should get 403 on "
                    + endpoint
                    + " but got "
                    + reply.status()
                    + " "
                    + reply.body());
          }
        }
        reopenTenant();
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
    assertTrue(allowedCells > 0 && deniedCells > 0, "the table must allow and deny something");
  }

  @Test
  void aReaderOnlyReadsAndAnAccountantNeverManagesTheTeam() {
    // The table, restated as the two rules people rely on, so changing the table cannot loosen them
    // without this test saying so.
    for (Map.Entry<String, Set<Who>> row : table().entrySet()) {
      boolean read = row.getKey().startsWith("GET ");
      if (!read) {
        assertFalse(row.getValue().contains(Who.READER), "a reader writes on " + row.getKey());
      }
      if (TEAM.contains(row.getKey()) || OWNER_ONLY.contains(row.getKey())) {
        assertFalse(
            row.getValue().contains(Who.ACCOUNTANT), "an accountant manages " + row.getKey());
        assertFalse(row.getValue().contains(Who.READER), "a reader manages " + row.getKey());
      }
    }
    assertEquals(MANAGERS, table().get("GET /api/tenant/members"));
    assertEquals(OWNER, table().get("POST /api/tenant/close"));
  }

  @Test
  void theEndpointsWithoutARoleNeedASignInExceptTheTwoOpenOnes() throws Exception {
    // Open to everyone: the sign-in page asks for the mode, the proxy asks for the health.
    assertEquals(200, call("GET", "/api/health", null, null, null).status());
    assertEquals(200, call("GET", "/api/config", null, null, null).status());
    // Everything else without a role is for a signed-in user, whatever tenant or role they have.
    String uuid = UUID.randomUUID().toString();
    assertEquals(401, call("GET", "/api/me", null, null, null).status(), "anonymous /api/me");
    assertEquals(
        401,
        call("POST", "/api/tenants", null, null, "{\"name\":\"Anonymous\"}").status(),
        "anonymous sign-up");
    assertEquals(
        401,
        call("POST", "/api/invitations/" + uuid + "/accept", null, null, null).status(),
        "anonymous accept");
    ensureUsers();
    for (Who who : Who.values()) {
      String token = tokens.get(who);
      assertEquals(200, call("GET", "/api/me", token, null, null).status(), who + " /api/me");
      // An empty name is refused by the service (400), after the sign-in check and without
      // creating a tenant.
      assertEquals(
          400,
          call("POST", "/api/tenants", token, null, "{\"name\":\"\"}").status(),
          who + " sign-up with an empty name");
      assertEquals(
          404,
          call("POST", "/api/invitations/" + uuid + "/accept", token, null, null).status(),
          who + " accept of an unknown invitation");
    }
  }

  // ---- requests --------------------------------------------------------------------------

  private Reply send(String endpoint, String token) throws Exception {
    String[] parts = endpoint.split(" ", 2);
    return call(parts[0], path(parts[1]), token, TENANT, body(endpoint));
  }

  /**
   * A concrete path for a pattern. The ids are placeholders: a role check happens before the id is
   * looked up, and an allowed role then gets 400 or 404, never 403.
   */
  private static String path(String pattern) {
    String uuid = UUID.randomUUID().toString();
    return pattern
        .replace("{userId}", uuid)
        .replace("/api/tenant/invitations/{id}", "/api/tenant/invitations/" + uuid)
        .replaceAll("\\{[A-Za-z]+\\}", "0");
  }

  /** A minimal body where the service reads it before the role check. */
  private static String body(String endpoint) {
    return switch (endpoint) {
      case "POST /api/tenant/invitations" ->
          "{\"email\":\"matrix-invited@example.com\",\"role\":\"READER\"}";
      case "PUT /api/tenant/members/{userId}" -> "{\"role\":\"READER\"}";
      case "POST /api/tenant/close",
          "DELETE /api/tenant/members/{userId}",
          "DELETE /api/tenant/invitations/{id}" ->
          null;
      default -> endpoint.startsWith("GET ") || endpoint.startsWith("DELETE ") ? null : "{}";
    };
  }

  /** The owner's call to close the tenant must not turn every later row into a 403. */
  private void reopenTenant() throws SQLException {
    admin("UPDATE tenant SET status = 'ACTIVE' WHERE id = " + TENANT);
  }
}
