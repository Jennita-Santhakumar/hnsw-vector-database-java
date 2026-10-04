package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Task 1.6: efSearch, reusable per-thread search state, and recall with default parameters. */
class HnswSearchTest {

  @Test
  void visitedSetForgetsOnResetAndGrows() {
    VisitedSet visited = new VisitedSet();
    visited.reset(4);
    assertThat(visited.visit(2)).isTrue();
    assertThat(visited.visit(2)).isFalse();
    visited.reset(4);
    assertThat(visited.visit(2)).isTrue();
    visited.reset(100);
    assertThat(visited.visit(99)).isTrue();
    assertThat(visited.visit(2)).isTrue();
  }

  @Test
  void visitedSetClearsStaleMarksWhenTheGenerationWraps() {
    VisitedSet visited = new VisitedSet(-2);
    visited.reset(3); // generation -1
    assertThat(visited.visit(1)).isTrue();
    visited.reset(3); // wraps to 0 -> cleared, generation 1
    assertThat(visited.visit(1)).isTrue();
    assertThat(visited.visit(0)).isTrue();
    assertThat(visited.visit(0)).isFalse();
  }

  @Test
  void boundedHeapResetChangesCapacityWithoutLosingCorrectness() {
    BoundedMaxHeap heap = new BoundedMaxHeap(2);
    heap.offer(1, 1f);
    heap.offer(2, 2f);
    heap.reset(4);
    assertThat(heap.size()).isZero();
    for (int i = 0; i < 6; i++) {
      heap.offer(i, 10f - i);
    }
    assertThat(heap.drainSorted().ords()).containsExactly(5, 4, 3, 2);
    heap.reset(1);
    heap.offer(7, 3f);
    heap.offer(8, 1f);
    assertThat(heap.drainSorted().ords()).containsExactly(8);
  }

  @Test
  void largerEfNeverLowersRecall() {
    HnswRecallTest.Built built =
        HnswRecallTest.build(Metric.L2, HnswParams.defaults(), HnswRecallTest.Dataset.UNIFORM);
    double previous = 0;
    for (int ef : new int[] {10, 32, 128, 512}) {
      double recall =
          HnswRecallTest.recallAtK(Metric.L2, built, HnswRecallTest.Dataset.UNIFORM, ef);
      assertThat(recall).isGreaterThanOrEqualTo(previous);
      previous = recall;
    }
    assertThat(previous).isGreaterThan(0.99);
  }

  @Test
  void searchAllocatesOnlyItsResult() {
    VectorStore store = new VectorStore(16);
    HnswIndex index = HnswIndexTest.build(store, Metric.L2, HnswParams.defaults(), 10_000, 3);
    Random rnd = new Random(5);
    float[][] queries = new float[500][];
    for (int i = 0; i < queries.length; i++) {
      queries[i] = HnswIndexTest.gaussian(rnd, 16);
    }
    for (int round = 0; round < 20; round++) { // warm up the JIT and the scratch buffers
      for (float[] q : queries) {
        index.search(q, 10, SearchParams.DEFAULT, null);
      }
    }
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long tid = Thread.currentThread().threadId();
    long before = threads.getThreadAllocatedBytes(tid);
    for (float[] q : queries) {
      index.search(q, 10, SearchParams.DEFAULT, null);
    }
    double perQuery = (threads.getThreadAllocatedBytes(tid) - before) / (double) queries.length;
    System.out.printf(Locale.ROOT, "HNSW search allocation: %.0f bytes/query (k=10)%n", perQuery);
    // Result = SearchResult + int[10] + float[10] ~ 140 bytes. A per-query visited array over
    // 10k nodes alone would cost >= 10 KB.
    assertThat(perQuery).isLessThan(1024);
  }

  @Test
  void concurrentSearchesMatchSequentialResults() throws Exception {
    VectorStore store = new VectorStore(16);
    HnswIndex index = HnswIndexTest.build(store, Metric.COSINE, HnswParams.defaults(), 5_000, 8);
    Random rnd = new Random(13);
    List<float[]> queries = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      queries.add(Metric.COSINE.prepare(HnswIndexTest.gaussian(rnd, 16)));
    }
    List<int[]> sequential = new ArrayList<>();
    for (float[] q : queries) {
      sequential.add(index.search(q, 10, SearchParams.DEFAULT, null).ords());
    }

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<int[]>> futures = new ArrayList<>();
      for (int round = 0; round < 4; round++) {
        for (float[] q : queries) {
          futures.add(pool.submit(() -> index.search(q, 10, SearchParams.DEFAULT, null).ords()));
        }
      }
      for (int i = 0; i < futures.size(); i++) {
        assertThat(futures.get(i).get()).containsExactly(sequential.get(i % queries.size()));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Task 1.6 property: recall@10 >= 0.9 on random data with default parameters. Fixed seed: recall
   * is statistical, so CI must not depend on which random cases a run happens to draw.
   */
  @Property(tries = 12, seed = "20261004")
  void defaultParamsGiveRecallAtLeast90Percent(
      @ForAll Metric metric,
      @ForAll @IntRange(min = 500, max = 4_000) int n,
      @ForAll @IntRange(min = 4, max = 48) int dim,
      @ForAll long seed) {
    VectorStore store = new VectorStore(dim);
    HnswIndex hnsw = new HnswIndex(store, metric.distance(), HnswParams.defaults());
    FlatIndex flat = new FlatIndex(store, metric.distance());
    Random rnd = new Random(seed);
    for (int i = 0; i < n; i++) {
      float[] v = metric.prepare(nonZero(rnd, dim));
      int ord = store.add(v);
      hnsw.add(ord, v);
      flat.add(ord, v);
    }
    int queries = 50;
    double hits = 0;
    for (int q = 0; q < queries; q++) {
      float[] query = metric.prepare(nonZero(rnd, dim));
      int[] truth = flat.search(query, 10, SearchParams.DEFAULT, null).ords();
      int[] got = hnsw.search(query, 10, SearchParams.DEFAULT, null).ords();
      hits += IntStream.of(got).filter(o -> IntStream.of(truth).anyMatch(t -> t == o)).count();
    }
    assertThat(hits / (queries * 10.0)).isGreaterThanOrEqualTo(0.9);
  }

  private static float[] nonZero(Random rnd, int dim) {
    float[] v = HnswIndexTest.gaussian(rnd, dim);
    v[0] += 1e-3f;
    return v;
  }
}
