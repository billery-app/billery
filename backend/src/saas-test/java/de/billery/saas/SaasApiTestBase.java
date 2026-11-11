package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.billery.MainApp;
import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
abstract class SaasApiTestBase {

  static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");
  static final String AUDIENCE = "billery";
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpClient HTTP = HttpClient.newHttpClient();

  /** Random per run, see {@code UploadStorageTest}. */
  static final String FILES_SECRET =
      java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();

  /**
   * Sign-up limits of the next context, by property name; empty = all off. Read when the context
   * starts; every test class gets a context of its own ({@code @DirtiesContext}).
   */
  static volatile java.util.Map<String, Integer> signUpLimits = java.util.Map.of();

  static java.nio.file.Path uploads;
  static MockIdp idpA;
  static MockIdp idpB;
  static MockIdp stranger;

  @Value("${local.server.port}")
  int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    // The suites fire hundreds of requests per tenant within seconds; RequestLimitsTest and
    // RequestLimitFilterTest cover the limits.
    registry.add("billery.limits.requests-per-second", () -> "0");
    // Every test signs up companies from 127.0.0.1, so the sign-up limits are off (0) unless a
    // test class sets them before its context starts (SignUpLimitsTest).
    for (String limit :
        List.of(
            "billery.max-companies-per-user",
            "billery.signups-per-user-per-day",
            "billery.signups-per-ip",
            "billery.signups-per-day")) {
      registry.add(limit, () -> signUpLimits.getOrDefault(limit, 0));
    }
    registry.add("billery.security.issuers", () -> idpA.issuer() + "," + idpB.issuer());
    registry.add("billery.security.audience", () -> AUDIENCE);
    registry.add("billery.oidc.client-id", () -> "billery-spa");
    registry.add("billery.files.secret", () -> FILES_SECRET);
    registry.add("billery.files.dir", () -> uploads.toString());
    if (S3TestStore.configured()) {
      // With S3_TEST_URL the whole SaaS API suite keeps its uploads in the object store.
      S3TestStore s3 = S3TestStore.CONFIGURED;
      registry.add("billery.files.store", () -> "s3");
      registry.add("billery.files.s3.endpoint", s3::endpoint);
      registry.add("billery.files.s3.bucket", s3::bucket);
      registry.add("billery.files.s3.access-key", s3::accessKey);
      registry.add("billery.files.s3.secret-key", s3::secretKey);
    }
  }

  @BeforeAll
  static void prepare() throws SQLException, IOException {
    uploads = java.nio.file.Files.createTempDirectory("billery-uploads");
    idpA = new MockIdp();
    idpB = new MockIdp();
    stranger = new MockIdp();
    Database.setDbPath(ADMIN_URL);
    admin("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    admin(
        "INSERT INTO tenant (id, name) VALUES (2, 'Zwei'), (3, 'Drei')",
        "SELECT setval(pg_get_serial_sequence('tenant', 'id'), 3)",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (1, 'Eins', 'A')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (2, 'Zwei', 'B')",
        "INSERT INTO customer (tenant_id, first_name, last_name) VALUES (3, 'Drei', 'C')");
    // From here on the application connects as the application role, like in production.
    Database.setDbPath(
        ADMIN_URL.replaceAll("[?&](user|password)=[^&]*", "").replaceFirst("&", "?")
            + "?user="
            + SaasTestSupport.APP_LOGIN
            + "&password=rls-test");
  }

  @AfterAll
  static void stop() throws IOException {
    try (var files = java.nio.file.Files.walk(uploads)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
    idpA.close();
    idpB.close();
    stranger.close();
    TestDatabaseConfig.resetDatabaseConfig();
  }

  static void admin(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  record Reply(int status, String body) {
    JsonNode json() throws IOException {
      return JSON.readTree(body);
    }
  }

  Reply call(String method, String path, String token, Integer tenant, String body)
      throws IOException, InterruptedException {
    String origin = "http://localhost:" + port;
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(origin + path))
            .timeout(Duration.ofSeconds(20))
            .header("Origin", origin)
            .header("Content-Type", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (tenant != null) {
      request.header("X-Tenant-Id", tenant.toString());
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), response.body());
  }

  Reply get(String path, String token, Integer tenant) throws Exception {
    return call("GET", path, token, tenant, null);
  }

  static String token(MockIdp idp, String subject, String email) {
    return idp.token(subject, email, true, AUDIENCE, Duration.ofMinutes(10));
  }

  /** Signs the user in (creating it) and returns its id. */
  String userId(String token) throws Exception {
    return get("/api/me", token, null).json().get("userId").asText();
  }

  void member(String userId, int tenant, String role) throws SQLException {
    admin(
        "INSERT INTO tenant_member (tenant_id, user_id, role) VALUES ("
            + tenant
            + ", '"
            + userId
            + "', '"
            + role
            + "') ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role");
  }

  static List<String> firstNames(Reply reply) throws IOException {
    List<String> names = new ArrayList<>();
    reply.json().get("items").forEach(customer -> names.add(customer.get("firstName").asText()));
    return names;
  }

  // ---- uploaded files -------------------------------------------------------------------

  Reply upload(String token, int tenant, byte[] png) throws Exception {
    String boundary = "----billery" + System.nanoTime();
    String origin = "http://localhost:" + port;
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    body.write(png);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(origin + "/api/upload"))
            .header("Origin", origin)
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", Integer.toString(tenant))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), response.body());
  }

  HttpResponse<byte[]> fetch(String url) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

  // ---- sign-up, invitations, members, closing -------------------------------------------

  Reply post(String path, String token, Integer tenant, String body) throws Exception {
    return call("POST", path, token, tenant, body);
  }

  int signUp(String token, String company) throws Exception {
    Reply reply = post("/api/tenants", token, null, "{\"name\":\"" + company + "\"}");
    assertEquals(201, reply.status(), reply.body());
    return reply.json().get("tenantId").asInt();
  }

  String idOfMember(String ownerToken, int tenant, String email) throws Exception {
    for (JsonNode member : get("/api/tenant/members", ownerToken, tenant).json()) {
      if (email.equals(member.get("email").asText())) {
        return member.get("userId").asText();
      }
    }
    return fail("no member " + email);
  }
}
