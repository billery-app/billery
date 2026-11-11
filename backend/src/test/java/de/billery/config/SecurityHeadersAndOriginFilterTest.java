package de.billery.config;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

class SecurityHeadersAndOriginFilterTest {

  private static final String APP_ORIGIN = "https://billery.example";

  private final SecurityHeadersAndOriginFilter filter = new SecurityHeadersAndOriginFilter();

  @Test
  void blocksCrossSiteFormAndSimplePostRequests() throws Exception {
    for (String contentType : new String[] {"application/x-www-form-urlencoded", "text/plain"}) {
      MockHttpServletRequest request = request("POST", "/api/customers");
      request.addHeader("Origin", "https://attacker.example");
      request.setContentType(contentType);
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertEquals(403, response.getStatus());
      assertNull(chain.getRequest());
      assertTrue(response.getContentAsString().contains("Untrusted or missing request origin"));
    }
  }

  @Test
  void blocksCrossOriginRequestsForEveryUnsafeMethod() throws Exception {
    for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
      MockHttpServletRequest request = request(method, "/api/customers/7");
      request.addHeader("Origin", "https://attacker.example");
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertEquals(403, response.getStatus(), method);
      assertNull(chain.getRequest(), method);
    }
  }

  @Test
  void doesNotFallBackToRefererWhenOriginIsPresentButUntrusted() throws Exception {
    MockHttpServletRequest request = request("POST", "/api/customers");
    request.addHeader("Origin", "https://attacker.example");
    request.addHeader("Referer", APP_ORIGIN + "/customers");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
  }

  @Test
  void allowsSameOriginWritesAndRefererFallback() throws Exception {
    MockHttpServletRequest request = request("PATCH", "/api/invoices/7/status");
    request.addHeader("Origin", APP_ORIGIN);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertNotNull(chain.getRequest());
    assertEquals(200, response.getStatus());
    assertEquals("max-age=31536000", response.getHeader("Strict-Transport-Security"));

    MockHttpServletRequest refererRequest = request("DELETE", "/api/customers/7");
    refererRequest.addHeader("Referer", APP_ORIGIN + "/customers/7");
    MockFilterChain refererChain = new MockFilterChain();
    filter.doFilter(refererRequest, new MockHttpServletResponse(), refererChain);
    assertNotNull(refererChain.getRequest());
  }

  @Test
  void rejectsWritesWithMissingOriginAndReferer() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request("POST", "/api/customers"), response, new MockFilterChain());

    assertEquals(403, response.getStatus());
  }

  @Test
  void permitsExplicitlyConfiguredCorsOrigin() throws Exception {
    SecurityHeadersAndOriginFilter configuredFilter = new SecurityHeadersAndOriginFilter();
    configuredFilter.setAllowedOrigins("https://spa.example, https://other.example/");
    MockHttpServletRequest request = request("POST", "/api/customers");
    request.addHeader("Origin", "https://spa.example");
    MockFilterChain chain = new MockFilterChain();

    configuredFilter.doFilter(request, new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());
  }

  @Test
  void forwardedHttpsOriginIsTrustedAndGetsHsts() throws Exception {
    MockHttpServletRequest request = request("POST", "/api/customers");
    request.setScheme("http");
    request.setServerName("backend");
    request.setServerPort(8080);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "billery.example");
    request.addHeader("X-Forwarded-Port", "443");
    request.addHeader("Origin", APP_ORIGIN);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    ForwardedHeaderFilter forwardedHeaderFilter = new ForwardedHeaderFilter();

    FilterChain applicationChain =
        (forwardedRequest, forwardedResponse) ->
            filter.doFilter(forwardedRequest, forwardedResponse, chain);
    forwardedHeaderFilter.doFilter(request, response, applicationChain);

    assertNotNull(chain.getRequest());
    assertEquals("max-age=31536000", response.getHeader("Strict-Transport-Security"));
  }

  @Test
  void appliesCspNosniffAndFrameProtectionToResponses() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request("GET", "/api/health"), response, new MockFilterChain());

    assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    assertEquals("DENY", response.getHeader("X-Frame-Options"));
    String csp = response.getHeader("Content-Security-Policy");
    assertTrue(csp.contains("script-src 'self'"));
    assertTrue(csp.contains("style-src 'self' 'unsafe-inline'"));
    assertTrue(csp.contains("frame-ancestors 'none'"));
    // The template editor's PDF preview is a blob: URL in an iframe (blocked before, G1).
    assertTrue(csp.matches(".*frame-src [^;]*blob:.*"), csp);
    assertTrue(csp.contains("object-src 'none'"), "frames, not plugins, are allowed blob:");
    assertFalse(csp.contains("unsafe-eval"));
  }

  /**
   * The silent token renewal loads the sign-in callback in a hidden iframe of the same origin: only
   * that page may be framed, only by this origin, and the rest of the policy is unchanged.
   */
  @Test
  void letsOnlyTheSameOriginFrameTheSignInCallback() throws Exception {
    MockHttpServletResponse callback = new MockHttpServletResponse();
    filter.doFilter(request("GET", "/auth/callback"), callback, new MockFilterChain());

    assertEquals("SAMEORIGIN", callback.getHeader("X-Frame-Options"));
    String csp = callback.getHeader("Content-Security-Policy");
    assertTrue(csp.endsWith("frame-ancestors 'self'"), csp);
    assertEquals(
        SecurityHeadersAndOriginFilter.CONTENT_SECURITY_POLICY.replace(
            "frame-ancestors 'none'", "frame-ancestors 'self'"),
        csp);

    for (String path : new String[] {"/", "/invoices", "/auth/callback/x", "/auth", "/api/me"}) {
      MockHttpServletResponse other = new MockHttpServletResponse();
      filter.doFilter(request("GET", path), other, new MockFilterChain());
      assertEquals("DENY", other.getHeader("X-Frame-Options"), path);
      assertTrue(
          other.getHeader("Content-Security-Policy").endsWith("frame-ancestors 'none'"), path);
    }
  }

  @Test
  void omitsHstsForPlainHttpRequests() throws Exception {
    MockHttpServletRequest request = request("GET", "/api/health");
    request.setScheme("http");
    request.setServerPort(80);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertNull(response.getHeader("Strict-Transport-Security"));
  }

  @Test
  void blocksEncodedAndPathParameterVariantsOfApiPaths() throws Exception {
    for (String path :
        new String[] {"/%61pi/customers", "/api;x=1/customers", "/api/customers;jsessionid=1"}) {
      MockHttpServletRequest request = request("POST", path);
      request.addHeader("Origin", "https://attacker.example");
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertEquals(403, response.getStatus(), path);
      assertNull(chain.getRequest(), path);
    }
  }

  @Test
  void blocksEncodedApiPathsWithoutAnyOriginOrReferer() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request("POST", "/%61pi/customers"), response, chain);

    assertEquals(403, response.getStatus());
    assertNull(chain.getRequest());
  }

  @Test
  void desktopShutdownEndpointRemainsTokenProtectedWithoutBrowserOrigin() throws Exception {
    MockHttpServletRequest request = request("POST", "/api/desktop/shutdown");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());
  }

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod(method);
    request.setRequestURI(path);
    request.setScheme("https");
    request.setServerName("billery.example");
    request.setServerPort(443);
    return request;
  }
}
