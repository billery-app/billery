package de.billery.saas;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * A minimal OpenID provider for tests: OIDC discovery, a JWKS endpoint and tokens signed with its
 * own key, so the backend validates signature, issuer, audience and expiry for real. Every instance
 * is its own issuer, which is how tests model "two identity providers".
 */
public final class MockIdp implements AutoCloseable {

  private final HttpServer server;
  private final RSAKey key;
  private final String issuer;
  private final String jwksPath;

  /** Starts a provider on a free local port; the issuer is the bare origin. */
  public MockIdp() {
    this("");
  }

  /**
   * Starts a provider whose issuer has a path, like authentik's {@code
   * https://host/application/o/<slug>/} (note the trailing slash): discovery and keys live below
   * that path, and the issuer in the tokens is the URL with exactly that slash.
   *
   * @param path the path after the origin, with leading and trailing slash, or "" for none
   */
  public MockIdp(String path) {
    try {
      key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException | JOSEException e) {
      throw new IllegalStateException("Cannot start the mock identity provider", e);
    }
    issuer = "http://127.0.0.1:" + server.getAddress().getPort() + path;
    jwksPath = path.isEmpty() ? "/jwks" : path + "jwks/";
    serve((path.isEmpty() ? "/" : path) + ".well-known/openid-configuration", discovery());
    serve(jwksPath, new JWKSet(key.toPublicJWK()).toString());
    server.start();
  }

  private String discovery() {
    return "{\"issuer\":\""
        + issuer
        + "\",\"jwks_uri\":\""
        + issuerOrigin()
        + jwksPath
        + "\",\"subject_types_supported\":[\"public\"],"
        + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
  }

  private String issuerOrigin() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void serve(String path, String body) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  /**
   * The issuer URL this provider puts into its tokens.
   *
   * @return the issuer
   */
  public String issuer() {
    return issuer;
  }

  /**
   * A signed token.
   *
   * @param subject the user's id at this provider
   * @param email the e-mail claim, or null for none
   * @param emailVerified the {@code email_verified} claim
   * @param audience the audience claim
   * @param validFor how long the token is valid from now; negative gives an expired token
   * @return the compact JWT
   */
  public String token(
      String subject, String email, boolean emailVerified, String audience, Duration validFor) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(now.minus(Duration.ofMinutes(5))))
            .expirationTime(Date.from(now.plus(validFor)))
            .claim("email_verified", emailVerified)
            .claim("name", "Test " + subject);
    if (email != null) {
      claims.claim("email", email);
    }
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              claims.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
