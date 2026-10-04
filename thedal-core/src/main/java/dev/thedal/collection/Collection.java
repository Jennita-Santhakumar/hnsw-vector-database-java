package dev.thedal.collection;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.FlatIndex;
import dev.thedal.index.HnswIndex;
import dev.thedal.index.Index;
import dev.thedal.index.IndexProvider;
import dev.thedal.index.SearchParams;
import dev.thedal.internal.collection.PointSet;
import dev.thedal.internal.storage.Fs;
import dev.thedal.internal.storage.Snapshot;
import dev.thedal.internal.storage.WalCorruptedException;
import dev.thedal.internal.storage.WalEntry;
import dev.thedal.internal.storage.WalReader;
import dev.thedal.internal.storage.WalWriter;
import dev.thedal.internal.store.IdMap;
import dev.thedal.internal.store.VectorStore;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A named set of points with one dimension, metric and index, either in memory only or durable on
 * disk.
 *
 * <p>Thread-safe through one {@link ReentrantReadWriteLock}: any number of concurrent searches and
 * reads, one writer at a time. Write batches are fully validated <em>before</em> the write lock is
 * taken, so a bad point rejects the whole batch without side effects. Compaction runs inside the
 * write path once tombstones pass 20%.
 *
 * <p>Durable collections log every write to a WAL under the write lock, apply it in memory, release
 * the lock, and only then wait for the fsync before returning. Readers never wait behind an fsync,
 * and a write is acknowledged (the method returns) only once it is on disk. A concurrent reader may
 * briefly see a write whose fsync is still in progress.
 */
public final class Collection implements Closeable {

  /** Largest accepted k. */
  public static final int MAX_K = 10_000;

  /** Largest accepted per-query ef (bounds the per-query heap allocation). */
  public static final int MAX_EF = 10_000;

  private final CollectionConfig config;
  private final PointSet points;
  private final CollectionStorage storage;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final Lock readLock = rwLock.readLock();
  private final Lock writeLock = rwLock.writeLock();
  private long compactions;

  /** Creates an empty in-memory collection. */
  public Collection(CollectionConfig config) {
    this(config, newPoints(config), null);
  }

  private Collection(CollectionConfig config, PointSet points, CollectionStorage storage) {
    this.config = config;
    this.points = points;
    this.storage = storage;
  }

  /**
   * Creates an empty durable collection in {@code dir}; the CREATE_COLLECTION record is fsynced
   * before this returns.
   */
  static Collection createDurable(CollectionConfig config, Path dir, DurabilityConfig durability) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    WalWriter wal = new WalWriter(dir, durability.wal(), 0);
    CollectionStorage storage = new CollectionStorage(dir, durability, wal, 0);
    wal.sync(storage.append(new WalEntry.CreateCollection(config)));
    return new Collection(config, newPoints(config), storage);
  }

  /** Recovery state while replaying. */
  private static final class Replay {
    CollectionConfig config;
    PointSet points;
    boolean dropped;
    long applied;
  }

  /**
   * Recovers the collection stored in {@code dir}: newest valid snapshot, then WAL records after
   * it, then compaction if due.
   *
   * @return the collection, or {@code null} if {@code dir} holds no committed collection (its
   *     CREATE record never became durable) or a dropped one; the caller deletes the directory
   */
  static Collection recover(Path dir, DurabilityConfig durability) {
    Replay replay = new Replay();
    long snapshotLsn =
        Snapshot.loadLatest(
                dir,
                header -> {
                  replay.config = header.config();
                  replay.points =
                      PointSet.readParts(
                          header.dir(),
                          header.config().dim(),
                          header.config().metric(),
                          indexFactory(header.config()),
                          PointSet.FilterConfig.DEFAULT);
                  return header.lsn();
                })
            .orElse(0L);
    WalReader.Recovery recovery =
        WalReader.recover(dir, snapshotLsn, record -> apply(replay, WalEntry.decode(record), dir));
    if (replay.dropped || replay.config == null) {
      return null;
    }
    replay.points.compactIfNeeded();
    // Never reuse an LSN at or below the snapshot's, even if the WAL holding it was pruned.
    WalWriter wal = new WalWriter(dir, durability.wal(), Math.max(recovery.lastLsn(), snapshotLsn));
    return new Collection(
        replay.config, replay.points, new CollectionStorage(dir, durability, wal, replay.applied));
  }

  private static void apply(Replay replay, WalEntry entry, Path dir) {
    switch (entry) {
      case WalEntry.CreateCollection create -> {
        if (replay.config != null) {
          throw new WalCorruptedException("second CREATE_COLLECTION record in " + dir);
        }
        replay.config = create.config();
        replay.points = newPoints(create.config());
      }
      case WalEntry.DropCollection drop -> replay.dropped = true;
      case WalEntry.Upsert upsert -> {
        requireCreated(replay, dir);
        replay.points.upsert(upsert.id(), upsert.vector(), upsert.metadata());
      }
      case WalEntry.Delete delete -> {
        requireCreated(replay, dir);
        replay.points.delete(delete.id());
      }
    }
    replay.applied++;
  }

  private static void requireCreated(Replay replay, Path dir) {
    if (replay.config == null) {
      throw new WalCorruptedException("data record before CREATE_COLLECTION in " + dir);
    }
  }

  /** Settings fixed at creation. */
  public CollectionConfig config() {
    return config;
  }

  /** Collection name. */
  public String name() {
    return config.name();
  }

  /** Whether writes are logged to disk. */
  public boolean isDurable() {
    return storage != null;
  }

  /**
   * Inserts or replaces every point in {@code batch}, in order (a repeated id keeps its last
   * version). All-or-nothing on validation: if any point is invalid, nothing is written. For a
   * durable collection, returns only after the batch is on disk.
   *
   * @throws IllegalArgumentException naming the first invalid point
   */
  public void upsert(List<Point> batch) {
    for (Point point : batch) {
      validateVector(point.id(), point.vector());
    }
    long lsn = 0;
    writeLock.lock();
    try {
      if (storage != null) {
        for (Point point : batch) {
          lsn = storage.append(new WalEntry.Upsert(point.id(), point.vector(), point.metadata()));
        }
      }
      for (Point point : batch) {
        points.upsert(point.id(), point.vector(), point.metadata());
      }
      compactIfNeeded();
    } finally {
      writeLock.unlock();
    }
    afterWrite(lsn);
  }

  /** Deletes {@code id}; returns whether it existed. Durable once this returns. */
  public boolean delete(String id) {
    long lsn = 0;
    writeLock.lock();
    try {
      if (points.ordOf(id) == IdMap.ABSENT) {
        return false;
      }
      if (storage != null) {
        lsn = storage.append(new WalEntry.Delete(id));
      }
      points.delete(id);
      compactIfNeeded();
    } finally {
      writeLock.unlock();
    }
    afterWrite(lsn);
    return true;
  }

  /** The stored point (vector normalized under cosine), if present. */
  public Optional<Point> get(String id) {
    readLock.lock();
    try {
      float[] vector = points.get(id);
      return vector == null
          ? Optional.empty()
          : Optional.of(new Point(id, vector, points.getMetadata(id)));
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Top-k points closest to {@code query} that match {@code filter} ({@code null} = all).
   *
   * @throws IllegalArgumentException for a bad query vector, k outside 1..{@link #MAX_K} or ef
   *     above {@link #MAX_EF}
   */
  public SearchResponse search(float[] query, int k, SearchParams params, Filter filter) {
    if (k < 1 || k > MAX_K) {
      throw new IllegalArgumentException("k must be 1-" + MAX_K + ": " + k);
    }
    if (params.ef() > MAX_EF) {
      throw new IllegalArgumentException("ef must be <= " + MAX_EF + ": " + params.ef());
    }
    validateVector("query", query);
    PointSet.SearchOutcome outcome;
    readLock.lock();
    try {
      outcome = points.search(query, k, params, filter);
    } finally {
      readLock.unlock();
    }
    List<SearchHit> hits = new ArrayList<>(outcome.hits().size());
    for (PointSet.Hit hit : outcome.hits()) {
      hits.add(new SearchHit(hit.id(), hit.distance(), hit.metadata()));
    }
    return new SearchResponse(hits, outcome.strategy().name(), outcome.selectivity());
  }

  /** Rebuilds the collection from live points now, whatever the tombstone ratio. */
  public void compact() {
    writeLock.lock();
    try {
      points.compact();
      compactions++;
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Writes a snapshot of the current state and prunes WAL segments it makes redundant. Writers wait
   * while the snapshot is written; searches continue.
   *
   * @return the snapshot's LSN
   * @throws IllegalStateException for an in-memory collection
   */
  public long snapshot() {
    if (storage == null) {
      throw new IllegalStateException("collection '" + name() + "' is not durable");
    }
    storage.snapshotLock.lock();
    try {
      long lsn;
      readLock.lock();
      try {
        // Appends and their in-memory application share the write lock, so with the read lock
        // held every record up to lastLsn() is reflected in the points written here.
        lsn = storage.wal.lastLsn();
        Snapshot.write(storage.dir, lsn, config, points::writeParts);
        storage.recordsSinceSnapshot.set(0);
      } finally {
        readLock.unlock();
      }
      storage.pruneWal();
      return lsn;
    } finally {
      storage.snapshotLock.unlock();
    }
  }

  /** Current statistics. */
  public CollectionStats stats() {
    readLock.lock();
    try {
      return new CollectionStats(
          config, points.size(), points.tombstoneCount(), points.memoryBytes(), compactions);
    } finally {
      readLock.unlock();
    }
  }

  /** Closes the WAL (making everything durable). In-memory collections need no closing. */
  @Override
  public void close() {
    if (storage != null) {
      storage.wal.close();
    }
  }

  /**
   * Durably drops this collection: logs DROP_COLLECTION, closes the WAL, atomically renames the
   * directory aside and deletes it. A crash midway is finished by the next startup.
   */
  void dropStorage() {
    if (storage == null) {
      return;
    }
    writeLock.lock();
    try {
      storage.wal.sync(storage.append(new WalEntry.DropCollection(name())));
      storage.wal.close();
      Path aside =
          storage.dir.resolveSibling(name() + CollectionManager.DROPPED_SUFFIX + System.nanoTime());
      Files.move(storage.dir, aside, StandardCopyOption.ATOMIC_MOVE);
      Path parent = aside.toAbsolutePath().getParent();
      if (parent != null) {
        Fs.fsyncDirectory(parent);
      }
      Fs.deleteRecursively(aside);
    } catch (IOException e) {
      throw new UncheckedIOException("dropping collection '" + name() + "'", e);
    } finally {
      writeLock.unlock();
    }
  }

  private void afterWrite(long lsn) {
    if (storage == null) {
      return;
    }
    storage.wal.sync(lsn);
    if (storage.snapshotDue()) {
      snapshot();
    }
  }

  /** Must be called with the write lock held. */
  private void compactIfNeeded() {
    if (points.compactIfNeeded() != null) {
      compactions++;
    }
  }

  private void validateVector(String what, float[] vector) {
    if (vector.length != config.dim()) {
      throw new IllegalArgumentException(
          what + ": expected dimension " + config.dim() + ", got " + vector.length);
    }
    boolean allZero = true;
    for (float component : vector) {
      if (!Float.isFinite(component)) {
        throw new IllegalArgumentException(what + ": vector components must be finite");
      }
      allZero &= component == 0f;
    }
    if (allZero && config.metric() == Metric.COSINE) {
      throw new IllegalArgumentException(what + ": cosine needs a non-zero vector");
    }
  }

  private static PointSet newPoints(CollectionConfig config) {
    return new PointSet(config.dim(), config.metric(), indexFactory(config));
  }

  /** Creates and restores the index type configured for a collection. */
  static IndexProvider indexFactory(CollectionConfig config) {
    return new IndexProvider() {
      @Override
      public Index create(VectorStore store, Metric metric) {
        return switch (config.indexType()) {
          case FLAT -> new FlatIndex(store, metric.distance());
          case HNSW -> new HnswIndex(store, metric.distance(), config.hnsw());
        };
      }

      @Override
      public Index read(VectorStore store, Metric metric, DataInputStream in) throws IOException {
        return switch (config.indexType()) {
          case FLAT -> FlatIndex.read(store, metric.distance(), in);
          case HNSW -> HnswIndex.read(store, metric.distance(), config.hnsw(), in);
        };
      }
    };
  }
}
