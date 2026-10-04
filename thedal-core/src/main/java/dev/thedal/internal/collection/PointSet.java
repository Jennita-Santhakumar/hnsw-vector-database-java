package dev.thedal.internal.collection;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.filter.Metadata;
import dev.thedal.index.ExactSearch;
import dev.thedal.index.Index;
import dev.thedal.index.SearchParams;
import dev.thedal.index.SearchResult;
import dev.thedal.internal.store.IdMap;
import dev.thedal.internal.store.VectorStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import org.roaringbitmap.RoaringBitmap;

/**
 * The points of one collection: vectors, external ids, metadata, the search index and the
 * tombstones of deleted or overwritten points.
 *
 * <p>Deletes are logical. The ordinal goes into a tombstone bitmap, stays in the store and graph
 * (HNSW keeps traversing through it), and is excluded from results. {@link #compact()} rebuilds
 * everything from the live points once tombstones pass {@link #COMPACTION_THRESHOLD}.
 *
 * <p>Filtered search picks a strategy by selectivity {@code s = matching / live}: below {@link
 * FilterConfig#exactBelowSelectivity()} it scans just the matching points exactly; otherwise it
 * runs the index with the filter applied during traversal and ef multiplied by {@code ceil(1/s)}.
 *
 * <p>Not thread-safe; the owning collection's read/write lock guards it.
 */
public final class PointSet {

  /** Tombstone share of all stored ordinals above which compaction is due. */
  public static final double COMPACTION_THRESHOLD = 0.20;

  /**
   * Filter strategy settings.
   *
   * @param exactBelowSelectivity use exact scan when matching/live is below this
   * @param maxEf cap on the expanded ef for filtered index search
   */
  public record FilterConfig(double exactBelowSelectivity, int maxEf) {
    /** 2% exact-scan threshold (ARCHITECTURE.md), ef capped at 4096. */
    public static final FilterConfig DEFAULT = new FilterConfig(0.02, 4096);

    /** Validates the settings. */
    public FilterConfig {
      if (!(exactBelowSelectivity >= 0 && exactBelowSelectivity <= 1)) {
        throw new IllegalArgumentException("exactBelowSelectivity must be in [0, 1]");
      }
      if (maxEf < 1) {
        throw new IllegalArgumentException("maxEf must be >= 1: " + maxEf);
      }
    }
  }

  /** How a search was answered. */
  public enum Strategy {
    /** No filter: plain index search. */
    UNFILTERED,
    /** Filter matched nothing. */
    NO_MATCH,
    /** Selective filter: exact scan over the matching points. */
    EXACT_SCAN,
    /** Index search with the filter applied during traversal and an expanded ef. */
    FILTERED_INDEX
  }

  /** A search hit: external id, distance (smaller = closer) and the point's metadata. */
  public record Hit(String id, float distance, Map<String, Object> metadata) {
    /** Takes an immutable copy of the metadata. */
    public Hit {
      metadata = Map.copyOf(metadata);
    }
  }

  /**
   * Search hits plus how they were found.
   *
   * @param selectivity matching / live points; 1 when unfiltered
   */
  public record SearchOutcome(List<Hit> hits, Strategy strategy, double selectivity) {
    /** Takes an immutable copy of the hits. */
    public SearchOutcome {
      hits = List.copyOf(hits);
    }
  }

  private final int dim;
  private final Metric metric;
  private final BiFunction<VectorStore, Metric, Index> indexFactory;
  private final FilterConfig filterConfig;

  private VectorStore store;
  private IdMap ids;
  private Index index;
  private RoaringBitmap tombstones;
  private final MetadataStore metadata = new MetadataStore();

  /**
   * Creates an empty point set with default filter settings.
   *
   * @param indexFactory builds an index over a store; called again on every compaction
   */
  public PointSet(int dim, Metric metric, BiFunction<VectorStore, Metric, Index> indexFactory) {
    this(dim, metric, indexFactory, FilterConfig.DEFAULT);
  }

  /** Creates an empty point set. */
  public PointSet(
      int dim,
      Metric metric,
      BiFunction<VectorStore, Metric, Index> indexFactory,
      FilterConfig filterConfig) {
    this.dim = dim;
    this.metric = metric;
    this.indexFactory = indexFactory;
    this.filterConfig = filterConfig;
    this.store = new VectorStore(dim);
    this.ids = new IdMap();
    this.index = indexFactory.apply(store, metric);
    this.tombstones = new RoaringBitmap();
  }

  /** Inserts or replaces {@code id} with no metadata. See {@link #upsert(String, float[], Map)}. */
  public int upsert(String id, float[] vector) {
    return upsert(id, vector, Map.of());
  }

  /**
   * Inserts {@code id}, or replaces its vector and metadata. A replaced point gets a new ordinal
   * and its old one is tombstoned, because a graph node's vector cannot change in place.
   *
   * @return the point's new ordinal
   * @throws IllegalArgumentException for an invalid id, vector or metadata; nothing changes then
   */
  public int upsert(String id, float[] vector, Map<String, ?> fields) {
    IdMap.requireValidId(id);
    Map<String, Object> normalized = Metadata.normalize(fields);
    float[] prepared = metric.prepare(vector);
    int ord = store.add(prepared);
    index.add(ord, prepared);
    int previous = ids.bind(id, ord);
    if (previous != IdMap.ABSENT) {
      tombstones.add(previous);
      metadata.remove(previous);
    }
    metadata.put(ord, normalized);
    return ord;
  }

  /** Deletes {@code id}; returns whether it existed. */
  public boolean delete(String id) {
    int ord = ids.unbind(id);
    if (ord == IdMap.ABSENT) {
      return false;
    }
    tombstones.add(ord);
    metadata.remove(ord);
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

  /** Metadata of {@code id}; empty if it has none or does not exist. */
  public Map<String, Object> getMetadata(String id) {
    int ord = ids.ordOf(id);
    return ord == IdMap.ABSENT ? Map.of() : metadata.get(ord);
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
  public List<Hit> searchByOrdinal(
      float[] query, int k, SearchParams params, IntPredicate allowed) {
    float[] prepared = metric.prepare(query);
    return toHits(index.search(prepared, k, params, liveAnd(allowed)));
  }

  /**
   * Top-k live points closest to {@code query} that match {@code filter} ({@code null} = all),
   * choosing the strategy by selectivity.
   */
  public SearchOutcome search(float[] query, int k, SearchParams params, Filter filter) {
    float[] prepared = metric.prepare(query);
    if (filter == null) {
      return new SearchOutcome(
          toHits(index.search(prepared, k, params, liveAnd(null))), Strategy.UNFILTERED, 1.0);
    }
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1: " + k);
    }
    RoaringBitmap matching = metadata.match(filter);
    matching.andNot(tombstones); // metadata holds live points only; this is a safety net
    int live = size();
    double selectivity = live == 0 ? 0 : matching.getCardinality() / (double) live;
    if (matching.isEmpty()) {
      return new SearchOutcome(List.of(), Strategy.NO_MATCH, 0);
    }
    if (selectivity < filterConfig.exactBelowSelectivity()) {
      SearchResult exact =
          ExactSearch.topK(store, metric.distance(), prepared, k, matching.toArray());
      return new SearchOutcome(toHits(exact), Strategy.EXACT_SCAN, selectivity);
    }
    int baseEf = Math.max(params.ef() > 0 ? params.ef() : index.defaultEf(), k);
    long expanded = (long) baseEf * (long) Math.ceil(1 / selectivity);
    SearchParams widened = new SearchParams((int) Math.min(filterConfig.maxEf(), expanded));
    SearchResult result = index.search(prepared, k, widened, matching::contains);
    return new SearchOutcome(toHits(result), Strategy.FILTERED_INDEX, selectivity);
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
   * Rebuilds store, ids, metadata and index from the live points (in ordinal order) and clears
   * tombstones.
   *
   * @return map from old ordinal to new ordinal, {@code -1} for dropped ones
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
    metadata.remap(remap);
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

  /** Bytes used by vectors, index structure and tombstones (metadata not included). */
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

  private List<Hit> toHits(SearchResult result) {
    List<Hit> hits = new ArrayList<>(result.size());
    for (int i = 0; i < result.size(); i++) {
      int ord = result.ord(i);
      hits.add(new Hit(ids.idOf(ord), result.distance(i), metadata.get(ord)));
    }
    return hits;
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
