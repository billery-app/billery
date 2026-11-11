package de.billery.saas.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The token bucket and the slots for expensive requests, with a clock the test moves. */
class RequestLimitsTest {

  private final AtomicLong nanos = new AtomicLong();

  private RequestLimits limits(double perSecond, int burst, int heavy) {
    return new RequestLimits(perSecond, burst, heavy, nanos::get);
  }

  @Test
  void aBurstPassesThenTheRateAppliesPerKey() {
    RequestLimits limits = limits(2, 5, 2);

    for (int i = 0; i < 5; i++) {
      assertEquals(0, limits.tryRequest("tenant:1"), "request " + i);
    }
    assertEquals(1, limits.tryRequest("tenant:1"), "the sixth waits a second");
    assertEquals(0, limits.tryRequest("tenant:2"), "another tenant has its own bucket");

    nanos.addAndGet(500_000_000L);
    assertEquals(0, limits.tryRequest("tenant:1"), "half a second refills one at 2 per second");
    assertTrue(limits.tryRequest("tenant:1") > 0);

    nanos.addAndGet(60_000_000_000L);
    for (int i = 0; i < 5; i++) {
      assertEquals(0, limits.tryRequest("tenant:1"), "a minute later the burst is back: " + i);
    }
  }

  @Test
  void theWaitIsTheTimeToTheNextToken() {
    RequestLimits limits = limits(0.1, 1, 2);
    assertEquals(0, limits.tryRequest("k"));
    assertEquals(10, limits.tryRequest("k"), "one request per ten seconds");
  }

  @Test
  void expensiveRequestsHaveTwoSlotsPerTenant() {
    RequestLimits limits = limits(10, 10, 2);

    assertTrue(limits.tryHeavy("tenant:1"));
    assertTrue(limits.tryHeavy("tenant:1"));
    assertFalse(limits.tryHeavy("tenant:1"));
    assertTrue(limits.tryHeavy("tenant:2"), "another tenant is not affected");

    limits.releaseHeavy("tenant:1");
    assertTrue(limits.tryHeavy("tenant:1"), "a released slot is free again");
  }

  @Test
  void slotsOfFinishedRequestsTakeNoMemory() {
    RequestLimits limits = limits(10, 10, 2);

    for (int tenant = 0; tenant < 1_000; tenant++) {
      assertTrue(limits.tryHeavy("tenant:" + tenant));
      assertTrue(limits.tryHeavy("tenant:" + tenant));
    }
    assertEquals(1_000, limits.heavyCount());
    for (int tenant = 0; tenant < 1_000; tenant++) {
      limits.releaseHeavy("tenant:" + tenant);
    }
    assertEquals(1_000, limits.heavyCount(), "one request per tenant still runs");
    assertTrue(limits.tryHeavy("tenant:7"), "and its other slot is free");
    limits.releaseHeavy("tenant:7");
    for (int tenant = 0; tenant < 1_000; tenant++) {
      limits.releaseHeavy("tenant:" + tenant);
    }
    assertEquals(0, limits.heavyCount(), "no entry outlives its last request");

    limits.releaseHeavy("tenant:1");
    assertTrue(limits.tryHeavy("tenant:1"));
    assertTrue(limits.tryHeavy("tenant:1"));
    assertFalse(limits.tryHeavy("tenant:1"), "a release too many adds no slot");
  }

  @Test
  void theSlotsHoldUnderConcurrentRequests() throws InterruptedException {
    RequestLimits limits = limits(10, 10, 2);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    AtomicInteger granted = new AtomicInteger();
    Thread[] threads = new Thread[8];
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          new Thread(
              () -> {
                for (int n = 0; n < 20_000; n++) {
                  if (limits.tryHeavy("tenant:1")) {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    granted.incrementAndGet();
                    running.decrementAndGet();
                    limits.releaseHeavy("tenant:1");
                  }
                }
              });
      threads[i].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertTrue(granted.get() > 0);
    assertTrue(most.get() <= 2, "never more than two at once, but " + most.get());
    assertEquals(0, limits.heavyCount(), "and nothing is left behind");
  }

  @Test
  void idleBucketsAreDroppedAndBusyOnesKeepTheirState() {
    RequestLimits limits = limits(1, 5, 2);
    for (int i = 0; i < 5; i++) {
      assertEquals(0, limits.tryRequest("tenant:busy"));
    }
    for (int tenant = 0; tenant <= RequestLimits.MAX_KEYS; tenant++) {
      assertEquals(0, limits.tryRequest("tenant:" + tenant));
    }
    assertEquals(RequestLimits.MAX_KEYS + 2, limits.bucketCount(), "nothing is full yet");

    nanos.addAndGet(1_500_000_000L);
    assertEquals(0, limits.tryRequest("tenant:busy"), "1.5 tokens came back");

    assertEquals(1, limits.bucketCount(), "the buckets that were full again are gone");
    assertTrue(limits.tryRequest("tenant:busy") > 0, "the busy tenant did not get a new burst");
  }

  @Test
  void theSweepRunsAtMostOncePerInterval() {
    // 10 per second and a burst of 5: a bucket is full again after half a sweep interval.
    RequestLimits limits = limits(10, 5, 2);
    long half = RequestLimits.SWEEP_INTERVAL_NANOS / 2;
    for (int tenant = 0; tenant <= RequestLimits.MAX_KEYS; tenant++) {
      limits.tryRequest("tenant:" + tenant);
    }
    nanos.addAndGet(half);
    limits.tryRequest("tenant:0");
    assertEquals(1, limits.bucketCount(), "the first sweep drops the full buckets");

    for (int tenant = 0; tenant <= RequestLimits.MAX_KEYS; tenant++) {
      limits.tryRequest("tenant:" + tenant);
    }
    nanos.addAndGet(RequestLimits.SWEEP_INTERVAL_NANOS - 1);
    limits.tryRequest("tenant:0");
    assertEquals(
        RequestLimits.MAX_KEYS + 1,
        limits.bucketCount(),
        "full buckets stay until the interval is over: the map is not walked per request");

    nanos.addAndGet(1);
    limits.tryRequest("tenant:0");
    assertEquals(1, limits.bucketCount(), "the next sweep is due after the interval");
  }

  @Test
  void theBucketsNeverGrowBeyondTheHardLimit() {
    RequestLimits limits = limits(0.001, 5, 2);
    int most = 0;
    for (int tenant = 0; tenant < RequestLimits.HARD_LIMIT * 2 + 10; tenant++) {
      assertEquals(0, limits.tryRequest("tenant:" + tenant), "a new tenant is never refused");
      most = Math.max(most, limits.bucketCount());
    }
    assertTrue(most <= RequestLimits.HARD_LIMIT + 1, "most buckets at once: " + most);
  }

  @Test
  void zeroSwitchesTheLimitsOff() {
    RequestLimits limits = limits(0, 1, 0);
    for (int i = 0; i < 1_000; i++) {
      assertEquals(0, limits.tryRequest("k"));
      assertTrue(limits.tryHeavy("k"));
    }
  }
}
