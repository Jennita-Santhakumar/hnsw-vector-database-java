package dev.thedal.internal.collection;

import dev.thedal.distance.Metric;
import dev.thedal.index.Index;
import dev.thedal.index.SearchParams;
import dev.thedal.index.SearchResult;
import dev.thedal.internal.store.IdMap;
import dev.thedal.internal.store.VectorStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import org.roaringbitmap.RoaringBitmap;

/**
 * The points of one collection: vectors, external ids, the search index and the tombstones of
 * deleted or overwritten points.
 *
 * <p>Deletes are logical. The ordinal goes into a tombstone bitmap, stays in the store and graph
 * (HNSW keeps traversing through it), and is excluded from results. {@link #compact()} rebuilds
 * everything from the live points once tombstones pass {@link #COMPACTION_THRESHOLD}.
 *
 * <p>Not thread-safe; the owning collection's read/write lock guards it.
 */
public final class PointSet {

  /** Tombstone share of all stored ordinals above which compaction is due. */
  public static final double COMPACTION_THRESHOLD = 0.20;

  /** A search hit: external id and distance (smaller = closer). */
  public record Hit(String id, float distance) {}

  private final int dim;
  private final Metric metric;
  private final BiFunction<VectorStore, Metric, Index> indexFactory;

  private VectorStore store;
  private IdMap ids;
  private Index index;
  private RoaringBitmap tombstones;

  /**
   * Creates an empty point set.
   *
   * @param indexFactory builds an index over a store; called again on every compaction
   */
  public PointSet(int dim, Metric metric, BiFunction<VectorStore, Metric, Index> indexFactory) {
    this.dim = dim;
    this.metric = metric;
    this.indexFactory = indexFactory;
    this.store = new VectorStore(dim);
    this.ids = new IdMap();
    this.index = indexFactory.apply(store, metric);
    this.tombstones = new RoaringBitmap();
  }

  /**
   * Inserts {@code id}, or replaces its vector. A replaced point gets a new ordinal and its old one
   * is tombstoned, because a graph node's vector cannot change in place.
   *
   * @return the point's new ordinal
   * @throws IllegalArgumentException for an invalid id or vector; nothing is changed then
   */
  public int upsert(String id, float[] vector) {
    IdMap.requireValidId(id);
    float[] prepared = metric.prepare(vector);
    int ord = store.add(prepared);
    index.add(ord, prepared);
    int previous = ids.bind(id, ord);
    if (previous != IdMap.ABSENT) {
      tombstones.add(previous);
    }
    return ord;
  }

  /** Deletes {@code id}; returns whether it existed. */
  public boolean delete(String id) {
    int ord = ids.unbind(id);
    if (ord == IdMap.ABSENT) {
      return false;
    }
    tombstones.add(ord);
    return true;
  }

  /**
   * Returns a copy of the stored vector for {@code id}, or {@code null}. Under {@link
   * Metric#COSINE} this is the normalized vector.
   */
  public float[] get(String id) {
    int ord = ids.ordOf(id);
    return ord == IdMap.ABSENT ? null : store.get(ord);
  }

  /** Current ordinal of {@code id}, or {@link IdMap#ABSENT}. */
  public int ordOf(String id) {
    return ids.ordOf(id);
  }

  /** Id of a live ordinal, or {@code null} for tombstoned or unknown ordinals. */
  public String idOf(int ord) {
    return ids.idOf(ord);
  }

  /**
   * Top-k live points closest to {@code query}.
   *
   * @param allowed extra ordinal filter; {@code null} allows every live point
   */
  public List<Hit> search(float[] query, int k, SearchParams params, IntPredicate allowed) {
    float[] prepared = metric.prepare(query);
    SearchResult result = index.search(prepared, k, params, liveAnd(allowed));
    List<Hit> hits = new ArrayList<>(result.size());
    for (int i = 0; i < result.size(); i++) {
      hits.add(new Hit(ids.idOf(result.ord(i)), result.distance(i)));
    }
    return hits;
  }

  /** Number of live points. */
  public int size() {
    return ids.size();
  }

  /** Number of tombstoned ordinals awaiting compaction. */
  public int tombstoneCount() {
    return tombstones.getCardinality();
  }

  /** Tombstones as a share of all stored ordinals (0 when empty). */
  public double tombstoneRatio() {
    return store.size() == 0 ? 0 : tombstoneCount() / (double) store.size();
  }

  /** Whether tombstones exceed {@link #COMPACTION_THRESHOLD}. */
  public boolean needsCompaction() {
    return tombstoneRatio() > COMPACTION_THRESHOLD;
  }

  /**
   * Rebuilds store, ids and index from the live points (in ordinal order) and clears tombstones.
   *
   * @return map from old ordinal to new ordinal, {@code -1} for dropped ones, so ordinal-keyed side
   *     structures can be remapped
   */
  public int[] compact() {
    VectorStore newStore = new VectorStore(dim);
    IdMap newIds = new IdMap();
    Index newIndex = indexFactory.apply(newStore, metric);
    int[] remap = new int[store.size()];
    Arrays.fill(remap, -1);
    for (int ord = 0; ord < store.size(); ord++) {
      String id = ids.idOf(ord);
      if (id == null) {
        continue; // tombstoned
      }
      float[] vector = store.get(ord);
      int newOrd = newStore.add(vector);
      newIndex.add(newOrd, vector);
      newIds.bind(id, newOrd);
      remap[ord] = newOrd;
    }
    store = newStore;
    ids = newIds;
    index = newIndex;
    tombstones = new RoaringBitmap();
    return remap;
  }

  /** Compacts if {@link #needsCompaction()}; returns the remap, or {@code null} if it did not. */
  public int[] compactIfNeeded() {
    return needsCompaction() ? compact() : null;
  }

  /** Bytes used by vectors, index structure and tombstones. */
  public long memoryBytes() {
    return store.memoryBytes() + index.memoryBytes() + tombstones.getSizeInBytes();
  }

  /** Vector dimension. */
  public int dim() {
    return dim;
  }

  /** Similarity metric. */
  public Metric metric() {
    return metric;
  }

  private IntPredicate liveAnd(IntPredicate allowed) {
    if (tombstones.isEmpty()) {
      return allowed;
    }
    RoaringBitmap dead = tombstones;
    return allowed == null
        ? ord -> !dead.contains(ord)
        : ord -> !dead.contains(ord) && allowed.test(ord);
  }
}
