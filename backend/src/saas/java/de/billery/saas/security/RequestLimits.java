package de.billery.saas.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-tenant request limits of one backend instance: a token bucket for all API requests and a
 * small number of concurrent expensive requests (PDF, preview, XRechnung, export, import). They
 * keep one tenant, or a loop in a script, from taking the instance's CPU and memory from the
 * others. With several instances each one counts for itself, so the effective limit grows with
 * them; that is enough for this purpose.
 *
 * <p>The memory is bounded, because users can create tenants: a full bucket says the same as no
 * bucket and is dropped, at most once per {@link #SWEEP_INTERVAL_NANOS}, once more than {@link
 * #MAX_KEYS} are kept; above {@link #HARD_LIMIT} all buckets are dropped (everyone starts with a
 * full burst again, which is better than refusing tenants or growing without end). Slots of
 * expensive requests are only kept while such a request runs.
 */
public final class RequestLimits {

  /** Buckets kept before idle, full ones are dropped. */
  static final int MAX_KEYS = 10_000;

  /** Buckets kept at most: more than this many keys in use at once drops them all. */
  static final int HARD_LIMIT = 100_000;

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  /** The least time between two sweeps, so a busy instance does not walk the map per request. */
  static final long SWEEP_INTERVAL_NANOS = NANOS_PER_SECOND;

  private final double perSecond;
  private final double burst;
  private final int heavyPerTenant;
  private final LongSupplier clock;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /** The expensive requests running per key; a key without one has no entry. */
  private final Map<String, Integer> heavy = new ConcurrentHashMap<>();

  private final AtomicLong lastSweep;

  /**
   * @param perSecond requests per second refilled per key; 0 switches the rate limit off
   * @param burst the most requests a key may make at once
   * @param heavyPerTenant concurrent expensive requests per key; 0 switches the limit off
   * @param clock nanoseconds, monotonic ({@code System::nanoTime})
   */
  public RequestLimits(double perSecond, int burst, int heavyPerTenant, LongSupplier clock) {
    this.perSecond = perSecond;
    this.burst = Math.max(1, burst);
    this.heavyPerTenant = heavyPerTenant;
    this.clock = clock;
    this.lastSweep = new AtomicLong(clock.getAsLong() - SWEEP_INTERVAL_NANOS);
  }

  /**
   * Takes one request from the key's bucket.
   *
   * @param key the tenant (or the user, for requests without a tenant)
   * @return 0 if the request may run, otherwise the seconds until it may (at least 1)
   */
  public long tryRequest(String key) {
    if (perSecond <= 0) {
      return 0;
    }
    long now = clock.getAsLong();
    sweep(now);
    return buckets.computeIfAbsent(key, k -> new Bucket(now)).take(now);
  }

  /**
   * Keeps the buckets bounded: drops the full ones (they hold no information) when there are many,
   * at most once per interval, and all of them above the hard limit.
   */
  private void sweep(long now) {
    if (buckets.size() <= MAX_KEYS) {
      return;
    }
    long last = lastSweep.get();
    if (now - last >= SWEEP_INTERVAL_NANOS && lastSweep.compareAndSet(last, now)) {
      buckets.values().removeIf(bucket -> bucket.isFull(now));
    }
    if (buckets.size() > HARD_LIMIT) {
      buckets.clear();
    }
  }

  /**
   * Reserves a slot for an expensive request.
   *
   * @param key the tenant
   * @return true if the request may run; then {@link #releaseHeavy} must follow
   */
  public boolean tryHeavy(String key) {
    if (heavyPerTenant <= 0) {
      return true;
    }
    boolean[] granted = new boolean[1];
    heavy.compute(
        key,
        (k, running) -> {
          int used = running == null ? 0 : running;
          if (used >= heavyPerTenant) {
            return running;
          }
          granted[0] = true;
          return used + 1;
        });
    return granted[0];
  }

  /**
   * Gives a slot back. The key's entry goes with its last slot, so only keys with a running
   * expensive request take memory.
   *
   * @param key the tenant
   */
  public void releaseHeavy(String key) {
    if (heavyPerTenant > 0) {
      heavy.computeIfPresent(key, (k, running) -> running <= 1 ? null : running - 1);
    }
  }

  /**
   * @return the keys with a bucket (for tests)
   */
  int bucketCount() {
    return buckets.size();
  }

  /**
   * @return the keys with a running expensive request (for tests)
   */
  int heavyCount() {
    return heavy.size();
  }

  /** A token bucket; synchronized per key, so keys do not wait for each other. */
  private final class Bucket {
    private double tokens = burst;
    private long updated;

    Bucket(long now) {
      this.updated = now;
    }

    synchronized long take(long now) {
      refill(now);
      if (tokens >= 1) {
        tokens -= 1;
        return 0;
      }
      return Math.max(1, (long) Math.ceil((1 - tokens) / perSecond));
    }

    synchronized boolean isFull(long now) {
      refill(now);
      return tokens >= burst;
    }

    private void refill(long now) {
      tokens = Math.min(burst, tokens + (now - updated) * perSecond / NANOS_PER_SECOND);
      updated = now;
    }
  }
}
