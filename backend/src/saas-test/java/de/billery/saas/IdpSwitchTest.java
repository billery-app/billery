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
import de.billery.saas.security.TrustedIssuers;
import de.billery.saas.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Switching the identity provider, step by step as in {@code
 * docs/operations/saas-identity-provider.md}: the new provider is accepted next to the old one, the
 * users reach their existing account by their verified e-mail address, then the old provider is
 * removed. Real signed tokens ({@link MockIdp}) go through the same issuer registry the application
 * uses; the users and their tenant memberships must not change.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class IdpSwitchTest {

  private static final String ADMIN_URL = System.getenv("POSTGRES_TEST_URL");
  private static final String AUDIENCE = "billery";

  private static IdentityRepository repository;
  private static IdentityService service;
  private static MockIdp oldProvider;
  private static MockIdp newProvider;

  @BeforeAll
  static void prepare() throws SQLException {
    Database.setDbPath(ADMIN_URL);
    admin("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    SchemaMigrator.migrate();
    SaasTestSupport.ensureLoginRole(ADMIN_URL);
    DataSource app = new TenantAwareDataSource(SaasTestSupport.appRoleDataSource(ADMIN_URL));
    repository = new IdentityRepository(JdbcClient.create(app));
    service =
        new IdentityService(
            repository, new TransactionTemplate(DbConfig.newTransactionManager(app)));
    oldProvider = new MockIdp();
    newProvider = new MockIdp();
  }

  @AfterAll
  static void stop() {
    oldProvider.close();
    newProvider.close();
    TestDatabaseConfig.resetDatabaseConfig();
  }

  private static void admin(String... statements) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  /** Validates a token the way the application does: through the registry of trusted issuers. */
  private static Jwt authenticate(TrustedIssuers issuers, String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    Authentication result =
        issuers.resolver().resolve(request).authenticate(new BearerTokenAuthenticationToken(token));
    return ((JwtAuthenticationToken) result).getToken();
  }

  private static String token(MockIdp idp, String subject, String email, boolean verified) {
    return idp.token(subject, email, verified, AUDIENCE, Duration.ofMinutes(10));
  }

  @Test
  void theProviderCanBeExchangedWithoutTouchingUsersOrMemberships() throws Exception {
    // 0. the old provider is the only issuer; the user signs in and works in a tenant
    TrustedIssuers onlyOld = new TrustedIssuers(List.of(oldProvider.issuer()), AUDIENCE);
    UUID user =
        service.resolve(
            authenticate(onlyOld, token(oldProvider, "old-1", "dora@example.com", true)));
    admin("INSERT INTO tenant (id, name) VALUES (2, 'Firma') ON CONFLICT DO NOTHING");
    TenantContext.runAs(
        2,
        null,
        () -> {
          repository.addMember(user, 2, Role.ADMIN);
          return null;
        });

    // 1. the new provider is accepted next to the old one: both tokens work
    TrustedIssuers both =
        new TrustedIssuers(List.of(oldProvider.issuer(), newProvider.issuer()), AUDIENCE);
    assertEquals(
        user,
        service.resolve(authenticate(both, token(oldProvider, "old-1", "dora@example.com", true))));

    // 2. the user signs in at the new provider (a new subject there): the verified address links it
    UUID viaNew =
        service.resolve(authenticate(both, token(newProvider, "new-77", "Dora@Example.com", true)));
    assertEquals(user, viaNew, "same user, found by the verified e-mail address");
    assertEquals(user, repository.findUserByIdentity(newProvider.issuer(), "new-77").orElseThrow());

    // 3. the old provider is removed: its tokens are refused, the new one keeps working
    TrustedIssuers onlyNew = new TrustedIssuers(List.of(newProvider.issuer()), AUDIENCE);
    assertThrows(
        InvalidBearerTokenException.class,
        () -> authenticate(onlyNew, token(oldProvider, "old-1", "dora@example.com", true)));
    assertEquals(
        user,
        service.resolve(
            authenticate(onlyNew, token(newProvider, "new-77", "dora@example.com", true))));

    // the membership was never touched
    Role role =
        TenantContext.runAs(
            null, user.toString(), () -> repository.findRole(user, 2).orElseThrow());
    assertEquals(Role.ADMIN, role);
    assertEquals(1, countUsers("dora@example.com"));
  }

  @Test
  void anAddressThatIsNotVerifiedAtTheNewProviderDoesNotTakeOverAnAccount() throws Exception {
    TrustedIssuers both =
        new TrustedIssuers(List.of(oldProvider.issuer(), newProvider.issuer()), AUDIENCE);
    UUID owner =
        service.resolve(authenticate(both, token(oldProvider, "old-2", "erik@example.com", true)));

    assertThrows(
        IdentityException.class,
        () ->
            service.resolve(
                authenticate(both, token(newProvider, "evil", "erik@example.com", false))));
    assertTrue(repository.findUserByIdentity(newProvider.issuer(), "evil").isEmpty());
    assertEquals(owner, repository.findUserByEmail("erik@example.com").orElseThrow());
  }

  @Test
  void aTokenForAnotherApplicationIsRejectedAtAnyProvider() {
    TrustedIssuers both =
        new TrustedIssuers(List.of(oldProvider.issuer(), newProvider.issuer()), AUDIENCE);
    String foreign =
        newProvider.token("x", "x@example.com", true, "some-other-app", Duration.ofMinutes(10));

    assertThrows(Exception.class, () -> authenticate(both, foreign));
  }

  private static int countUsers(String email) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ADMIN_URL);
        var stmt = conn.prepareStatement("SELECT COUNT(*) FROM app_user WHERE lower(email) = ?")) {
      stmt.setString(1, email);
      try (var rs = stmt.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
