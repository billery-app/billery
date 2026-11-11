package de.billery.saas;

import de.billery.saas.identity.IdentityRepository;
import de.billery.saas.identity.IdentityService;
import de.billery.saas.security.RequestLimitFilter;
import de.billery.saas.security.RequestLimits;
import de.billery.saas.security.TenantContextFilter;
import de.billery.saas.security.TrustedIssuers;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security of the SaaS profile: an OAuth2 resource server that accepts bearer tokens of the
 * configured issuers ({@code billery.security.issuers}, comma-separated; optionally {@code
 * billery.security.audience}), followed by the {@link TenantContextFilter}.
 *
 * <p>Requests are stateless and carry no cookie, so there is no session to protect against CSRF.
 * Open: the health endpoint and the single-page app's static files (they must load before anyone
 * can sign in) and {@code /api/config} (the app reads its sign-in settings there). Everything else
 * under {@code /api} needs a token. {@code /uploads/<tenant>/<file>} is open because an {@code
 * <img>} tag cannot authenticate: the controller serves a file only for its signed URL. The API
 * documentation stays closed.
 */
@Configuration
@Profile("saas")
public class SaasSecurityConfig {

  /**
   * The accepted identity providers.
   *
   * @param issuers comma-separated issuer URLs
   * @param audience the required token audience; the start fails without it
   * @return the registry
   */
  @Bean
  TrustedIssuers trustedIssuers(
      @Value("${billery.security.issuers:}") String issuers,
      @Value("${billery.security.audience:}") String audience) {
    return new TrustedIssuers(
        Arrays.stream(issuers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
        audience);
  }

  /**
   * The filter chain for all requests.
   *
   * @param http the builder
   * @param issuers the accepted issuers
   * @param identities maps tokens to users
   * @param repository reads memberships
   * @param requestsPerSecond API requests per second and tenant ({@code billery.limits.*}); 0 = off
   * @param burst the most requests a tenant may make at once
   * @param heavyPerTenant concurrent PDF, preview, XRechnung, export and import requests per tenant
   * @return the chain
   * @throws Exception if the configuration is invalid
   */
  @Bean
  SecurityFilterChain saasFilterChain(
      HttpSecurity http,
      TrustedIssuers issuers,
      IdentityService identities,
      IdentityRepository repository,
      @Value("${billery.limits.requests-per-second:20}") double requestsPerSecond,
      @Value("${billery.limits.burst:200}") int burst,
      @Value("${billery.limits.heavy-per-tenant:2}") int heavyPerTenant)
      throws Exception {
    TenantContextFilter tenantFilter = new TenantContextFilter(identities, repository);
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .oauth2ResourceServer(o -> o.authenticationManagerResolver(issuers.resolver()))
        .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
        // Per-tenant request limits; needs the tenant the filter above established.
        .addFilterAfter(
            new RequestLimitFilter(
                new RequestLimits(requestsPerSecond, burst, heavyPerTenant, System::nanoTime)),
            TenantContextFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/health", "/api/health/ready", "/api/config")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    // Per-tenant files: the controller checks the signature of the URL.
                    .requestMatchers("/uploads/**")
                    .permitAll()
                    .requestMatchers("/v3/**", "/swagger-ui/**", "/swagger-ui.html")
                    .denyAll()
                    .anyRequest()
                    .permitAll())
        .build();
  }
}
