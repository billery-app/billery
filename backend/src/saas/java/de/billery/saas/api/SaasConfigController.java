package de.billery.saas.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sign-in settings of the single-page app (public: they are needed before anyone is signed in,
 * and contain no secret: the app is a public OIDC client using the authorization-code flow with
 * PKCE).
 */
@RestController
@Profile("saas")
public class SaasConfigController {

  private final String issuer;
  private final String clientId;
  private final String scope;
  private final String audience;

  /**
   * Reads the settings.
   *
   * @param oidcIssuer the provider the app signs in at; defaults to the first trusted issuer
   * @param trustedIssuers the issuers the backend accepts tokens from
   * @param clientId the app's client id at the provider (required)
   * @param scope the requested scopes; {@code offline_access} lets the provider issue refresh
   *     tokens for the silent renewal
   * @param audience the audience the access token is requested for (always set: the backend
   *     requires it)
   */
  public SaasConfigController(
      @Value("${billery.oidc.issuer:}") String oidcIssuer,
      @Value("${billery.security.issuers:}") String trustedIssuers,
      @Value("${billery.oidc.client-id}") String clientId,
      @Value("${billery.oidc.scope:openid profile email offline_access}") String scope,
      @Value("${billery.security.audience:}") String audience) {
    this.issuer =
        oidcIssuer.isBlank()
            ? Arrays.stream(trustedIssuers.split(",")).map(String::trim).findFirst().orElse("")
            : oidcIssuer.trim();
    this.clientId = clientId;
    this.scope = scope;
    this.audience = audience.trim();
  }

  /**
   * Returns the operating mode and the sign-in settings.
   *
   * @return mode {@code saas} plus issuer, client id, scope and audience
   */
  @GetMapping("/api/config")
  public Map<String, Object> config() {
    Map<String, Object> oidc = new LinkedHashMap<>();
    oidc.put("issuer", issuer);
    oidc.put("clientId", clientId);
    oidc.put("scope", scope);
    oidc.put("audience", audience);
    return Map.of("mode", "saas", "oidc", oidc);
  }
}
