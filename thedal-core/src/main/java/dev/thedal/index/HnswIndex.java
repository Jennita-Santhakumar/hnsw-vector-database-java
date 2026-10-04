package dev.thedal.index;

import dev.thedal.distance.Distance;
import dev.thedal.internal.store.VectorStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Hierarchical Navigable Small World graph index (Malkov &amp; Yashunin, arXiv:1603.09320).
 *
 * <p>Each node gets a random top layer {@code floor(-ln(U) * mL)}. Upper layers are sparse
 * long-range graphs used to descend greedily toward the query; layer 0 holds every node and is
 * searched best-first with a candidate list of size ef.
 *
 * <p>Neighbour lists are {@code int[]} with the count in slot 0 followed by up to M (or M0 = 2M on
 * layer 0) neighbour ordinals. Ordinals must be added densely in order, matching the vector store.
 *
 * <p>Not thread-safe: one writer at a time, and no searches concurrent with a write. The owning
 * collection's read/write lock provides that.
 */
public final class HnswIndex implements Index {

  private final VectorStore store;
  private final Distance distance;
  private final HnswParams params;
  private final double levelMultiplier;
  private final Random random;

  private int[] levels = new int[16];
  private int[][][] links = new int[16][][];
  private int count;
  private int entryPoint = -1;
  private int maxLevel = -1;
  private long linkBytes;

  /** Creates an empty HNSW index over {@code store}. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The index reads the collection's shared store by design; never copied.")
  public HnswIndex(VectorStore store, Distance distance, HnswParams params) {
    this.store = store;
    this.distance = distance;
    this.params = params;
    this.levelMultiplier = params.levelMultiplier();
    this.random = new Random(params.seed());
  }

  /**
   * Inserts {@code ord} (Algorithm 1): greedy descent to the node's top layer, then on each of its
   * layers a best-first search with efConstruction, M neighbours chosen, and bidirectional links.
   *
   * @throws IllegalArgumentException if ordinals are not added densely in order (0, 1, 2, ...)
   */
  @Override
  public void add(int ord, float[] vector) {
    if (ord != count) {
      throw new IllegalArgumentException("expected ordinal " + count + ", got " + ord);
    }
    if (ord >= store.size()) {
      throw new IllegalArgumentException("ordinal " + ord + " is not in the vector store");
    }
    if (vector.length != store.dim()) {
      throw new IllegalArgumentException(
          "dimension mismatch: expected " + store.dim() + ", got " + vector.length);
    }
    int level = randomLevel(random, levelMultiplier);
    allocateNode(ord, level);
    count++;

    if (entryPoint < 0) {
      entryPoint = ord;
      maxLevel = level;
      return;
    }

    int ep = entryPoint;
    for (int layer = maxLevel; layer > level; layer--) {
      ep = greedyClosest(vector, ep, layer);
    }
    int[] entryPoints = {ep};
    for (int layer = Math.min(level, maxLevel); layer >= 0; layer--) {
      SearchResult found =
          searchLayer(vector, entryPoints, params.efConstruction(), layer).drainSorted();
      int[] candidates = found.ords();
      int[] neighbours = selectNeighbours(found, params.m());
      int[] own = links[ord][layer];
      System.arraycopy(neighbours, 0, own, 1, neighbours.length);
      own[0] = neighbours.length;
      for (int neighbour : neighbours) {
        connect(neighbour, ord, layer);
      }
      entryPoints = candidates;
    }

    if (level > maxLevel) {
      maxLevel = level;
      entryPoint = ord;
    }
  }

  /**
   * K-nearest-neighbour search (Algorithm 5): greedy descent through the upper layers, then a
   * best-first search of layer 0 with ef = max(efSearch or the per-query override, k).
   *
   * <p>{@code allowed} is applied to the ef candidates found; with a restrictive filter this can
   * return fewer than k hits.
   */
  @Override
  public SearchResult search(
      float[] query, int k, SearchParams searchParams, IntPredicate allowed) {
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1: " + k);
    }
    if (query.length != store.dim()) {
      throw new IllegalArgumentException(
          "dimension mismatch: expected " + store.dim() + ", got " + query.length);
    }
    if (count == 0) {
      return SearchResult.empty();
    }
    int ef = Math.max(searchParams.ef() > 0 ? searchParams.ef() : params.efSearch(), k);
    int ep = entryPoint;
    for (int layer = maxLevel; layer > 0; layer--) {
      ep = greedyClosest(query, ep, layer);
    }
    SearchResult candidates = searchLayer(query, new int[] {ep}, ef, 0).drainSorted();

    int[] ords = new int[Math.min(k, candidates.size())];
    float[] dists = new float[ords.length];
    int n = 0;
    for (int i = 0; i < candidates.size() && n < ords.length; i++) {
      int ord = candidates.ord(i);
      if (allowed == null || allowed.test(ord)) {
        ords[n] = ord;
        dists[n] = candidates.distance(i);
        n++;
      }
    }
    return new SearchResult(Arrays.copyOf(ords, n), Arrays.copyOf(dists, n));
  }

  /** Estimated bytes of the graph: neighbour arrays plus per-node bookkeeping. */
  @Override
  public long memoryBytes() {
    return linkBytes + (long) levels.length * Integer.BYTES + (long) links.length * 8;
  }

  /** Number of nodes in the graph. */
  public int size() {
    return count;
  }

  /**
   * Level for a new node: floor(-ln(U) * mL) with U uniform in (0, 1], giving P(level >= l) = M^-l.
   */
  static int randomLevel(Random random, double levelMultiplier) {
    double u = 1.0 - random.nextDouble();
    return (int) Math.floor(-Math.log(u) * levelMultiplier);
  }

  int levelOf(int ord) {
    return levels[ord];
  }

  int entryPoint() {
    return entryPoint;
  }

  int maxLevel() {
    return maxLevel;
  }

  /** Copy of the neighbours of {@code ord} on {@code layer}. */
  int[] neighbours(int ord, int layer) {
    int[] list = links[ord][layer];
    return Arrays.copyOfRange(list, 1, 1 + list[0]);
  }

  /** Algorithm 2: best-first search of one layer, returning the ef closest nodes found. */
  private BoundedMaxHeap searchLayer(float[] query, int[] entryPoints, int ef, int layer) {
    boolean[] visited = new boolean[count];
    MinHeap candidates = new MinHeap(ef);
    BoundedMaxHeap results = new BoundedMaxHeap(ef);
    for (int ep : entryPoints) {
      if (!visited[ep]) {
        visited[ep] = true;
        float d = store.distance(distance, query, ep);
        candidates.push(ep, d);
        results.offer(ep, d);
      }
    }
    while (!candidates.isEmpty()) {
      int current = candidates.peekOrd();
      float currentDist = candidates.peekDist();
      candidates.pop();
      if (results.isFull() && currentDist > results.worstDistance()) {
        break; // every remaining candidate is farther than the worst result
      }
      int[] list = links[current][layer];
      for (int i = 1; i <= list[0]; i++) {
        int neighbour = list[i];
        if (visited[neighbour]) {
          continue;
        }
        visited[neighbour] = true;
        float d = store.distance(distance, query, neighbour);
        if (results.offer(neighbour, d)) {
          candidates.push(neighbour, d);
        }
      }
    }
    return results;
  }

  /** Greedy walk on one layer (the ef = 1 case): move to any closer neighbour until none is. */
  private int greedyClosest(float[] query, int start, int layer) {
    int current = start;
    float currentDist = store.distance(distance, query, current);
    boolean improved = true;
    while (improved) {
      improved = false;
      int[] list = links[current][layer];
      for (int i = 1; i <= list[0]; i++) {
        float d = store.distance(distance, query, list[i]);
        if (d < currentDist) {
          currentDist = d;
          current = list[i];
          improved = true;
        }
      }
    }
    return current;
  }

  /**
   * Adds {@code newNeighbour} to {@code node}'s list on {@code layer}. When the list is full, it is
   * shrunk by re-running neighbour selection over the old neighbours plus the new one, measured
   * from {@code node}.
   */
  private void connect(int node, int newNeighbour, int layer) {
    int[] list = links[node][layer];
    int n = list[0];
    int capacity = list.length - 1;
    if (n < capacity) {
      list[n + 1] = newNeighbour;
      list[0] = n + 1;
      return;
    }
    BoundedMaxHeap all = new BoundedMaxHeap(n + 1);
    for (int i = 1; i <= n; i++) {
      all.offer(list[i], store.distance(distance, node, list[i]));
    }
    all.offer(newNeighbour, store.distance(distance, node, newNeighbour));
    int[] kept = selectNeighbours(all.drainSorted(), capacity);
    System.arraycopy(kept, 0, list, 1, kept.length);
    list[0] = kept.length;
  }

  /**
   * Picks at most {@code max} links from {@code candidates} (sorted closest-first to the base node)
   * using the configured {@link NeighborSelection}.
   */
  private int[] selectNeighbours(SearchResult candidates, int max) {
    int limit = Math.min(max, candidates.size());
    if (params.selection() == NeighborSelection.SIMPLE) {
      return Arrays.copyOf(candidates.ords(), limit);
    }
    // Algorithm 4 without candidate extension or re-adding pruned links (as in hnswlib).
    int[] selected = new int[limit];
    int n = 0;
    for (int i = 0; i < candidates.size() && n < limit; i++) {
      int candidate = candidates.ord(i);
      float toBase = candidates.distance(i);
      boolean diverse = true;
      for (int j = 0; j < n; j++) {
        if (store.distance(distance, candidate, selected[j]) < toBase) {
          diverse = false; // an already-kept neighbour covers this direction
          break;
        }
      }
      if (diverse) {
        selected[n++] = candidate;
      }
    }
    return Arrays.copyOf(selected, n);
  }

  private void allocateNode(int ord, int level) {
    if (ord == levels.length) {
      int capacity = levels.length * 2;
      levels = Arrays.copyOf(levels, capacity);
      links = Arrays.copyOf(links, capacity);
    }
    levels[ord] = level;
    int[][] layers = new int[level + 1][];
    long bytes = 16 + 8L * layers.length;
    for (int layer = 0; layer <= level; layer++) {
      int capacity = layer == 0 ? params.m0() : params.m();
      layers[layer] = new int[capacity + 1];
      bytes += 16 + 4L * (capacity + 1);
    }
    links[ord] = layers;
    linkBytes += bytes;
  }
}
