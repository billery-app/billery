package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.config.DbConfig;
import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import de.billery.saas.db.TenantAwareDataSource;
import de.billery.saas.identity.IdentityException;
import de.billery.saas.identity.IdentityRepository;
import de.billery.saas.identity.IdentityService;
import de.billery.saas.identity.Role;
import de.billery.saas.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mapping tokens to users on a real PostgreSQL: first sign-in, repeat sign-in, the identity
 * provider switch (a second issuer, same verified e-mail), and the cases that must not link.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class IdentityServiceTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");

  private static IdentityRepository repository;
  private static IdentityService service;

  @BeforeAll
  static void prepare() throws SQLException {
    Database.setDbPath(ADMIN_URL);
    execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    DataSource app = new TenantAwareDataSource(SaasTestSupport.appRoleDataSource(ADMIN_URL));
    repository = new IdentityRepository(JdbcClient.create(app));
    service =
        new IdentityService(
            repository, new TransactionTemplate(DbConfig.newTransactionManager(app)));
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void noUsers() throws SQLException {
    TenantContext.clear();
    execute("DELETE FROM user_identity", "DELETE FROM tenant_member", "DELETE FROM app_user");
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static Jwt token(String issuer, String subject, String email, Boolean verified) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .issuer(issuer)
            .subject(subject)
            .claim("name", "Name " + subject);
    if (email != null) {
      jwt.claim("email", email);
    }
    if (verified != null) {
      jwt.claim("email_verified", verified);
    }
    return jwt.build();
  }

  @Test
  void theFirstSignInCreatesTheUserAndLaterOnesFindIt() {
    UUID first = service.resolve(token("https://idp-a.test", "sub-1", "Anna@Example.com", true));
    UUID again = service.resolve(token("https://idp-a.test", "sub-1", "Anna@Example.com", true));

    assertEquals(first, again);
    assertEquals(first, repository.findUserByEmail("anna@example.com").orElseThrow());
  }

  @Test
  void aKnownIdentityNeedsNoEmailClaimAnymore() {
    UUID user = service.resolve(token("https://idp-a.test", "sub-1", "a@example.com", true));

    assertEquals(user, service.resolve(token("https://idp-a.test", "sub-1", null, null)));
  }

  @Test
  void aSecondProviderIsLinkedToTheSameUserByTheVerifiedEmail() {
    UUID viaA = service.resolve(token("https://idp-a.test", "sub-a", "anna@example.com", true));
    UUID viaB = service.resolve(token("https://idp-b.test", "sub-b", "ANNA@example.com", true));

    assertEquals(viaA, viaB);
    assertEquals(viaA, repository.findUserByIdentity("https://idp-b.test", "sub-b").orElseThrow());
  }

  @Test
  void anUnverifiedEmailNeverLinksToAnExistingUserNorCreatesOne() {
    UUID existing = service.resolve(token("https://idp-a.test", "sub-a", "anna@example.com", true));

    assertThrows(
        IdentityException.class,
        () -> service.resolve(token("https://idp-b.test", "sub-x", "anna@example.com", false)));
    assertThrows(
        IdentityException.class,
        () -> service.resolve(token("https://idp-b.test", "sub-y", "anna@example.com", null)));
    assertThrows(
        IdentityException.class,
        () -> service.resolve(token("https://idp-b.test", "sub-z", null, true)));
    assertTrue(repository.findUserByIdentity("https://idp-b.test", "sub-x").isEmpty());
    assertEquals(existing, repository.findUserByEmail("anna@example.com").orElseThrow());
  }

  @Test
  void thesameSubjectAtAnotherIssuerIsAnotherIdentity() {
    UUID anna = service.resolve(token("https://idp-a.test", "42", "anna@example.com", true));
    UUID bert = service.resolve(token("https://idp-b.test", "42", "bert@example.com", true));

    assertNotEquals(anna, bert);
  }

  @Test
  void parallelFirstSignInsCreateExactlyOneUser() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<UUID>> signIns =
          Collections.nCopies(
              8,
              () ->
                  service.resolve(token("https://idp-a.test", "sub-1", "race@example.com", true)));
      List<UUID> users = new ArrayList<>();
      for (Future<UUID> result : pool.invokeAll(signIns)) {
        users.add(result.get());
      }
      assertEquals(1, users.stream().distinct().count(), users.toString());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, repository.findUserByEmail("race@example.com").stream().count());
  }

  @Test
  void roleAndMembershipsFollowTheMembershipTable() throws Exception {
    UUID user = service.resolve(token("https://idp-a.test", "m", "m@example.com", true));
    execute("INSERT INTO tenant (id, name) VALUES (2, 'Zwei') ON CONFLICT DO NOTHING");
    TenantContext.runAs(1, null, () -> addMember(user, 1, Role.ADMIN));
    TenantContext.runAs(2, null, () -> addMember(user, 2, Role.READER));

    assertEquals(Role.READER, TenantContext.runAs(null, user.toString(), () -> role(user, 2)));
    assertNull(TenantContext.runAs(null, user.toString(), () -> role(user, 3)));
    assertNull(TenantContext.runAs(null, UUID.randomUUID().toString(), () -> role(user, 1)));
    assertEquals(
        List.of(1, 2),
        TenantContext.runAs(
            null,
            user.toString(),
            () -> repository.findMemberships(user).stream().map(m -> m.tenantId()).toList()));
  }

  @Test
  void membersAreWrittenOnlyForTheActiveTenant() throws Exception {
    UUID user = service.resolve(token("https://idp-a.test", "w", "w@example.com", true));
    execute("INSERT INTO tenant (id, name) VALUES (2, 'Zwei') ON CONFLICT DO NOTHING");

    assertThrows(
        Exception.class, () -> TenantContext.runAs(1, null, () -> addMember(user, 2, Role.OWNER)));
    assertThrows(
        Exception.class,
        () -> TenantContext.runAs(null, null, () -> addMember(user, 1, Role.OWNER)));
  }

  private static Object addMember(UUID user, int tenant, Role role) {
    repository.addMember(user, tenant, role);
    return null;
  }

  private static Role role(UUID user, int tenant) {
    return repository.findRole(user, tenant).orElse(null);
  }
}
