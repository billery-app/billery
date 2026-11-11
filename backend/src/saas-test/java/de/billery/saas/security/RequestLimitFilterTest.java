package de.billery.saas.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.saas.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What the API answers when a tenant exceeds its limits. */
class RequestLimitFilterTest {

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  private static MockHttpServletResponse call(
      RequestLimitFilter filter, String path, FilterChain chain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void aTenantOverItsRateGets429WithRetryAfterAndAMessage() throws Exception {
    RequestLimitFilter filter = new RequestLimitFilter(new RequestLimits(1, 3, 2, () -> 0L));
    AtomicInteger passed = new AtomicInteger();
    FilterChain chain = (req, res) -> passed.incrementAndGet();
    TenantContext.set(7, "u1");

    for (int i = 0; i < 3; i++) {
      assertEquals(200, call(filter, "/api/customers", chain).getStatus());
    }
    MockHttpServletResponse refused = call(filter, "/api/customers", chain);

    assertEquals(429, refused.getStatus());
    assertEquals("1", refused.getHeader("Retry-After"));
    assertTrue(
        refused.getContentAsString().contains("Zu viele Anfragen"), refused.getContentAsString());
    assertEquals(3, passed.get(), "the refused request never reached the application");

    TenantContext.set(8, "u1");
    assertEquals(200, call(filter, "/api/customers", chain).getStatus(), "another tenant goes on");
  }

  @Test
  void requestsWithoutASignedInUserAndOutsideTheApiAreNotCounted() throws Exception {
    RequestLimitFilter filter = new RequestLimitFilter(new RequestLimits(1, 1, 1, () -> 0L));
    FilterChain chain = (req, res) -> {};
    for (int i = 0; i < 5; i++) {
      assertEquals(200, call(filter, "/api/health", chain).getStatus());
    }
    TenantContext.set(7, "u1");
    for (int i = 0; i < 5; i++) {
      assertEquals(200, call(filter, "/assets/app.js", chain).getStatus());
    }
  }

  @Test
  void aThirdConcurrentPdfOfOneTenantIsRefusedOthersAreNot() throws Exception {
    RequestLimitFilter filter = new RequestLimitFilter(new RequestLimits(100, 100, 2, () -> 0L));
    CountDownLatch running = new CountDownLatch(2);
    CountDownLatch finish = new CountDownLatch(1);
    FilterChain slow =
        (req, res) -> {
          running.countDown();
          try {
            finish.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(() -> asTenant(7, () -> call(filter, "/api/invoices/1/pdf", slow)));
      Future<?> second =
          pool.submit(() -> asTenant(7, () -> call(filter, "/api/export/gobd", slow)));
      assertTrue(running.await(10, TimeUnit.SECONDS));

      TenantContext.set(7, "u1");
      MockHttpServletResponse third = call(filter, "/api/templates/preview", (req, res) -> {});
      assertEquals(429, third.getStatus());
      assertEquals("5", third.getHeader("Retry-After"));
      assertEquals(
          200,
          call(filter, "/api/customers", (req, res) -> {}).getStatus(),
          "cheap requests go on");
      TenantContext.set(8, "u2");
      assertEquals(
          200, call(filter, "/api/invoices/2/pdf", (req, res) -> {}).getStatus(), "other tenant");

      finish.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      TenantContext.set(7, "u1");
      assertEquals(
          200,
          call(filter, "/api/invoices/1/pdf", (req, res) -> {}).getStatus(),
          "slots are released");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theExpensivePathsAreRecognised() {
    for (String path :
        new String[] {
          "/api/invoices/12/pdf",
          "/api/invoices/12/xrechnung",
          "/api/templates/preview",
          "/api/export/gobd",
          "/api/documents/export/3",
          "/api/documents/import"
        }) {
      assertTrue(RequestLimitFilter.HEAVY.matcher(path).matches(), path);
    }
    for (String path :
        new String[] {"/api/invoices/12", "/api/invoices/12/xrechnung/check", "/api/customers"}) {
      assertTrue(!RequestLimitFilter.HEAVY.matcher(path).matches(), path);
    }
  }

  private interface Call {
    MockHttpServletResponse run() throws Exception;
  }

  private static MockHttpServletResponse asTenant(int tenant, Call call) throws Exception {
    TenantContext.set(tenant, "u" + tenant);
    try {
      return call.run();
    } finally {
      TenantContext.clear();
    }
  }
}
