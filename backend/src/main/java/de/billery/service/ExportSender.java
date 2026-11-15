package de.billery.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Carries the bytes of one export to the client on a thread of its own, so that a client that reads
 * slowly (or not at all) blocks only that thread.
 *
 * <p>The export itself runs on the request thread, because its database snapshot (and, in SaaS, the
 * tenant of its connection) is bound to that thread. If it wrote to the response directly, a
 * stalled client would hold the snapshot, a pooled connection and the export slot for as long as
 * the server's write lets it, past every deadline. Here the producer only hands chunks to a small
 * bounded queue and waits at most until the deadline; the sender thread does the blocking writes.
 * At most {@link #IN_FLIGHT_BYTES} are held between the two, so memory stays bounded as well.
 *
 * <p>The deadline is enforced on both sides: the producer gives up handing over chunks, the sender
 * drops everything still queued. A write the client never completes is the only thing left that the
 * deadline cannot cut short; it ends with the servlet container's write timeout and holds only the
 * sender and the request thread, no database resources and no export slot (the export closes those
 * before {@link #close()} waits for the sender).
 *
 * <p>Use from one producer thread: {@code write} / {@code flush}, then either {@link #finish()}
 * (everything sent) or just {@link #close()} (sends what was handed over before a failure, unless
 * the deadline passed or the client failed). {@code close} must always be called: only after it has
 * returned is the sender done with the client's stream.
 */
final class ExportSender extends OutputStream {

  /** Size of one chunk handed to the sender. */
  static final int CHUNK_BYTES = 64 * 1024;

  /** Chunks that may wait for the sender. */
  private static final int QUEUE_CHUNKS = 4;

  /**
   * Most bytes produced but not yet written to the client: the queue, the chunk the sender is
   * writing and the chunk being filled.
   */
  static final int IN_FLIGHT_BYTES = (QUEUE_CHUNKS + 2) * CHUNK_BYTES;

  /** How often a waiting producer looks whether the sender failed. */
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

  /** Marks the end of the stream in the queue (compared by identity). */
  private static final byte[] END = new byte[0];

  private final OutputStream client;
  private final long deadline;
  private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
  private final CountDownLatch done = new CountDownLatch(1);
  private final Thread sender;

  private byte[] buffer = new byte[CHUNK_BYTES];
  private int filled;
  private boolean ended;
  private boolean closed;

  /** Set when nothing more may reach the client (deadline passed or the client failed). */
  private volatile boolean dropping;

  /** Why writing to the client failed, if it did. */
  private volatile IOException failure;

  /**
   * Starts the sender.
   *
   * @param client the response stream; written only by the sender thread, never closed
   * @param deadline {@link System#nanoTime()} after which nothing more is sent
   */
  ExportSender(OutputStream client, long deadline) {
    super();
    this.client = client;
    this.deadline = deadline;
    this.sender = Thread.ofVirtual().name("gobd-export-sender").start(this::send);
  }

  /** The sender thread: writes chunk after chunk until the end marker. */
  private void send() {
    try {
      while (true) {
        byte[] chunk = queue.take();
        if (chunk == END) {
          break;
        }
        if (System.nanoTime() - deadline > 0) {
          dropping = true;
        }
        if (!dropping) {
          client.write(chunk);
        }
      }
      if (!dropping) {
        client.flush();
      }
    } catch (IOException e) {
      failure = e;
      dropping = true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      dropping = true;
    } catch (RuntimeException e) {
      failure = new IOException("Writing the export to the client failed", e);
      dropping = true;
    } finally {
      queue.clear(); // a producer waiting for room gets it and then sees the failure
      done.countDown();
    }
  }

  @Override
  public void write(int b) throws IOException {
    write(new byte[] {(byte) b}, 0, 1);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (ended) {
      throw new IOException("The export stream has already ended");
    }
    int from = off;
    int left = len;
    while (left > 0) {
      int n = Math.min(left, CHUNK_BYTES - filled);
      System.arraycopy(b, from, buffer, filled, n);
      filled += n;
      from += n;
      left -= n;
      if (filled == CHUNK_BYTES) {
        handOver(buffer);
        buffer = new byte[CHUNK_BYTES];
        filled = 0;
      }
    }
  }

  /** Hands the bytes written so far to the sender (a partial chunk). */
  @Override
  public void flush() throws IOException {
    if (!ended && filled > 0) {
      handOver(Arrays.copyOf(buffer, filled));
      filled = 0;
    }
  }

  /**
   * Hands everything over and waits until the client has it all, at most until the deadline.
   *
   * @throws IOException if the client failed
   * @throws ExportLimitException if the deadline passed first (the rest is dropped)
   */
  void finish() throws IOException {
    flush();
    handOver(END);
    ended = true;
    try {
      while (!done.await(Math.min(remaining(), POLL_NANOS), TimeUnit.NANOSECONDS)) {
        if (remaining() <= 0) {
          throw tooLate();
        }
      }
    } catch (InterruptedException e) {
      throw interrupted(e);
    }
    checkClient();
  }

  /**
   * Ends the stream and waits for the sender. Unless {@link #finish()} ran, the bytes handed over
   * so far are still sent (the caller made sure they are not a complete archive), unless the
   * deadline passed or the client failed. Never closes the client's stream.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (!ended) {
      ended = true;
      try {
        if (filled > 0) {
          handOver(Arrays.copyOf(buffer, filled));
        }
      } catch (IOException | RuntimeException e) {
        dropping = true; // the deadline passed or the client failed: nothing more goes out
      }
      endSender();
    }
    try {
      sender.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Puts the end marker into the queue, making room if the rest is dropped anyway. */
  private void endSender() {
    try {
      while (done.getCount() > 0) {
        if (dropping || remaining() <= 0) {
          dropping = true;
          queue.clear();
        }
        if (queue.offer(END, POLL_NANOS, TimeUnit.NANOSECONDS)) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      dropping = true;
      sender.interrupt();
    }
  }

  /** Waits for room in the queue, at most until the deadline; gives up if the sender failed. */
  private void handOver(byte[] chunk) throws IOException {
    try {
      while (true) {
        checkClient();
        if (done.getCount() == 0) {
          throw new IOException("The export sender has stopped");
        }
        long left = remaining();
        if (left <= 0) {
          throw tooLate();
        }
        if (queue.offer(chunk, Math.min(left, POLL_NANOS), TimeUnit.NANOSECONDS)) {
          return;
        }
      }
    } catch (InterruptedException e) {
      throw interrupted(e);
    }
  }

  /** The deadline passed: nothing more goes to the client. */
  private ExportLimitException tooLate() {
    dropping = true;
    return GobdExportService.tooLong();
  }

  /** The producer was interrupted: keeps the flag, drops the rest and reports it as I/O failure. */
  private InterruptedIOException interrupted(InterruptedException cause) {
    Thread.currentThread().interrupt();
    dropping = true;
    InterruptedIOException e = new InterruptedIOException("Interrupted while the export was sent");
    e.initCause(cause);
    return e;
  }

  private void checkClient() throws IOException {
    IOException e = failure;
    if (e != null) {
      throw new IOException("Writing the export to the client failed", e);
    }
  }

  private long remaining() {
    return deadline - System.nanoTime();
  }
}
