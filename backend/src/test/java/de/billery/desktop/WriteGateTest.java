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

  @Test
  void aBackupPausesNewWritesAndWaitsForThoseUnderWay() throws Exception {
    WriteGate gate = new WriteGate();
    gate.enter(); // a write under way
    assertFalse(gate.beginExclusive(Duration.ofMillis(50)), "waits for it, then gives up");
    gate.leave();

    assertTrue(gate.beginExclusive(Duration.ofSeconds(1)));
    boolean[] entered = {false};
    Thread write =
        new Thread(
            () -> {
              gate.enter(); // paused while the backup copies
              entered[0] = true;
              gate.leave();
            });
    write.start();
    Thread.sleep(150);
    assertFalse(entered[0], "a new write waits for the backup");
    gate.endExclusive();
    write.join(5000);
    assertTrue(entered[0], "and continues afterwards");
  }
}
