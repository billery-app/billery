package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The limits of open sign-up, over HTTP with small values: companies owned per user, companies
 * created per user, per source IP and for the whole system in 24 hours. A refused sign-up answers
 * with a message for the user and counts nothing.
 *
 * <p>The source is the client address the application sees; behind the reverse proxy that is the
 * address from {@code X-Forwarded-For} ({@code server.forward-headers-strategy=framework}), which
 * the proxy must overwrite. The test sends that header like the proxy would.
 */
class SignUpLimitsTest extends SaasApiTestBase {

  private static final int OWNED = 3;
  private static final int PER_USER = 2;
  private static final int PER_SOURCE = 2;
  private static final int PER_DAY = 5;

  @Autowired MeterRegistry meters;
  @Autowired io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheus;

  static {
    signUpLimits =
        java.util.Map.of(
            "billery.max-companies-per-user", OWNED,
            "billery.signups-per-user-per-day", PER_USER,
            "billery.signups-per-ip", PER_SOURCE,
            "billery.signups-per-day", PER_DAY);
  }

  /** The other test classes run with the limits off again. */
  @AfterAll
  static void limitsOff() {
    signUpLimits = java.util.Map.of();
  }

  /** Every test starts on a new day. */
  @BeforeEach
  void newDay() throws Exception {
    admin("DELETE FROM daily_quota");
  }

  private int sources;

  /** A source address of its own per call, unless a test names one. */
  private String newSource() {
    return "198.51.100." + (++sources);
  }

  private Reply signUp(String token, String company, String source) throws Exception {
    String origin = "http://localhost:" + port;
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/tenants"))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", origin)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", source)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + company + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), response.body());
  }

  private static String user(String name) {
    return token(idpA, name + "-" + System.nanoTime(), name + System.nanoTime() + "@example.com");
  }

  @Test
  void oneUserCreatesAtMostTheDailyNumberOfCompanies() throws Exception {
    String anna = user("anna");
    for (int i = 0; i < PER_USER; i++) {
      assertEquals(201, signUp(anna, "Anna " + i, newSource()).status());
    }

    Reply refused = signUp(anna, "Anna zu viel", newSource());
    assertEquals(429, refused.status(), refused.body());
    assertTrue(refused.json().get("message").asText().contains("morgen"), refused.body());
    assertEquals(201, signUp(user("bert"), "Bert GmbH", newSource()).status(), "others go on");
  }

  @Test
  void oneSourceWithFreshAccountsIsStopped() throws Exception {
    String office = "203.0.113.50";
    for (int i = 0; i < PER_SOURCE; i++) {
      assertEquals(201, signUp(user("acct" + i), "Firma " + i, office).status());
    }

    Reply refused = signUp(user("acct-new"), "Noch eine", office);
    assertEquals(429, refused.status(), refused.body());
    assertTrue(refused.json().get("message").asText().contains("Anschluss"), refused.body());
    assertEquals(201, signUp(user("elsewhere"), "Woanders", newSource()).status());
  }

  @Test
  void theSystemLimitIsTheEmergencyBrakeAndIsCountedForTheMonitoring() throws Exception {
    double before = refusals("system");
    for (int i = 0; i < PER_DAY; i++) {
      assertEquals(201, signUp(user("sys" + i), "System " + i, newSource()).status(), "#" + i);
    }

    Reply refused = signUp(user("sys-late"), "Zu spät", newSource());
    assertEquals(429, refused.status(), refused.body());
    assertEquals(before + 1, refusals("system"), "billery_quota_refused_total{scope=system}");
    // The alert rules (deploy/saas/monitoring/alerts.yml) query exactly this series.
    String scrape = prometheus.scrape();
    assertTrue(
        scrape.contains(
            "billery_quota_refused_total{application=\"billery\",quota=\"signup\",scope=\"system\"}"),
        scrape.lines().filter(l -> l.contains("quota")).toList().toString());
    assertTrue(
        scrape.contains(
            "billery_quota_refused_total{application=\"billery\",quota=\"invitation_mail\",scope=\"ip\"} 0.0"),
        "declared with 0 before the first refusal");
  }

  @Test
  void aRefusedSignUpCountsNothing() throws Exception {
    String carla = user("carla");
    String source = newSource();
    for (int i = 0; i < PER_USER; i++) {
      assertEquals(201, signUp(carla, "Carla " + i, source).status());
    }
    for (int i = 0; i < 5; i++) {
      assertEquals(429, signUp(carla, "Carla nochmal", newSource()).status());
    }

    // Carla's refused attempts came from other sources and used none of their limits, nor the
    // system's: PER_DAY - PER_USER sign-ups are still possible today.
    for (int i = 0; i < PER_DAY - PER_USER; i++) {
      assertEquals(201, signUp(user("dora" + i), "Dora " + i, newSource()).status(), "#" + i);
    }
  }

  @Test
  void oneUserOwnsAtMostTheAllowedNumberOfCompaniesAndClosingOneFreesASlot() throws Exception {
    String erik = user("erik");
    int first = 0;
    for (int i = 0; i < OWNED; i++) {
      if (i == PER_USER) {
        newDay();
      }
      Reply created = signUp(erik, "Erik " + i, newSource());
      assertEquals(201, created.status(), created.body());
      if (i == 0) {
        first = created.json().get("tenantId").asInt();
      }
    }
    newDay();

    Reply refused = signUp(erik, "Erik zu viel", newSource());
    assertEquals(409, refused.status(), refused.body());
    assertTrue(refused.json().get("message").asText().contains("bereits 3 Firmen"), refused.body());

    assertEquals(200, post("/api/tenant/close", erik, first, null).status());
    assertEquals(201, signUp(erik, "Erik neu", newSource()).status(), "a closed company frees one");
  }

  private double refusals(String scope) {
    var counter =
        meters.find("billery.quota.refused").tag("quota", "signup").tag("scope", scope).counter();
    return counter == null ? 0 : counter.count();
  }
}
