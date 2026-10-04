package dev.thedal.collection;

import dev.thedal.internal.storage.Snapshot;
import dev.thedal.internal.storage.WalEntry;
import dev.thedal.internal.storage.WalWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** The on-disk side of one collection: its directory, WAL writer and snapshot bookkeeping. */
final class CollectionStorage {

  final Path dir;
  final DurabilityConfig durability;
  final WalWriter wal;
  final AtomicLong recordsSinceSnapshot;

  /** Serializes snapshots of this collection. */
  final ReentrantLock snapshotLock = new ReentrantLock();

  CollectionStorage(
      Path dir, DurabilityConfig durability, WalWriter wal, long recordsSinceSnapshot) {
    this.dir = dir;
    this.durability = durability;
    this.wal = wal;
    this.recordsSinceSnapshot = new AtomicLong(recordsSinceSnapshot);
  }

  /** Appends an entry (not yet durable); returns its LSN. Call with the collection write lock. */
  long append(WalEntry entry) {
    long lsn = wal.append(entry.op(), entry.encode());
    recordsSinceSnapshot.incrementAndGet();
    return lsn;
  }

  boolean snapshotDue() {
    return recordsSinceSnapshot.get() >= durability.snapshotEveryRecords();
  }

  /**
   * Deletes WAL segments whose records are all covered by the <em>oldest</em> kept snapshot, so the
   * fallback snapshot can still be replayed forward.
   */
  void pruneWal() {
    List<Path> snapshots = Snapshot.list(dir);
    if (!snapshots.isEmpty()) {
      wal.pruneSegmentsUpTo(Snapshot.lsnOf(snapshots.get(0)));
    }
  }
}
