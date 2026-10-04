package dev.thedal.collection;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.FlatIndex;
import dev.thedal.index.HnswIndex;
import dev.thedal.index.Index;
import dev.thedal.index.SearchParams;
import dev.thedal.internal.collection.PointSet;
import dev.thedal.internal.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

/**
 * A named set of points with one dimension, metric and index.
 *
 * <p>Thread-safe through one {@link ReentrantReadWriteLock}: any number of concurrent searches and
 * reads, one writer at a time. Write batches are fully validated <em>before</em> the write lock is
 * taken, so a bad point rejects the whole batch without side effects and the lock is held only for
 * the mutation. Compaction runs inside the write path once tombstones pass 20%.
 */
public final class Collection {

  /** Largest accepted k. */
  public static final int MAX_K = 10_000;

  /** Largest accepted per-query ef (bounds the per-query heap allocation). */
  public static final int MAX_EF = 10_000;

  private final CollectionConfig config;
  private final PointSet points;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final Lock readLock = rwLock.readLock();
  private final Lock writeLock = rwLock.writeLock();
  private long compactions;

  /** Creates an empty collection. */
  public Collection(CollectionConfig config) {
    this.config = config;
    this.points = new PointSet(config.dim(), config.metric(), indexFactory(config));
  }

  /** Settings fixed at creation. */
  public CollectionConfig config() {
    return config;
  }

  /** Collection name. */
  public String name() {
    return config.name();
  }

  /**
   * Inserts or replaces every point in {@code batch}, in order (a repeated id keeps its last
   * version). All-or-nothing: if any point is invalid, nothing is written.
   *
   * @throws IllegalArgumentException naming the first invalid point
   */
  public void upsert(List<Point> batch) {
    for (Point point : batch) {
      validateVector(point.id(), point.vector());
    }
    writeLock.lock();
    try {
      for (Point point : batch) {
        points.upsert(point.id(), point.vector(), point.metadata());
      }
      compactIfNeeded();
    } finally {
      writeLock.unlock();
    }
  }

  /** Deletes {@code id}; returns whether it existed. */
  public boolean delete(String id) {
    writeLock.lock();
    try {
      boolean deleted = points.delete(id);
      if (deleted) {
        compactIfNeeded();
      }
      return deleted;
    } finally {
      writeLock.unlock();
    }
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

  private static BiFunction<VectorStore, Metric, Index> indexFactory(CollectionConfig config) {
    return switch (config.indexType()) {
      case FLAT -> (store, metric) -> new FlatIndex(store, metric.distance());
      case HNSW -> (store, metric) -> new HnswIndex(store, metric.distance(), config.hnsw());
    };
  }
}
