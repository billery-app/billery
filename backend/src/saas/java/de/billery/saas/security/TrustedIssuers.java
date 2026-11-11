package de.billery.saas.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

/**
 * The identity providers the backend accepts tokens from. Several may be listed at once: that is
 * how the provider is exchanged without a cut-over (see {@code
 * docs/architecture/multi-tenancy.md}).
 *
 * <p>The decoder of an issuer is built on its first token (OIDC discovery, signing keys) and
 * validates signature, expiry, issuer and the audience. The audience is mandatory: without it a
 * token the same provider minted for another application would be accepted. Tokens of any other
 * issuer are rejected.
 */
public final class TrustedIssuers {

  private final Set<String> issuers;
  private final String audience;
  private final Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();

  /**
   * Creates the registry.
   *
   * @param issuers the accepted issuer URLs; at least one
   * @param audience the audience a token must carry; required
   * @throws IllegalStateException if no issuer or no audience is configured
   */
  public TrustedIssuers(List<String> issuers, String audience) {
    if (issuers.isEmpty()) {
      throw new IllegalStateException(
          "The saas profile needs at least one trusted issuer (billery.security.issuers)");
    }
    if (audience == null || audience.isBlank()) {
      throw new IllegalStateException(
          "The saas profile needs the audience of the access tokens"
              + " (billery.security.audience, environment BILLERY_SECURITY_AUDIENCE)");
    }
    this.issuers = Set.copyOf(issuers);
    this.audience = audience.trim();
  }

  /**
   * The resolver Spring Security's resource server uses to pick the manager for a request.
   *
   * @return the resolver
   */
  public JwtIssuerAuthenticationManagerResolver resolver() {
    return new JwtIssuerAuthenticationManagerResolver(this::manager);
  }

  private AuthenticationManager manager(String issuer) {
    if (!issuers.contains(issuer)) {
      return null; // unknown issuer: the resolver answers "Invalid issuer"
    }
    return managers.computeIfAbsent(issuer, this::build);
  }

  private AuthenticationManager build(String issuer) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), this::checkAudience));
    JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
    return new ProviderManager(provider);
  }

  private OAuth2TokenValidatorResult checkAudience(Jwt token) {
    if (token.getAudience() != null && token.getAudience().contains(audience)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "The token is not meant for this application", null));
  }
}
