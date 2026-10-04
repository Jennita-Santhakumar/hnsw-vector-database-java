package dev.thedal.internal.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends records to the WAL and makes them durable.
 *
 * <p>Usage: {@link #append} each record of a write (assigns increasing LSNs), then {@link
 * #sync(long)} with the last LSN before acknowledging. With {@link FsyncMode#ALWAYS} sync fsyncs
 * right away; with {@link FsyncMode#BATCH} it waits for the background group commit. Either way a
 * whole batch costs one fsync.
 *
 * <p>Segments rotate once they would exceed {@link WalConfig#segmentBytes()}; the old segment is
 * fsynced before the new file is created. Thread-safe.
 */
public final class WalWriter implements Closeable {

  private final Path dir;
  private final WalConfig config;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition durableAdvanced = lock.newCondition();
  private final Thread flusher;

  private FileChannel channel;
  private long segmentSize;
  private long nextLsn;
  private long writtenLsn;
  private long durableLsn;
  private long fsyncCount;
  private long fsyncNanos;
  private IOException failure;
  private boolean closed;

  /**
   * Opens the WAL in {@code dir} for appending after {@code lastLsn} (from {@link
   * WalReader#recover}, which must run first so a torn tail is gone). Appends go to a new segment.
   */
  public WalWriter(Path dir, WalConfig config, long lastLsn) {
    this.dir = dir;
    this.config = config;
    this.nextLsn = lastLsn + 1;
    this.writtenLsn = lastLsn;
    this.durableLsn = lastLsn;
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (config.fsyncMode() == FsyncMode.BATCH) {
      flusher =
          Thread.ofPlatform()
              .name("wal-flusher-" + dir.getFileName())
              .daemon()
              .unstarted(this::flushLoop);
      flusher.start();
    } else {
      flusher = null;
    }
  }

  /** Appends one record (not yet durable) and returns its LSN. */
  public long append(byte op, byte[] payload) {
    lock.lock();
    try {
      ensureOpen();
      long lsn = nextLsn;
      ByteBuffer record = Wal.encode(op, lsn, payload);
      if (record.remaining() - WalRecord.PREFIX_BYTES > WalRecord.MAX_BODY_BYTES) {
        throw new IllegalArgumentException(
            "WAL record too large: " + record.remaining() + " bytes");
      }
      if (channel == null
          || (segmentSize > 0 && segmentSize + record.remaining() > config.segmentBytes())) {
        rotate(lsn);
      }
      while (record.hasRemaining()) {
        segmentSize += channel.write(record);
      }
      nextLsn++;
      writtenLsn = lsn;
      return lsn;
    } catch (IOException e) {
      failure = e;
      throw new UncheckedIOException("WAL append failed", e);
    } finally {
      lock.unlock();
    }
  }

  /** Blocks until every record up to {@code lsn} is on disk. */
  public void sync(long lsn) {
    lock.lock();
    try {
      if (config.fsyncMode() == FsyncMode.ALWAYS) {
        if (durableLsn < lsn) {
          forceLocked();
        }
        return;
      }
      while (durableLsn < lsn) {
        ensureOpen();
        if (!durableAdvanced.await(config.batchInterval().toNanos() * 4, TimeUnit.NANOSECONDS)) {
          ensureOpen(); // timed out: surface a flusher failure instead of waiting forever
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for WAL fsync", e);
    } catch (IOException e) {
      failure = e;
      throw new UncheckedIOException("WAL fsync failed", e);
    } finally {
      lock.unlock();
    }
  }

  /** LSN of the last appended record. */
  public long lastLsn() {
    lock.lock();
    try {
      return writtenLsn;
    } finally {
      lock.unlock();
    }
  }

  /** LSN up to which records are durable. */
  public long durableLsn() {
    lock.lock();
    try {
      return durableLsn;
    } finally {
      lock.unlock();
    }
  }

  /** Number of fsyncs performed (for metrics and group-commit tests). */
  public long fsyncCount() {
    lock.lock();
    try {
      return fsyncCount;
    } finally {
      lock.unlock();
    }
  }

  /** Total time spent in fsync, in nanoseconds. */
  public long fsyncNanos() {
    lock.lock();
    try {
      return fsyncNanos;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes closed segments whose records all have LSN {@code <= coveredLsn}: segment i qualifies
   * when segment i+1 starts at or before {@code coveredLsn + 1}. The active segment is never
   * deleted.
   *
   * @return number of segments deleted
   */
  public int pruneSegmentsUpTo(long coveredLsn) {
    lock.lock();
    try {
      List<Path> segments = Wal.segments(dir);
      int deleted = 0;
      for (int i = 0; i + 1 < segments.size(); i++) {
        if (Wal.firstLsn(segments.get(i + 1)) > coveredLsn + 1) {
          break;
        }
        Files.delete(segments.get(i));
        deleted++;
      }
      if (deleted > 0) {
        Wal.fsyncDirectory(dir);
      }
      return deleted;
    } catch (IOException e) {
      throw new UncheckedIOException("pruning WAL segments in " + dir, e);
    } finally {
      lock.unlock();
    }
  }

  /** Makes everything durable, stops the flusher and closes the segment. */
  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      if (failure == null && durableLsn < writtenLsn) {
        forceLocked();
      }
      closed = true;
      durableAdvanced.signalAll();
      if (channel != null) {
        channel.close();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("WAL close failed", e);
    } finally {
      lock.unlock();
    }
    if (flusher != null) {
      flusher.interrupt();
      try {
        flusher.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void flushLoop() {
    long intervalNanos = config.batchInterval().toNanos();
    while (true) {
      try {
        TimeUnit.NANOSECONDS.sleep(intervalNanos);
      } catch (InterruptedException e) {
        return;
      }
      lock.lock();
      try {
        if (closed) {
          return;
        }
        if (failure == null && durableLsn < writtenLsn) {
          forceLocked();
        }
      } catch (IOException e) {
        failure = e;
        durableAdvanced.signalAll();
      } finally {
        lock.unlock();
      }
    }
  }

  /** fsyncs the current segment; must hold the lock. */
  private void forceLocked() throws IOException {
    if (channel != null) {
      long start = System.nanoTime();
      channel.force(false);
      fsyncNanos += System.nanoTime() - start;
      fsyncCount++;
    }
    durableLsn = writtenLsn;
    durableAdvanced.signalAll();
  }

  private void rotate(long firstLsn) throws IOException {
    if (channel != null) {
      forceLocked();
      channel.close();
    }
    Path segment = dir.resolve(Wal.segmentName(firstLsn));
    if (Files.exists(segment) && Files.size(segment) > 0) {
      // Only possible if recovery was skipped: it would have reported this segment's LSNs.
      throw new IllegalStateException("segment " + segment + " already has records; run recovery");
    }
    // An existing empty file is left by a crash right after a rotation; reuse it.
    channel =
        FileChannel.open(
            segment,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    segmentSize = 0;
    Wal.fsyncDirectory(dir);
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("WAL is closed");
    }
    if (failure != null) {
      throw new UncheckedIOException("WAL failed earlier; refusing further writes", failure);
    }
  }
}
