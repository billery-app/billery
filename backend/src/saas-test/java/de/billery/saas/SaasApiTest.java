package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.billery.MainApp;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The SaaS API end to end: the real application in the {@code saas} profile on a random port,
 * connected to PostgreSQL as the application role, with tokens signed by {@link MockIdp}s. Covers
 * token validation (issuer, audience, expiry), first sign-in, membership, roles, tenant selection
 * and the data a tenant sees.
 *
 * <p>Runs only when {@code POSTGRES_TEST_URL} is set (it wipes the {@code public} schema).
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(classes = MainApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("saas")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SaasApiTest extends SaasApiTestBase {

  @Test
  void healthIsOpenAndTheApiNeedsAValidToken() throws Exception {
    assertEquals(200, get("/api/health", null, null).status());
    assertEquals(200, get("/api/health/ready", null, null).status());
    assertEquals(401, get("/api/customers/page", null, 1).status());
    assertEquals(401, get("/api/customers/page", "not-a-token", 1).status());
    assertEquals(401, get("/api/me", null, null).status());
  }

  @Test
  void theAppReadsItsSignInSettingsWithoutAToken() throws Exception {
    Reply config = get("/api/config", null, null);

    assertEquals(200, config.status());
    assertEquals("saas", config.json().get("mode").asText());
    assertEquals(idpA.issuer(), config.json().get("oidc").get("issuer").asText());
    assertEquals("billery-spa", config.json().get("oidc").get("clientId").asText());
    assertEquals(AUDIENCE, config.json().get("oidc").get("audience").asText());
    assertTrue(config.json().get("oidc").get("scope").asText().contains("openid"));
  }

  @Test
  void tokensFromUnknownIssuersWithTheWrongAudienceOrExpiredAreRejected() throws Exception {
    assertEquals(401, get("/api/me", token(stranger, "x", "x@example.com"), null).status());
    assertEquals(
        401,
        get(
                "/api/me",
                idpA.token("x", "x@example.com", true, "someone-else", Duration.ofMinutes(10)),
                null)
            .status());
    assertEquals(
        401,
        get(
                "/api/me",
                idpA.token("x", "x@example.com", true, AUDIENCE, Duration.ofMinutes(-10)),
                null)
            .status());
  }

  @Test
  void theFirstSignInNeedsAVerifiedEmailAndStartsWithoutTenants() throws Exception {
    Reply unverified =
        get(
            "/api/me",
            idpA.token("u1", "u1@example.com", false, AUDIENCE, Duration.ofMinutes(10)),
            null);
    assertEquals(403, unverified.status());

    Reply me = get("/api/me", token(idpA, "newcomer", "newcomer@example.com"), null);
    assertEquals(200, me.status());
    assertEquals("newcomer@example.com", me.json().get("email").asText());
    assertEquals(0, me.json().get("memberships").size());
  }

  @Test
  void aUserSeesOnlyTheTenantItNamesAndOnlyIfItIsAMember() throws Exception {
    String anna = token(idpA, "anna", "anna@example.com");
    String annaId = userId(anna);
    member(annaId, 1, "ADMIN");
    member(annaId, 2, "READER");

    List<String> tenantOne = firstNames(get("/api/customers/page", anna, 1));
    assertTrue(tenantOne.contains("Eins"));
    assertFalse(tenantOne.contains("Zwei") || tenantOne.contains("Drei"));
    assertEquals(List.of("Zwei"), firstNames(get("/api/customers/page", anna, 2)));
    assertEquals(403, get("/api/customers/page", anna, 3).status(), "member of neither");
    assertEquals(403, get("/api/customers/page", anna, 999).status(), "no such tenant");
    assertEquals(400, get("/api/customers/page", anna, null).status(), "two tenants, none named");
    assertEquals(
        2, get("/api/me", anna, null).json().get("memberships").size(), "the tenant switcher data");
  }

  @Test
  void aSingleTenantUserMayOmitTheHeader() throws Exception {
    String bert = token(idpA, "bert", "bert@example.com");
    member(userId(bert), 1, "OWNER");

    List<String> names = firstNames(get("/api/customers/page", bert, null));
    assertTrue(names.contains("Eins"));
    assertFalse(names.contains("Zwei") || names.contains("Drei"));
  }

  @Test
  void aReaderCannotWriteAndAnAdminCan() throws Exception {
    String carla = token(idpA, "carla", "carla@example.com");
    String carlaId = userId(carla);
    member(carlaId, 1, "ADMIN");
    member(carlaId, 2, "READER");
    String body = "{\"firstName\":\"Neu\",\"lastName\":\"Kunde\"}";

    assertEquals(403, call("POST", "/api/customers", carla, 2, body).status());
    Reply created = call("POST", "/api/customers", carla, 1, body);
    assertEquals(200, created.status());

    assertTrue(firstNames(get("/api/customers/page", carla, 1)).contains("Neu"));
    assertFalse(firstNames(get("/api/customers/page", carla, 2)).contains("Neu"));
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement();
        var rs = stmt.executeQuery("SELECT tenant_id FROM customer WHERE first_name = 'Neu'")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1), "stored for the tenant the request named");
      assertFalse(rs.next());
    }
  }

  @Test
  void aSecondIdentityProviderReachesTheSameUserAndData() throws Exception {
    String viaA = token(idpA, "dora-a", "dora@example.com");
    String doraId = userId(viaA);
    member(doraId, 1, "ADMIN");

    String viaB = token(idpB, "dora-b", "dora@example.com");
    assertEquals(doraId, userId(viaB), "linked by the verified e-mail address");
    List<String> names = firstNames(get("/api/customers/page", viaB, 1));
    assertTrue(names.contains("Eins"));
    assertFalse(names.contains("Zwei") || names.contains("Drei"));
  }

  @Test
  void apiDocumentationIsClosedAndUnsignedUploadUrlsAreRefused() throws Exception {
    String anna = token(idpA, "anna", "anna@example.com");
    assertEquals(403, get("/v3/api-docs", anna, 1).status());
    assertEquals(403, get("/uploads/1/anything.png", null, null).status(), "no signature");
    assertEquals(403, get("/uploads/1/anything.png?sig=forged", null, null).status());
  }

  @Test
  void aLogoIsStoredPerTenantAndOnlyItsSignedUrlOpensIt() throws Exception {
    String owner = token(idpA, "uma", "uma@example.com");
    int tenant = signUp(owner, "Uma Logo GmbH");
    String other = token(idpA, "ulf", "ulf@example.com");
    int otherTenant = signUp(other, "Ulf Logo GmbH");

    Reply uploaded = upload(owner, tenant, PNG);
    assertEquals(200, uploaded.status(), uploaded.body());
    String url = uploaded.json().get("url").asText();
    assertTrue(url.contains("/uploads/" + tenant + "/"), url);

    // the browser loads it without any token, with the safety headers of the upload path
    HttpResponse<byte[]> image = fetch(url);
    assertEquals(200, image.statusCode());
    assertEquals("image/png", image.headers().firstValue("Content-Type").orElse(""));
    assertEquals("nosniff", image.headers().firstValue("X-Content-Type-Options").orElse(""));
    assertTrue(
        image.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"));
    assertArrayEquals(PNG, image.body());

    // tampering with the signature, the tenant or the name does not open anything
    String path = url.substring(url.indexOf("/uploads/"));
    String name = path.substring(path.lastIndexOf('/') + 1, path.indexOf('?'));
    String base = url.substring(0, url.indexOf("/uploads/"));
    assertEquals(403, fetch(base + path.replace("sig=", "sig=x")).statusCode());
    assertEquals(403, fetch(base + "/uploads/" + tenant + "/" + name).statusCode(), "unsigned");
    assertEquals(
        403,
        fetch(base + path.replace("/uploads/" + tenant + "/", "/uploads/" + otherTenant + "/"))
            .statusCode(),
        "the signature of one tenant does not open the same name under another");
    assertEquals(
        403,
        fetch(
                base
                    + "/uploads/"
                    + tenant
                    + "/other.png?sig="
                    + path.substring(path.indexOf("sig=") + 4))
            .statusCode());
  }

  /**
   * The renderer reads the logo through the storage (a directory, or the object store with {@code
   * S3_TEST_URL}): the tenant's own logo ends up in its PDF, another tenant's logo never does, even
   * with that tenant's valid signed URL.
   */
  @Test
  void thePdfShowsTheTenantsOwnLogoButNeverAnotherTenantsLogo() throws Exception {
    String owner = token(idpA, "lia", "lia@example.com");
    int tenant = signUp(owner, "Lia Logo GmbH");
    String other = token(idpA, "leo", "leo@example.com");
    int otherTenant = signUp(other, "Leo Logo GmbH");
    Reply company =
        post(
            "/api/owners",
            owner,
            tenant,
            "{\"firstName\":\"Lia\",\"lastName\":\"Logo\",\"companyName\":\"Lia Logo GmbH\","
                + "\"postalCode\":\"12345\",\"city\":\"Berlin\",\"street\":\"Weg\","
                + "\"houseNumber\":\"1\",\"isSmallBusiness\":false}");
    assertTrue(company.status() < 300, company.body());
    String own = upload(owner, tenant, realPng()).json().get("url").asText();
    String foreign = upload(other, otherTenant, realPng()).json().get("url").asText();

    assertTrue(previewWithLogo(owner, tenant, own).contains("/Subtype/Image"), "own logo");
    assertFalse(
        previewWithLogo(owner, tenant, foreign).contains("/Subtype/Image"),
        "another tenant's logo is not printed");
  }

  /** A small image the PDF renderer can actually decode and embed. */
  private static byte[] realPng() throws IOException {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  /** Renders a draft template whose header carries the logo and returns the PDF as text. */
  private String previewWithLogo(String token, int tenant, String logoUrl) throws Exception {
    String blocks =
        JSON.writeValueAsString(
            java.util.List.of(
                java.util.Map.of(
                    "id",
                    "h",
                    "type",
                    "header",
                    "order",
                    0,
                    "data",
                    java.util.Map.of("companyName", "Logo", "logoUrl", logoUrl),
                    "config",
                    java.util.Map.of())));
    Reply pdf =
        post(
            "/api/templates/preview",
            token,
            tenant,
            JSON.writeValueAsString(
                java.util.Map.of(
                    "templateData",
                    java.util.Map.of("name", "Logo", "documentTypeId", 1, "blocks", blocks))));
    assertEquals(200, pdf.status(), pdf.body());
    assertTrue(pdf.body().startsWith("%PDF"), "a PDF");
    return pdf.body();
  }

  @Test
  void aReaderCannotUploadAndAnotherTenantsMemberCannotUploadIntoThisTenant() throws Exception {
    String owner = token(idpA, "vera", "vera@example.com");
    int tenant = signUp(owner, "Vera Logo GmbH");
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"vic@example.com\",\"role\":\"READER\"}");
    String vic = token(idpA, "vic", "vic@example.com");
    post(
        "/api/invitations/"
            + get("/api/me", vic, null).json().get("invitations").get(0).get("id").asText()
            + "/accept",
        vic,
        null,
        null);

    assertEquals(403, upload(vic, tenant, PNG).status(), "a reader only reads");
    String stranger = token(idpA, "wes", "wes@example.com");
    assertEquals(403, upload(stranger, tenant, PNG).status(), "not a member");
  }

  @Test
  void signingUpCreatesAUsableTenantWithTheCallerAsOwner() throws Exception {
    String sue = token(idpA, "sue", "sue@example.com");
    int tenant = signUp(sue, "Sue GmbH");

    JsonNode memberships = get("/api/me", sue, null).json().get("memberships");
    assertEquals("OWNER", memberships.get(0).get("role").asText());
    assertEquals("Sue GmbH", memberships.get(0).get("tenantName").asText());
    assertEquals(200, get("/api/customers/page", sue, tenant).status());
    assertEquals(
        200,
        post("/api/customers", sue, tenant, "{\"firstName\":\"A\",\"lastName\":\"B\"}").status());
    // the default templates were copied for the new tenant
    JsonNode templates = get("/api/templates", sue, tenant).json();
    assertEquals(4, templates.size());
    assertEquals(400, post("/api/tenants", sue, null, "{\"name\":\"  \"}").status());
  }

  @Test
  void anInvitedPersonAcceptsWithTheirVerifiedAddressAndTheRoleApplies() throws Exception {
    String owner = token(idpA, "olga", "olga@example.com");
    int tenant = signUp(owner, "Olga AG");

    Reply invited =
        post(
            "/api/tenant/invitations",
            owner,
            tenant,
            "{\"email\":\"Bob@Example.com\",\"role\":\"reader\"}");
    assertEquals(201, invited.status(), invited.body());
    // No MAIL_* settings in this suite: the invitation is stored and the answer says that no
    // mail went out (InvitationMailerTest covers the mail itself, saas-stack a real SMTP server).
    assertFalse(invited.json().get("mailSent").asBoolean(), invited.body());
    assertEquals(
        "Der Mailversand ist nicht eingerichtet.", invited.json().get("mailProblem").asText());
    assertEquals(
        409,
        post(
                "/api/tenant/invitations",
                owner,
                tenant,
                "{\"email\":\"bob@example.com\",\"role\":\"READER\"}")
            .status(),
        "already invited");
    assertEquals(1, get("/api/tenant/invitations", owner, tenant).json().size());

    String bob = token(idpA, "bob", "bob@example.com");
    JsonNode pending = get("/api/me", bob, null).json().get("invitations");
    assertEquals(1, pending.size());
    assertEquals("Olga AG", pending.get(0).get("tenantName").asText());
    // someone else cannot see or take the invitation
    String mallory = token(idpA, "mallory", "mallory@example.com");
    assertEquals(0, get("/api/me", mallory, null).json().get("invitations").size());
    assertEquals(
        404,
        post(
                "/api/invitations/" + pending.get(0).get("id").asText() + "/accept",
                mallory,
                null,
                null)
            .status());

    assertEquals(
        200,
        post("/api/invitations/" + pending.get(0).get("id").asText() + "/accept", bob, null, null)
            .status());
    assertEquals(0, get("/api/me", bob, null).json().get("invitations").size());
    assertEquals(200, get("/api/customers/page", bob, tenant).status());
    assertEquals(
        403,
        post("/api/customers", bob, tenant, "{\"firstName\":\"x\",\"lastName\":\"y\"}").status(),
        "a reader only reads");
    assertEquals(0, get("/api/tenant/invitations", owner, tenant).json().size(), "no longer open");
    assertEquals(
        409,
        post(
                "/api/tenant/invitations",
                owner,
                tenant,
                "{\"email\":\"bob@example.com\",\"role\":\"READER\"}")
            .status(),
        "already a member");
  }

  @Test
  void anAdminHandsOutOnlyTheRolesBelowOwnerAndAdmin() throws Exception {
    String owner = token(idpA, "owen", "owen@example.com");
    int tenant = signUp(owner, "Owen KG");
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"ada@example.com\",\"role\":\"ADMIN\"}");
    String ada = token(idpA, "ada", "ada@example.com");
    String invitation =
        get("/api/me", ada, null).json().get("invitations").get(0).get("id").asText();
    post("/api/invitations/" + invitation + "/accept", ada, null, null);

    assertEquals(
        403,
        post(
                "/api/tenant/invitations",
                ada,
                tenant,
                "{\"email\":\"x@example.com\",\"role\":\"ADMIN\"}")
            .status());
    assertEquals(
        403,
        post(
                "/api/tenant/invitations",
                ada,
                tenant,
                "{\"email\":\"x@example.com\",\"role\":\"OWNER\"}")
            .status());
    assertEquals(
        201,
        post(
                "/api/tenant/invitations",
                ada,
                tenant,
                "{\"email\":\"x@example.com\",\"role\":\"ACCOUNTANT\"}")
            .status());
    assertEquals(
        400,
        post(
                "/api/tenant/invitations",
                ada,
                tenant,
                "{\"email\":\"not-an-address\",\"role\":\"READER\"}")
            .status());
    assertEquals(
        400,
        post(
                "/api/tenant/invitations",
                ada,
                tenant,
                "{\"email\":\"y@example.com\",\"role\":\"BOSS\"}")
            .status());
  }

  @Test
  void membersAreManagedByRoleAndTheLastOwnerStays() throws Exception {
    String owner = token(idpA, "mia", "mia@example.com");
    int tenant = signUp(owner, "Mia & Co");
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"ben@example.com\",\"role\":\"ACCOUNTANT\"}");
    String ben = token(idpA, "ben", "ben@example.com");
    post(
        "/api/invitations/"
            + get("/api/me", ben, null).json().get("invitations").get(0).get("id").asText()
            + "/accept",
        ben,
        null,
        null);
    String miaId = idOfMember(owner, tenant, "mia@example.com");
    String benId = idOfMember(owner, tenant, "ben@example.com");

    assertEquals(
        403,
        get("/api/tenant/members", ben, tenant).status(),
        "an accountant does not manage members");
    assertEquals(
        409,
        call("PUT", "/api/tenant/members/" + miaId, owner, tenant, "{\"role\":\"ADMIN\"}").status(),
        "last owner");
    assertEquals(
        409,
        call("DELETE", "/api/tenant/members/" + miaId, owner, tenant, null).status(),
        "last owner");
    assertEquals(
        200,
        call("PUT", "/api/tenant/members/" + benId, owner, tenant, "{\"role\":\"READER\"}")
            .status());
    assertEquals(
        403,
        post("/api/customers", ben, tenant, "{\"firstName\":\"x\",\"lastName\":\"y\"}").status());
    assertEquals(
        200,
        call("DELETE", "/api/tenant/members/" + benId, ben, tenant, null).status(),
        "anyone may leave");
    assertEquals(403, get("/api/customers/page", ben, tenant).status());
    assertEquals(404, call("DELETE", "/api/tenant/members/" + benId, owner, tenant, null).status());
  }

  @Test
  void aClosedTenantKeepsItsDataAndTurnsReadOnly() throws Exception {
    String owner = token(idpA, "cleo", "cleo@example.com");
    int tenant = signUp(owner, "Cleo Ltd");
    post("/api/customers", owner, tenant, "{\"firstName\":\"Keep\",\"lastName\":\"Me\"}");
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"dan@example.com\",\"role\":\"ADMIN\"}");
    String dan = token(idpA, "dan", "dan@example.com");
    String invitation =
        get("/api/me", dan, null).json().get("invitations").get(0).get("id").asText();
    post("/api/invitations/" + invitation + "/accept", dan, null, null);
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"eve@example.com\",\"role\":\"READER\"}");
    String eve = token(idpA, "eve", "eve@example.com");
    String eveInvitation =
        get("/api/me", eve, null).json().get("invitations").get(0).get("id").asText();

    assertEquals(
        403, post("/api/tenant/close", dan, tenant, null).status(), "only an owner closes");
    assertEquals(200, post("/api/tenant/close", owner, tenant, null).status());

    assertTrue(firstNames(get("/api/customers/page", owner, tenant)).contains("Keep"));
    Reply refused =
        post("/api/customers", owner, tenant, "{\"firstName\":\"x\",\"lastName\":\"y\"}");
    assertEquals(403, refused.status());
    assertTrue(refused.body().contains("read-only"), refused.body());
    assertEquals(
        409, post("/api/invitations/" + eveInvitation + "/accept", eve, null, null).status());
    assertEquals(
        403, post("/api/tenant/close", owner, tenant, null).status(), "already closed: no writes");
  }

  /**
   * The operator's containment steps of {@code docs/operations/saas-incident-response.md}: a
   * suspended tenant is read-only at once and can still export, removing the membership rows ends
   * an account's access with its next request (the token stays valid), and reopening restores
   * writing.
   */
  @Test
  void anOperatorSuspendsATenantEndsAnAccountsAccessAndReopens() throws Exception {
    String owner = token(idpA, "ines", "ines@example.com");
    int tenant = signUp(owner, "Ines KG");
    post("/api/customers", owner, tenant, "{\"firstName\":\"Keep\",\"lastName\":\"Me\"}");
    post(
        "/api/tenant/invitations",
        owner,
        tenant,
        "{\"email\":\"jon@example.com\",\"role\":\"ACCOUNTANT\"}");
    String jon = token(idpA, "jon", "jon@example.com");
    String invitation =
        get("/api/me", jon, null).json().get("invitations").get(0).get("id").asText();
    post("/api/invitations/" + invitation + "/accept", jon, null, null);
    String jonId = idOfMember(owner, tenant, "jon@example.com");

    admin("UPDATE tenant SET status = 'SUSPENDED' WHERE id = " + tenant);

    assertTrue(firstNames(get("/api/customers/page", owner, tenant)).contains("Keep"));
    Reply refused =
        post("/api/customers", owner, tenant, "{\"firstName\":\"x\",\"lastName\":\"y\"}");
    assertEquals(403, refused.status(), "suspended: no writes");
    assertTrue(refused.body().contains("read-only"), refused.body());
    assertEquals(
        200,
        get("/api/export/gobd?from=2026-01-01&to=2026-12-31&documents=false", owner, tenant)
            .status(),
        "the export still works");

    assertEquals(200, get("/api/customers/page", jon, tenant).status());
    admin("DELETE FROM tenant_member WHERE user_id = '" + jonId + "'");
    assertEquals(403, get("/api/customers/page", jon, tenant).status(), "no membership, no access");
    assertEquals(200, get("/api/me", jon, null).status(), "the token itself is still valid");
    assertEquals(0, get("/api/me", jon, null).json().get("memberships").size());

    admin("UPDATE tenant SET status = 'ACTIVE', closed_at = NULL WHERE id = " + tenant);
    assertEquals(
        200,
        post("/api/customers", owner, tenant, "{\"firstName\":\"x\",\"lastName\":\"y\"}").status(),
        "reopened");
  }

  @Test
  void aNeighbouringTenantsInvitationNeverShowsInThisTenantsList() throws Exception {
    String frank = token(idpA, "frank", "frank@example.com");
    int own = signUp(frank, "Frank Bau");
    String gina = token(idpA, "gina", "gina@example.com");
    int other = signUp(gina, "Gina Bau");
    post(
        "/api/tenant/invitations",
        gina,
        other,
        "{\"email\":\"frank@example.com\",\"role\":\"READER\"}");

    assertEquals(0, get("/api/tenant/invitations", frank, own).json().size());
    assertEquals(1, get("/api/me", frank, null).json().get("invitations").size());
    assertEquals(
        404,
        call(
                "DELETE",
                "/api/tenant/invitations/"
                    + get("/api/me", frank, null)
                        .json()
                        .get("invitations")
                        .get(0)
                        .get("id")
                        .asText(),
                frank,
                own,
                null)
            .status());
  }
}
