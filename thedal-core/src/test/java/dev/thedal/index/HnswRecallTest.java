package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Recall of HNSW against exact {@link FlatIndex} answers on 10k seeded random vectors. The measured
 * value is printed (visible in the test report's stdout) so it can be logged in docs/RESULTS.md.
 */
class HnswRecallTest {

  static final int N = 10_000;
  static final int DIM = 32;
  static final int QUERIES = 200;
  static final int K = 10;

  /** Recall@K of {@code hnsw} vs exact search, averaged over seeded random queries. */
  static double recallAtK(
      VectorStore store, Metric metric, Index hnsw, Index exact, SearchParams params, long seed) {
    Random rnd = new Random(seed);
    double total = 0;
    for (int q = 0; q < QUERIES; q++) {
      float[] query = metric.prepare(HnswIndexTest.gaussian(rnd, DIM));
      int[] truth = exact.search(query, K, SearchParams.DEFAULT, null).ords();
      int[] got = hnsw.search(query, K, params, null).ords();
      total += IntStream.of(got).filter(o -> IntStream.of(truth).anyMatch(t -> t == o)).count();
    }
    return total / (QUERIES * (double) K);
  }

  @ParameterizedTest
  @EnumSource(Metric.class)
  void recallAt10AgainstFlatIndex(Metric metric) {
    VectorStore store = new VectorStore(DIM);
    Random rnd = new Random(2024);
    HnswIndex hnsw = new HnswIndex(store, metric.distance(), HnswParams.defaults());
    FlatIndex flat = new FlatIndex(store, metric.distance());
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      float[] v = metric.prepare(HnswIndexTest.gaussian(rnd, DIM));
      int ord = store.add(v);
      hnsw.add(ord, v);
      flat.add(ord, v);
    }
    double buildSeconds = (System.nanoTime() - start) / 1e9;

    double recall = recallAtK(store, metric, hnsw, flat, SearchParams.DEFAULT, 77);

    System.out.printf(
        Locale.ROOT,
        "HNSW simple-selection recall@%d=%.4f metric=%s n=%d dim=%d M=%d efC=%d efSearch=%d"
            + " build=%.1fs%n",
        K,
        recall,
        metric,
        N,
        DIM,
        HnswParams.DEFAULT_M,
        HnswParams.DEFAULT_EF_CONSTRUCTION,
        HnswParams.DEFAULT_EF_SEARCH,
        buildSeconds);
    assertThat(recall).isGreaterThanOrEqualTo(0.90);
  }
}
