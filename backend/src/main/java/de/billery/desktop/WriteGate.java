package de.billery.desktop;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Counts the changing API requests in progress. Choosing a restore first stages it (from then on
 * {@link RestorePendingFilter} refuses new writes), then waits here until the writes already under
 * way have finished, and only then counts what the restore takes out. A request enters the gate
 * before it checks for a staged restore, so none can slip between the two. A backup pauses new
 * requests while it copies database and uploaded files ({@link #beginExclusive}).
 */
@Component
@Profile("desktop")
public class WriteGate {

  private int inProgress;
  private boolean exclusive;

  /** A changing request starts; it waits while a backup takes its snapshot. */
  public synchronized void enter() {
    while (exclusive) {
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    inProgress++;
  }

  /** A changing request has finished (committed or not). */
  public synchronized void leave() {
    inProgress = Math.max(0, inProgress - 1);
    notifyAll();
  }

  /**
   * Waits until no changing request is in progress.
   *
   * @param timeout how long to wait at most
   * @return {@code true} if none is in progress, {@code false} after the timeout
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (inProgress > 0) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      wait(Math.max(1, left / 1_000_000));
    }
    return true;
  }

  /**
   * Pauses new changing requests and waits for those under way, so a backup copies database and
   * uploaded files as one state. Always followed by {@link #endExclusive()}.
   *
   * @param timeout how long to wait for the requests under way
   * @return {@code true} once nothing is written; {@code false} after the timeout (then the pause
   *     is lifted again)
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public synchronized boolean beginExclusive(Duration timeout) throws InterruptedException {
    exclusive = true;
    if (!awaitIdle(timeout)) {
      endExclusive();
      return false;
    }
    return true;
  }

  /** Lets the paused requests continue. */
  public synchronized void endExclusive() {
    exclusive = false;
    notifyAll();
  }
}
