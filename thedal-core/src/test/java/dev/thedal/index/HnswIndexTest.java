package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HnswIndexTest {

  static float[] gaussian(Random rnd, int dim) {
    float[] v = new float[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = (float) rnd.nextGaussian();
    }
    return v;
  }

  static HnswIndex build(VectorStore store, Metric metric, HnswParams params, int n, long seed) {
    Random rnd = new Random(seed);
    HnswIndex index = new HnswIndex(store, metric.distance(), params);
    for (int i = 0; i < n; i++) {
      float[] v = metric.prepare(gaussian(rnd, store.dim()));
      index.add(store.add(v), v);
    }
    return index;
  }

  @Test
  void levelsFollowTheGeometricDistribution() {
    Random rnd = new Random(1);
    double mL = HnswParams.defaults().levelMultiplier();
    int n = 200_000;
    int[] atLeast = new int[4];
    for (int i = 0; i < n; i++) {
      int level = HnswIndex.randomLevel(rnd, mL);
      assertThat(level).isNotNegative();
      for (int l = 0; l < atLeast.length && l <= level; l++) {
        atLeast[l]++;
      }
    }
    // P(level >= l) = M^-l with M = 16.
    assertThat(atLeast[0]).isEqualTo(n);
    assertThat(atLeast[1] / (double) n).isCloseTo(1.0 / 16, within(0.003));
    assertThat(atLeast[2] / (double) n).isCloseTo(1.0 / 256, within(0.0006));
  }

  @Test
  void sameSeedAndInsertsBuildTheSameGraph() {
    HnswParams params = new HnswParams(8, 50, 32, 7);
    HnswIndex a = build(new VectorStore(8), Metric.L2, params, 500, 3);
    HnswIndex b = build(new VectorStore(8), Metric.L2, params, 500, 3);
    assertThat(a.entryPoint()).isEqualTo(b.entryPoint());
    assertThat(a.maxLevel()).isEqualTo(b.maxLevel());
    for (int ord = 0; ord < 500; ord++) {
      assertThat(a.levelOf(ord)).isEqualTo(b.levelOf(ord));
      for (int layer = 0; layer <= a.levelOf(ord); layer++) {
        assertThat(a.neighbours(ord, layer)).containsExactly(b.neighbours(ord, layer));
      }
    }
  }

  @Test
  void graphInvariantsHold() {
    HnswParams params = new HnswParams(6, 40, 32, 11);
    VectorStore store = new VectorStore(12);
    HnswIndex index = build(store, Metric.COSINE, params, 2_000, 5);

    int[] nodesOnLayer = new int[64];
    for (int ord = 0; ord < index.size(); ord++) {
      for (int layer = 0; layer <= index.levelOf(ord); layer++) {
        nodesOnLayer[layer]++;
      }
    }
    int topLevel = -1;
    for (int ord = 0; ord < index.size(); ord++) {
      topLevel = Math.max(topLevel, index.levelOf(ord));
      for (int layer = 0; layer <= index.levelOf(ord); layer++) {
        int[] nbrs = index.neighbours(ord, layer);
        int cap = layer == 0 ? params.m0() : params.m();
        // A node can only be isolated on a layer where it is the sole node.
        assertThat(nbrs.length).isBetween(nodesOnLayer[layer] > 1 ? 1 : 0, cap);
        assertThat(nbrs).doesNotContain(ord).doesNotHaveDuplicates();
        for (int nbr : nbrs) {
          assertThat(index.levelOf(nbr)).isGreaterThanOrEqualTo(layer);
        }
      }
    }
    assertThat(index.maxLevel()).isEqualTo(topLevel);
    assertThat(index.levelOf(index.entryPoint())).isEqualTo(topLevel);
    assertThat(reachableOnLayer0(index)).isEqualTo(index.size());
    assertThat(index.memoryBytes()).isPositive();
  }

  @Test
  void everyStoredVectorFindsItselfFirst() {
    VectorStore store = new VectorStore(16);
    HnswIndex index = build(store, Metric.L2, HnswParams.defaults(), 1_000, 9);
    for (int ord = 0; ord < store.size(); ord += 37) {
      SearchResult r = index.search(store.get(ord), 1, SearchParams.DEFAULT, null);
      assertThat(r.ord(0)).isEqualTo(ord);
      assertThat(r.distance(0)).isZero();
    }
  }

  @Test
  void resultsAreSortedAndExactlyScored() {
    VectorStore store = new VectorStore(8);
    HnswIndex index = build(store, Metric.DOT, HnswParams.defaults(), 800, 2);
    float[] query = gaussian(new Random(99), 8);
    SearchResult r = index.search(query, 10, new SearchParams(100), null);
    assertThat(r.size()).isEqualTo(10);
    for (int i = 0; i < r.size(); i++) {
      assertThat(r.distance(i)).isEqualTo(store.distance(Metric.DOT.distance(), query, r.ord(i)));
      if (i > 0) {
        assertThat(r.distance(i)).isGreaterThanOrEqualTo(r.distance(i - 1));
      }
    }
  }

  @Test
  void smallIndexesAndFilters() {
    VectorStore store = new VectorStore(2);
    HnswIndex index = new HnswIndex(store, Metric.L2.distance(), HnswParams.defaults());
    assertThat(index.search(new float[2], 3, SearchParams.DEFAULT, null).size()).isZero();

    float[] only = {1, 1};
    index.add(store.add(only), only);
    SearchResult one = index.search(new float[2], 3, SearchParams.DEFAULT, null);
    assertThat(one.ords()).containsExactly(0);

    for (int i = 1; i < 5; i++) {
      float[] v = {i, -i};
      index.add(store.add(v), v);
    }
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, null).size()).isEqualTo(5);
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, o -> o % 2 == 1).ords())
        .containsExactly(1, 3);
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, o -> false).size()).isZero();
  }

  @Test
  void rejectsInvalidArguments() {
    VectorStore store = new VectorStore(2);
    HnswIndex index = new HnswIndex(store, Metric.L2.distance(), HnswParams.defaults());
    store.add(new float[2]);
    assertThatThrownBy(() -> index.add(1, new float[2]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected ordinal 0");
    assertThatThrownBy(() -> index.add(0, new float[3]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimension mismatch");
    index.add(0, new float[2]);
    assertThatThrownBy(() -> index.add(1, new float[2]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in the vector store");
    assertThatThrownBy(() -> index.search(new float[2], 0, SearchParams.DEFAULT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> index.search(new float[1], 1, SearchParams.DEFAULT, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void paramsAreValidated() {
    assertThatThrownBy(() -> new HnswParams(1, 10, 10, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HnswParams(4, 0, 10, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HnswParams(4, 10, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(HnswParams.defaults().m0()).isEqualTo(32);
  }

  @Test
  void minHeapPopsInAscendingOrderAndGrows() {
    MinHeap heap = new MinHeap(1);
    float[] dists = {5, 1, 4, 1, 3, 2};
    for (int i = 0; i < dists.length; i++) {
      heap.push(i, dists[i]);
    }
    assertThat(heap.size()).isEqualTo(6);
    int[] order = new int[6];
    for (int i = 0; i < 6; i++) {
      order[i] = heap.peekOrd();
      heap.pop();
    }
    assertThat(order).containsExactly(1, 3, 5, 4, 2, 0);
    assertThat(heap.isEmpty()).isTrue();
    heap.push(7, 1f);
    heap.clear();
    assertThat(heap.isEmpty()).isTrue();
  }

  private static int reachableOnLayer0(HnswIndex index) {
    boolean[] seen = new boolean[index.size()];
    Deque<Integer> queue = new ArrayDeque<>();
    queue.add(index.entryPoint());
    seen[index.entryPoint()] = true;
    int reached = 0;
    while (!queue.isEmpty()) {
      int ord = queue.poll();
      reached++;
      for (int nbr : index.neighbours(ord, 0)) {
        if (!seen[nbr]) {
          seen[nbr] = true;
          queue.add(nbr);
        }
      }
    }
    return reached;
  }

  @Test
  void neighbourCopiesDoNotExposeInternalArrays() {
    VectorStore store = new VectorStore(4);
    HnswIndex index = build(store, Metric.L2, HnswParams.defaults(), 50, 4);
    int[] nbrs = index.neighbours(0, 0);
    Arrays.fill(nbrs, -1);
    assertThat(index.neighbours(0, 0)).doesNotContain(-1);
  }
}
