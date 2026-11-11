package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Tenant isolation over HTTP, for every endpoint of the API.
 *
 * <p>Two tenants, A (user Alice) and B (user Bob), each with a full set of data. For every endpoint
 * that takes an id or returns data, Alice acts in <b>her own</b> tenant and uses the real ids of
 * Bob's data. The attack passes only if
 *
 * <ul>
 *   <li>the answer is the same as for ids that exist nowhere (no existence leak),
 *   <li>nothing of Bob's data (a unique tag in every name, note and reference) is in the answer,
 *   <li>Bob's data is unchanged afterwards, and
 *   <li>the very same request works when Bob sends it for his own data (so the attack is
 *       well-formed and not refused for an unrelated reason).
 * </ul>
 *
 * Naming Bob's tenant in the {@code X-Tenant-Id} header instead is refused with 403 on every
 * endpoint. A guard test fails for any {@code /api} endpoint that is not listed here, so a new
 * endpoint cannot be added without an isolation test.
 */
class SaasIsolationTest extends SaasApiTestBase {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static final int MISSING = 987_654_321;

  @Autowired RequestMappingHandlerMapping mappings;

  private static String alice;
  private static String bob;
  private static int tenantA;
  private static int tenantB;
  private static String bobUserId;

  @BeforeAll
  static void twoTenants() throws Exception {
    // the application context is created after this method (static state is ready by then)
  }

  // ---- fixture ----------------------------------------------------------------------------

  /** The ids of one complete set of data of a tenant; every name carries {@code tag}. */
  record Fixture(
      String tag,
      int owner,
      int customer,
      int spareCustomer,
      int article,
      int spareArticle,
      int service,
      int spareService,
      int equipment,
      int spareEquipment,
      int draft,
      int issued,
      int payment,
      int template,
      int spareTemplate,
      String member,
      String invitation) {

    /** Ids that exist nowhere: the reference answer of every attack. */
    static Fixture missing() {
      return new Fixture(
          "NOPE",
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          MISSING,
          "00000000-0000-0000-0000-000000000000",
          "00000000-0000-0000-0000-000000000000");
    }
  }

  private void ensureUsers() throws Exception {
    if (alice != null) {
      return;
    }
    alice = token(idpA, "iso-alice", "iso-alice@example.com");
    bob = token(idpA, "iso-bob", "iso-bob@example.com");
    tenantA = signUp(alice, "Isolation A");
    tenantB = signUp(bob, "Isolation B");
    bobUserId = userId(bob);
  }

  private JsonNode json(Reply reply, int expected) throws IOException {
    assertEquals(expected, reply.status(), reply.body());
    return MAPPER.readTree(reply.body());
  }

  private int create(String path, String token, int tenant, String body) throws Exception {
    Reply reply = post(path, token, tenant, body);
    assertTrue(reply.status() / 100 == 2, path + " -> " + reply.status() + " " + reply.body());
    return MAPPER.readTree(reply.body()).get("id").asInt();
  }

  private int ensureOwner(String token, int tenant, String tag) throws Exception {
    Reply primary = get("/api/owners/primary", token, tenant);
    if (primary.status() == 200 && !primary.body().isBlank() && !"null".equals(primary.body())) {
      return MAPPER.readTree(primary.body()).get("id").asInt();
    }
    return create(
        "/api/owners",
        token,
        tenant,
        "{\"firstName\":\"Own\",\"lastName\":\""
            + tag
            + "\",\"companyName\":\"Firma "
            + tag
            + "\",\"companyType\":\"GmbH\",\"postalCode\":\"12345\",\"city\":\"Berlin\","
            + "\"street\":\"Weg\",\"houseNumber\":1,\"phone\":\"123\",\"email\":\"o@x.de\","
            + "\"taxId\":\"DE123456789\",\"iban\":\"DE02120300000000202051\","
            + "\"isSmallBusiness\":false}");
  }

  private int customer(String token, int tenant, String tag, String suffix) throws Exception {
    return create(
        "/api/customers",
        token,
        tenant,
        "{\"firstName\":\"F"
            + suffix
            + tag
            + "\",\"lastName\":\""
            + tag
            + suffix
            + "\",\"postalCode\":\"10115\",\"city\":\"Berlin\",\"street\":\"Str\","
            + "\"houseNumber\":2,\"email\":\"c@x.de\"}");
  }

  private int article(String token, int tenant, String tag, String suffix) throws Exception {
    return create(
        "/api/articles",
        token,
        tenant,
        "{\"name\":\"Art"
            + suffix
            + tag
            + "\",\"description\":\"d\",\"unit\":\"Stk\","
            + "\"purchasePrice\":1,\"salesPrice\":10,\"taxRate\":19}");
  }

  private int invoice(
      String token, int tenant, String tag, int customer, int article, boolean issue)
      throws Exception {
    int id =
        create(
            "/api/invoices",
            token,
            tenant,
            "{\"customerId\":"
                + customer
                + ",\"invoiceDate\":\"2026-03-01\","
                + "\"dueDate\":\"2026-03-31\",\"workStartDate\":\"2026-03-01\","
                + "\"workEndDate\":\"2026-03-02\",\"documentTypeId\":1,\"notes\":\""
                + tag
                + "\",\"items\":[{\"itemType\":\"ARTICLE\",\"itemId\":"
                + article
                + ",\"itemName\":\"Pos "
                + tag
                + "\",\"unit\":\"Stk\",\"quantity\":1,"
                + "\"unitPrice\":10,\"taxRate\":19}]}");
    if (issue) {
      Reply issued =
          call(
              "PATCH",
              "/api/invoices/" + id + "/status",
              token,
              tenant,
              "{\"documentStatusId\":2}");
      assertEquals(200, issued.status(), issued.body());
    }
    return id;
  }

  /**
   * An issued invoice with one line of the service, paid in full: only service lines on fully paid
   * invoices count as equipment usage.
   */
  private void paidServiceInvoice(String token, int tenant, String tag, int customer, int service)
      throws Exception {
    int id =
        create(
            "/api/invoices",
            token,
            tenant,
            "{\"customerId\":"
                + customer
                + ",\"invoiceDate\":\"2026-03-01\",\"dueDate\":\"2026-03-31\","
                + "\"workStartDate\":\"2026-03-01\",\"documentTypeId\":1,\"notes\":\""
                + tag
                + "\",\"items\":[{\"itemType\":\"SERVICE\",\"itemId\":"
                + service
                + ",\"itemName\":\"Einsatz "
                + tag
                + "\",\"unit\":\"h\",\"quantity\":2,\"unitPrice\":50,\"taxRate\":19}]}");
    Reply issued =
        call("PATCH", "/api/invoices/" + id + "/status", token, tenant, "{\"documentStatusId\":2}");
    assertEquals(200, issued.status(), issued.body());
    String gross = MAPPER.readTree(issued.body()).get("grossAmount").asText();
    Reply paid =
        post(
            "/api/invoices/" + id + "/payments",
            token,
            tenant,
            "{\"paymentDate\":\"2026-03-05\",\"amount\":"
                + gross
                + ",\"paymentMethod\":\"Bank\",\"reference\":\""
                + tag
                + "\",\"notes\":\"\"}");
    assertEquals(200, paid.status(), paid.body());
  }

  /** A complete set of data in a tenant, created through the API by its owner. */
  private Fixture fixture(String token, int tenant) throws Exception {
    String tag = "T" + COUNTER.incrementAndGet() + "X" + Long.toString(System.nanoTime(), 36);
    int owner = ensureOwner(token, tenant, tag);
    int customer = customer(token, tenant, tag, "");
    int article = article(token, tenant, tag, "");
    int draft = invoice(token, tenant, tag, customer, article, false);
    int issued = invoice(token, tenant, tag, customer, article, true);
    Reply paid =
        post(
            "/api/invoices/" + issued + "/payments",
            token,
            tenant,
            "{\"paymentDate\":\"2026-03-05\",\"amount\":5.00,\"paymentMethod\":\"Bank\","
                + "\"reference\":\""
                + tag
                + "\",\"notes\":\"\"}");
    assertEquals(200, paid.status(), paid.body());
    int equipment =
        create(
            "/api/equipment",
            token,
            tenant,
            "{\"name\":\"Eq" + tag + "\",\"purchaseValue\":1000,\"status\":\"active\"}");
    int spareEquipment =
        create(
            "/api/equipment",
            token,
            tenant,
            "{\"name\":\"EqS" + tag + "\",\"purchaseValue\":10,\"status\":\"active\"}");
    // The service is linked to the equipment and billed on a fully paid invoice: the usage list
    // and the summary then carry this tenant's data, so the leak check means something
    // there.
    int service =
        create(
            "/api/services",
            token,
            tenant,
            "{\"name\":\"Svc"
                + tag
                + "\",\"description\":\"d\",\"hourlyRate\":50,\"unit\":\"h\","
                + "\"taxRate\":19,\"equipmentId\":"
                + equipment
                + "}");
    paidServiceInvoice(token, tenant, tag, customer, service);
    int spareService =
        create(
            "/api/services",
            token,
            tenant,
            "{\"name\":\"SvcS"
                + tag
                + "\",\"description\":\"d\",\"hourlyRate\":50,\"unit\":\"h\","
                + "\"taxRate\":19}");
    JsonNode templates = json(get("/api/templates", token, tenant), 200);
    int template = templates.get(0).get("id").asInt();
    int spareTemplate =
        create(
            "/api/templates",
            token,
            tenant,
            "{\"name\":\"Tpl"
                + tag
                + "\",\"documentTypeId\":1,\"blocks\":\"[]\","
                + "\"isDefault\":false}");
    String member = secondMember(token, tenant, tag);
    Reply invited =
        post(
            "/api/tenant/invitations",
            token,
            tenant,
            "{\"email\":\"inv-"
                + tag.toLowerCase(java.util.Locale.ROOT)
                + "@example.com\","
                + "\"role\":\"READER\"}");
    assertEquals(201, invited.status(), invited.body());
    String invitation = MAPPER.readTree(invited.body()).get("id").asText();
    return new Fixture(
        tag,
        owner,
        customer,
        customer(token, tenant, tag, "S"),
        article,
        article(token, tenant, tag, "S"),
        service,
        spareService,
        equipment,
        spareEquipment,
        draft,
        issued,
        MAPPER.readTree(paid.body()).get("id").asInt(),
        template,
        spareTemplate,
        member,
        invitation);
  }

  /** A second member (a reader), so that changing or removing a member is possible. */
  private String secondMember(String ownerToken, int tenant, String tag) throws Exception {
    String email = "iso-m-" + tag.toLowerCase(java.util.Locale.ROOT) + "@example.com";
    Reply invited =
        post(
            "/api/tenant/invitations",
            ownerToken,
            tenant,
            "{\"email\":\"" + email + "\",\"role\":\"READER\"}");
    assertEquals(201, invited.status(), invited.body());
    String reader = token(idpA, "iso-m-" + tag, email);
    JsonNode pending = json(get("/api/me", reader, null), 200).get("invitations");
    assertEquals(
        200,
        post(
                "/api/invitations/" + pending.get(0).get("id").asText() + "/accept",
                reader,
                null,
                null)
            .status());
    return userId(reader);
  }

  // ---- the attacks ---------------------------------------------------------------------------

  /**
   * Whether an endpoint is about one object by id, a list of data, or acts on the active tenant.
   */
  enum Kind {
    BY_ID,
    LIST,
    ACTIVE
  }

  /** A request, built from the ids of a fixture. */
  record Attack(
      Kind kind, String method, Function<Fixture, String> path, Function<Fixture, String> body) {

    static Attack get(Kind kind, Function<Fixture, String> path) {
      return new Attack(kind, "GET", path, f -> null);
    }

    static Attack send(
        String method, Function<Fixture, String> path, Function<Fixture, String> body) {
      return new Attack(Kind.BY_ID, method, path, body);
    }
  }

  private static String customerBody(Fixture f) {
    return "{\"firstName\":\"Hacked\",\"lastName\":\"Hacked\",\"postalCode\":\"1\",\"city\":\"x\","
        + "\"street\":\"x\",\"houseNumber\":1}";
  }

  private static Map<String, Attack> attacks() {
    Map<String, Attack> a = new LinkedHashMap<>();
    // articles, customers, services, equipment, owners
    a.put("GET /api/articles", Attack.get(Kind.LIST, f -> "/api/articles"));
    a.put(
        "GET /api/articles/{id}", Attack.get(Kind.BY_ID, f -> "/api/articles/" + f.spareArticle()));
    a.put(
        "PUT /api/articles/{id}",
        Attack.send(
            "PUT",
            f -> "/api/articles/" + f.spareArticle(),
            f ->
                "{\"name\":\"Hacked\",\"description\":\"x\",\"unit\":\"x\",\"purchasePrice\":1,"
                    + "\"salesPrice\":1,\"taxRate\":19}"));
    a.put(
        "DELETE /api/articles/{id}",
        Attack.send("DELETE", f -> "/api/articles/" + f.spareArticle(), f -> null));
    a.put("GET /api/customers/page", Attack.get(Kind.LIST, f -> "/api/customers/page"));
    a.put(
        "GET /api/customers/{id}",
        Attack.get(Kind.BY_ID, f -> "/api/customers/" + f.spareCustomer()));
    a.put(
        "PUT /api/customers/{id}",
        Attack.send(
            "PUT", f -> "/api/customers/" + f.spareCustomer(), SaasIsolationTest::customerBody));
    a.put(
        "DELETE /api/customers/{id}",
        Attack.send("DELETE", f -> "/api/customers/" + f.spareCustomer(), f -> null));
    a.put("GET /api/services", Attack.get(Kind.LIST, f -> "/api/services"));
    a.put(
        "GET /api/services/{id}", Attack.get(Kind.BY_ID, f -> "/api/services/" + f.spareService()));
    a.put(
        "PUT /api/services/{id}",
        Attack.send(
            "PUT",
            f -> "/api/services/" + f.spareService(),
            f ->
                "{\"name\":\"Hacked\",\"description\":\"x\",\"hourlyRate\":1,\"unit\":\"x\","
                    + "\"taxRate\":19}"));
    a.put(
        "DELETE /api/services/{id}",
        Attack.send("DELETE", f -> "/api/services/" + f.spareService(), f -> null));
    a.put("GET /api/equipment", Attack.get(Kind.LIST, f -> "/api/equipment"));
    a.put("GET /api/equipment/summary", Attack.get(Kind.LIST, f -> "/api/equipment/summary"));
    a.put(
        "GET /api/equipment/{id}/usage",
        Attack.get(Kind.BY_ID, f -> "/api/equipment/" + f.equipment() + "/usage"));
    a.put(
        "PUT /api/equipment/{id}",
        Attack.send(
            "PUT",
            f -> "/api/equipment/" + f.spareEquipment(),
            f -> "{\"name\":\"Hacked\",\"purchaseValue\":1,\"status\":\"active\"}"));
    a.put(
        "DELETE /api/equipment/{id}",
        Attack.send("DELETE", f -> "/api/equipment/" + f.spareEquipment(), f -> null));
    a.put("GET /api/owners", Attack.get(Kind.LIST, f -> "/api/owners"));
    a.put("GET /api/owners/primary", Attack.get(Kind.LIST, f -> "/api/owners/primary"));
    a.put(
        "PUT /api/owners/{id}",
        Attack.send(
            "PUT",
            f -> "/api/owners/" + f.owner(),
            f ->
                "{\"firstName\":\"Hacked\",\"lastName\":\"Hacked\",\"companyName\":\"Hacked\","
                    + "\"isSmallBusiness\":false}"));
    a.put(
        "DELETE /api/owners/{id}",
        Attack.send("DELETE", f -> "/api/owners/" + f.owner(), f -> null));
    // documents
    a.put("GET /api/invoices/page", Attack.get(Kind.LIST, f -> "/api/invoices/page"));
    a.put("GET /api/invoices/statistics", Attack.get(Kind.LIST, f -> "/api/invoices/statistics"));
    a.put(
        "GET /api/invoices/next-number",
        Attack.get(Kind.LIST, f -> "/api/invoices/next-number?documentTypeId=1"));
    a.put(
        "GET /api/invoices/by-customer/{customerId}",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/by-customer/" + f.customer()));
    a.put("GET /api/invoices/{id}", Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.draft()));
    a.put(
        "PUT /api/invoices/{id}",
        Attack.send(
            "PUT",
            f -> "/api/invoices/" + f.draft(),
            f ->
                "{\"customerId\":"
                    + f.customer()
                    + ",\"invoiceDate\":\"2026-03-01\","
                    + "\"dueDate\":\"2026-03-31\",\"documentTypeId\":1,\"notes\":\"Hacked\","
                    + "\"items\":[]}"));
    a.put(
        "DELETE /api/invoices/{id}",
        Attack.send("DELETE", f -> "/api/invoices/" + f.draft(), f -> null));
    a.put(
        "POST /api/invoices/{id}/clone",
        Attack.send("POST", f -> "/api/invoices/" + f.draft() + "/clone", f -> "{}"));
    a.put(
        "PATCH /api/invoices/{id}/status",
        Attack.send(
            "PATCH",
            f -> "/api/invoices/" + f.draft() + "/status",
            f -> "{\"documentStatusId\":2}"));
    a.put(
        "POST /api/invoices/{id}/cancel",
        Attack.send("POST", f -> "/api/invoices/" + f.issued() + "/cancel", f -> "{}"));
    a.put(
        "POST /api/invoices/{id}/archive",
        Attack.send("POST", f -> "/api/invoices/" + f.draft() + "/archive", f -> null));
    a.put(
        "POST /api/invoices/{id}/unarchive",
        Attack.send("POST", f -> "/api/invoices/" + f.draft() + "/unarchive", f -> null));
    a.put(
        "GET /api/invoices/{id}/payments",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.issued() + "/payments"));
    a.put(
        "POST /api/invoices/{id}/payments",
        Attack.send(
            "POST",
            f -> "/api/invoices/" + f.issued() + "/payments",
            f ->
                "{\"paymentDate\":\"2026-03-06\",\"amount\":1.00,\"paymentMethod\":\"x\","
                    + "\"reference\":\"Hacked\",\"notes\":\"\"}"));
    a.put(
        "POST /api/invoices/{id}/payments/{paymentId}/reverse",
        Attack.send(
            "POST",
            f -> "/api/invoices/" + f.issued() + "/payments/" + f.payment() + "/reverse",
            f -> "{}"));
    a.put(
        "GET /api/invoices/{id}/history",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.draft() + "/history"));
    a.put(
        "GET /api/invoices/{id}/pdf",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.draft() + "/pdf"));
    a.put(
        "GET /api/invoices/{id}/xrechnung",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.issued() + "/xrechnung"));
    a.put(
        "GET /api/invoices/{id}/xrechnung/check",
        Attack.get(Kind.BY_ID, f -> "/api/invoices/" + f.issued() + "/xrechnung/check"));
    // templates
    a.put("GET /api/templates", Attack.get(Kind.LIST, f -> "/api/templates"));
    a.put(
        "GET /api/templates/{id}",
        Attack.get(Kind.BY_ID, f -> "/api/templates/" + f.spareTemplate()));
    a.put(
        "GET /api/templates/by-type/{documentTypeId}",
        Attack.get(Kind.LIST, f -> "/api/templates/by-type/1"));
    a.put(
        "GET /api/templates/default/{documentTypeId}",
        Attack.get(Kind.LIST, f -> "/api/templates/default/1"));
    a.put(
        "PUT /api/templates/{id}",
        Attack.send(
            "PUT",
            f -> "/api/templates/" + f.spareTemplate(),
            f ->
                "{\"name\":\"Hacked\",\"documentTypeId\":1,\"blocks\":\"[]\",\"isDefault\":false}"));
    a.put(
        "DELETE /api/templates/{id}",
        Attack.send("DELETE", f -> "/api/templates/" + f.spareTemplate(), f -> null));
    a.put(
        "POST /api/templates/{id}/set-default",
        Attack.send(
            "POST", f -> "/api/templates/" + f.spareTemplate() + "/set-default", f -> null));
    a.put(
        "POST /api/templates/from-document/{documentId}",
        Attack.send("POST", f -> "/api/templates/from-document/" + f.draft(), f -> "{}"));
    a.put(
        "POST /api/documents/export/{templateId}",
        Attack.send(
            "POST", f -> "/api/documents/export/" + f.spareTemplate() + "?format=docx", f -> null));
    // search and export
    a.put("GET /api/search", Attack.get(Kind.LIST, f -> "/api/search?q=T"));
    a.put(
        "GET /api/export/gobd",
        Attack.get(
            Kind.LIST, f -> "/api/export/gobd?from=2026-01-01&to=2026-12-31&documents=false"));
    // tenant administration: members and invitations of another tenant
    a.put("GET /api/tenant/members", Attack.get(Kind.LIST, f -> "/api/tenant/members"));
    a.put("GET /api/tenant/invitations", Attack.get(Kind.LIST, f -> "/api/tenant/invitations"));
    a.put("GET /api/tenant/audit", Attack.get(Kind.LIST, f -> "/api/tenant/audit"));
    a.put(
        "PUT /api/tenant/members/{userId}",
        Attack.send("PUT", f -> "/api/tenant/members/" + f.member(), f -> "{\"role\":\"READER\"}"));
    a.put(
        "DELETE /api/tenant/members/{userId}",
        Attack.send("DELETE", f -> "/api/tenant/members/" + f.member(), f -> null));
    a.put(
        "DELETE /api/tenant/invitations/{id}",
        Attack.send("DELETE", f -> "/api/tenant/invitations/" + f.invitation(), f -> null));
    return a;
  }

  /** Endpoints that create data in, or act on, the active tenant only (checked separately). */
  private static final Set<String> ACTS_ON_ACTIVE_TENANT =
      Set.of(
          "POST /api/articles",
          "POST /api/customers",
          "POST /api/services",
          "POST /api/equipment",
          "POST /api/owners",
          "POST /api/invoices",
          "POST /api/templates",
          "POST /api/templates/preview",
          "POST /api/documents/import",
          "POST /api/documents/export/new",
          "POST /api/upload",
          "POST /api/tenant/invitations",
          "POST /api/tenant/close");

  /** Endpoints with no tenant data at all. */
  private static final Set<String> TENANT_FREE =
      Set.of(
          "GET /api/health",
          "GET /api/health/ready",
          "GET /api/config",
          "GET /api/me",
          "POST /api/tenants",
          "POST /api/invitations/{id}/accept",
          "GET /api/documents/variables/list",
          "POST /api/documents/variables/validate");

  // ---- the guard -------------------------------------------------------------------------------

  @Test
  void everyApiEndpointHasAnIsolationTest() {
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
        for (RequestMethod method : methods) {
          inApplication.add(method + " " + pattern);
        }
        assertFalse(methods.isEmpty(), pattern + " has no explicit HTTP method");
      }
    }
    Set<String> declared = new TreeSet<>(attacks().keySet());
    declared.addAll(ACTS_ON_ACTIVE_TENANT);
    declared.addAll(TENANT_FREE);

    Set<String> missing = new TreeSet<>(inApplication);
    missing.removeAll(declared);
    Set<String> stale = new TreeSet<>(declared);
    stale.removeAll(inApplication);
    assertTrue(
        missing.isEmpty(),
        "endpoints without an isolation test (add an attack, or list it as tenant-free):\n"
            + String.join("\n", missing));
    assertTrue(
        stale.isEmpty(), "listed here but not in the application:\n" + String.join("\n", stale));
  }

  // ---- helpers for the matrix --------------------------------------------------------------

  private record Raw(int status, byte[] body) {
    String text() {
      return new String(body, StandardCharsets.ISO_8859_1);
    }
  }

  private Raw raw(String method, String path, String token, Integer tenant, String body)
      throws Exception {
    String origin = "http://localhost:" + port;
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(30))
            .header("Origin", origin)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token);
    if (tenant != null) {
      request.header("X-Tenant-Id", tenant.toString());
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<byte[]> response =
        java.net.http.HttpClient.newHttpClient()
            .send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Raw(response.statusCode(), response.body());
  }

  /** True if the tag appears in the body, also inside the entries of a ZIP. */
  private static boolean containsTag(Raw response, String tag) throws IOException {
    if (response.text().contains(tag)) {
      return true;
    }
    if (response.body().length > 2 && response.body()[0] == 'P' && response.body()[1] == 'K') {
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains(tag)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  // ---- the matrix
  // ----------------------------------------------------------------------------------

  @Test
  void aTenantCannotReachAnotherTenantsDataThroughAnyEndpoint() throws Exception {
    ensureUsers();
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Attack> entry : attacks().entrySet()) {
      String key = entry.getKey();
      Attack attack = entry.getValue();
      Fixture target = fixture(bob, tenantB);

      Raw stolen =
          raw(
              attack.method(),
              attack.path().apply(target),
              alice,
              tenantA,
              attack.body().apply(target));
      Raw reference =
          raw(
              attack.method(),
              attack.path().apply(Fixture.missing()),
              alice,
              tenantA,
              attack.body().apply(Fixture.missing()));

      if (attack.kind() == Kind.BY_ID && stolen.status() != reference.status()) {
        problems.add(
            key + ": B's ids answer " + stolen.status() + ", unknown ids " + reference.status());
      }
      if (containsTag(stolen, target.tag())) {
        problems.add(key + ": the answer contains data of the other tenant");
      }
      // the other tenant's data is untouched
      Raw customer = raw("GET", "/api/customers/" + target.spareCustomer(), bob, tenantB, null);
      Raw invoice = raw("GET", "/api/invoices/" + target.draft(), bob, tenantB, null);
      if (customer.status() != 200 || !customer.text().contains(target.tag())) {
        problems.add(key + ": the other tenant's customer was changed or removed");
      }
      if (invoice.status() != 200 || !invoice.text().contains(target.tag())) {
        problems.add(key + ": the other tenant's invoice was changed or removed");
      }
      // naming the other tenant in the header is refused
      Raw foreign =
          raw(
              attack.method(),
              attack.path().apply(target),
              alice,
              tenantB,
              attack.body().apply(target));
      if (foreign.status() != 403) {
        problems.add(
            key
                + ": X-Tenant-Id of a foreign tenant answers "
                + foreign.status()
                + " instead of 403");
      }
      // the same request works for its owner: the attack was well-formed
      Raw control =
          raw(
              attack.method(),
              attack.path().apply(target),
              bob,
              tenantB,
              attack.body().apply(target));
      if (control.status() / 100 != 2) {
        problems.add(
            key
                + ": positive control by the owner answers "
                + control.status()
                + " "
                + control.text().substring(0, Math.min(120, control.text().length())));
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  /**
   * Equipment usage and the amortisation summary of another tenant stay out of reach. The owner
   * sees the paid service line and its revenue; Alice, with the real equipment id, gets the answer
   * of an id that exists nowhere, and her summary has nothing of Bob's.
   */
  @Test
  void equipmentUsageAndRevenueOfAnotherTenantStayOutOfReach() throws Exception {
    ensureUsers();
    Fixture target = fixture(bob, tenantB);
    String usage = "/api/equipment/" + target.equipment() + "/usage";

    Raw own = raw("GET", usage, bob, tenantB, null);
    assertEquals(200, own.status());
    assertTrue(containsTag(own, target.tag()), "the owner sees the line: " + own.text());
    JsonNode summary =
        MAPPER.readTree(raw("GET", "/api/equipment/summary", bob, tenantB, null).body());
    JsonNode entry = null;
    for (JsonNode e : summary) {
      if (e.get("id").asInt() == target.equipment()) {
        entry = e;
      }
    }
    assertNotNull(entry, "the owner's summary lists the equipment");
    assertEquals(1, entry.get("usageCount").asInt(), entry.toString());
    assertTrue(entry.get("totalRevenue").decimalValue().signum() > 0, entry.toString());

    Raw stolen = raw("GET", usage, alice, tenantA, null);
    Raw unknown = raw("GET", "/api/equipment/" + MISSING + "/usage", alice, tenantA, null);
    assertEquals(unknown.status(), stolen.status(), "no existence leak");
    assertFalse(containsTag(stolen, target.tag()), stolen.text());
    Raw aliceSummary = raw("GET", "/api/equipment/summary", alice, tenantA, null);
    assertFalse(containsTag(aliceSummary, target.tag()), aliceSummary.text());
    for (JsonNode e : MAPPER.readTree(aliceSummary.body())) {
      assertNotEquals(
          target.equipment(), e.get("id").asInt(), "Bob's equipment in Alice's summary");
    }
  }

  @Test
  void whatATenantCreatesStaysInThatTenant() throws Exception {
    ensureUsers();
    String tag = "ALICE" + COUNTER.incrementAndGet() + "X" + Long.toString(System.nanoTime(), 36);
    int customer = customer(alice, tenantA, tag, "");
    int article = article(alice, tenantA, tag, "");
    int invoice = invoice(alice, tenantA, tag, customer, article, false);
    create(
        "/api/equipment",
        alice,
        tenantA,
        "{\"name\":\"Eq" + tag + "\",\"purchaseValue\":1,\"status\":\"active\"}");
    create(
        "/api/services",
        alice,
        tenantA,
        "{\"name\":\"Svc"
            + tag
            + "\",\"description\":\"d\",\"hourlyRate\":1,\"unit\":\"h\",\"taxRate\":19}");
    create(
        "/api/templates",
        alice,
        tenantA,
        "{\"name\":\"Tpl" + tag + "\",\"documentTypeId\":1,\"blocks\":\"[]\",\"isDefault\":false}");

    for (String list :
        List.of(
            "/api/customers/page",
            "/api/articles",
            "/api/services",
            "/api/equipment",
            "/api/invoices/page",
            "/api/templates")) {
      assertTrue(
          containsTag(raw("GET", list, alice, tenantA, null), tag),
          "Alice sees her own data in " + list);
      assertFalse(
          containsTag(raw("GET", list, bob, tenantB, null), tag), "Bob must not see it in " + list);
    }
    assertFalse(containsTag(raw("GET", "/api/search?q=" + tag, bob, tenantB, null), tag));
    assertEquals(404, raw("GET", "/api/invoices/" + invoice, bob, tenantB, null).status());
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM customer WHERE tenant_id <> "
                + tenantA
                + " AND last_name = '"
                + tag
                + "'"),
        "stored for the acting tenant only");
  }

  @Test
  void aForeignTenantHeaderIsRefusedOnEveryEndpointThatCreatesOrActs() throws Exception {
    ensureUsers();
    List<String> problems = new ArrayList<>();
    String[][] requests = {
      {"POST", "/api/articles", "{}"},
      {"POST", "/api/customers", "{}"},
      {"POST", "/api/services", "{}"},
      {"POST", "/api/equipment", "{}"},
      {"POST", "/api/owners", "{}"},
      {"POST", "/api/invoices", "{}"},
      {"POST", "/api/templates", "{}"},
      {"POST", "/api/templates/preview", "{}"},
      {"POST", "/api/documents/export/new", "{}"},
      {"POST", "/api/tenant/invitations", "{}"},
      {"POST", "/api/tenant/close", null},
      {"POST", "/api/upload", null}
    };
    for (String[] request : requests) {
      Raw reply = raw(request[0], request[1], alice, tenantB, request[2]);
      if (reply.status() != 403) {
        problems.add(request[0] + " " + request[1] + " -> " + reply.status());
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
    assertEquals(
        "ACTIVE",
        admin1("SELECT status FROM tenant WHERE id = " + tenantB),
        "the attempt to close the other tenant did nothing");
  }

  private static int count(String sql) throws java.sql.SQLException {
    return Integer.parseInt(admin1(sql));
  }

  private static String admin1(String sql) throws java.sql.SQLException {
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(System.getenv("POSTGRES_TEST_URL"));
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
