package de.billery.desktop;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Counts the changing API requests in progress. Choosing a restore first stages it (from then on
 * {@link RestorePendingFilter} refuses new writes), then waits here until the writes already under
 * way have finished, and only then counts what the restore takes out. A request enters the gate
 * before it checks for a staged restore, so none can slip between the two.
 */
@Component
@Profile("desktop")
public class WriteGate {

  private final AtomicInteger inProgress = new AtomicInteger();

  /** A changing request starts. */
  public void enter() {
    inProgress.incrementAndGet();
  }

  /** A changing request has finished (committed or not). */
  public void leave() {
    synchronized (this) {
      inProgress.decrementAndGet();
      notifyAll();
    }
  }

  /**
   * Waits until no changing request is in progress.
   *
   * @param timeout how long to wait at most
   * @return {@code true} if none is in progress, {@code false} after the timeout
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (this) {
      while (inProgress.get() > 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return false;
        }
        wait(Math.max(1, left / 1_000_000));
      }
    }
    return true;
  }
}
