package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Choosing a restore waits for the writes already under way. */
class WriteGateTest {

  @Test
  void waitsUntilTheWritesInProgressHaveFinished() throws Exception {
    WriteGate gate = new WriteGate();
    assertTrue(gate.awaitIdle(Duration.ZERO), "nothing in progress");

    gate.enter();
    assertFalse(gate.awaitIdle(Duration.ofMillis(50)), "a write is still running");

    CountDownLatch waiting = new CountDownLatch(1);
    Thread writer =
        new Thread(
            () -> {
              try {
                waiting.await();
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              gate.leave();
            });
    writer.start();
    waiting.countDown();
    assertTrue(gate.awaitIdle(Duration.ofSeconds(5)), "returns once the write has left");
    writer.join();
  }
}
