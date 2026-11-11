package de.billery.saas;

import static java.time.Duration.ofMinutes;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.saas.security.TrustedIssuers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The registry of trusted issuers refuses to exist without an audience (threat model G2): a token
 * the same provider minted for another application must never be accepted. The rejection of a token
 * with another audience, and the acceptance of the right one, run against the real application in
 * {@link SaasApiTest#tokensFromUnknownIssuersWithTheWrongAudienceOrExpiredAreRejected()}.
 */
class TrustedIssuersTest {

  private static final List<String> ISSUERS = List.of("https://idp.example.com/");

  @Test
  void theStartFailsWithoutAnAudienceAndTheMessageNamesTheVariable() {
    for (String missing : new String[] {null, "", "   "}) {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> new TrustedIssuers(ISSUERS, missing),
              "audience: [" + missing + "]");
      assertTrue(failure.getMessage().contains("BILLERY_SECURITY_AUDIENCE"), failure.getMessage());
    }
  }

  @Test
  void anIssuerWithAPathAndATrailingSlashLikeAuthentiksWorks() {
    // authentik: https://host/application/o/<slug>/ ; the token's iss carries the slash too.
    try (MockIdp idp = new MockIdp("/application/o/billery/")) {
      assertTrue(idp.issuer().endsWith("/application/o/billery/"), idp.issuer());
      TrustedIssuers trusted = new TrustedIssuers(List.of(idp.issuer()), "billery");

      Jwt accepted =
          authenticate(trusted, idp.token("u1", "a@example.com", true, "billery", ofMinutes(10)));
      assertEquals(idp.issuer(), accepted.getClaimAsString("iss"));

      // the same provider, another audience: still refused
      assertThrows(
          OAuth2AuthenticationException.class,
          () ->
              authenticate(
                  trusted, idp.token("u1", "a@example.com", true, "other", ofMinutes(10))));
    }
  }

  private static Jwt authenticate(TrustedIssuers issuers, String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    return ((JwtAuthenticationToken)
            issuers
                .resolver()
                .resolve(request)
                .authenticate(new BearerTokenAuthenticationToken(token)))
        .getToken();
  }

  @Test
  void anAudienceAndAnIssuerAreAccepted() {
    assertDoesNotThrow(() -> new TrustedIssuers(ISSUERS, "billery"));
  }

  @Test
  void anIssuerIsStillRequired() {
    assertThrows(IllegalStateException.class, () -> new TrustedIssuers(List.of(), "billery"));
  }
}
