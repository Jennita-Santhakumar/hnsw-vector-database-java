package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Recall of HNSW against exact {@link FlatIndex} answers on 10k seeded random vectors. Measured
 * values are printed (visible in the test report's stdout) so they can be logged in
 * docs/RESULTS.md.
 */
class HnswRecallTest {

  static final int N = 10_000;
  static final int DIM = 32;
  static final int QUERIES = 200;
  static final int K = 10;

  /** Seeded data generators. */
  enum Dataset {
    /** i.i.d. standard Gaussian vectors. */
    UNIFORM,
    /** Gaussian mixture: 50 centres spread with sd 10, points with sd 1 around a random centre. */
    CLUSTERED;

    private static final int CLUSTERS = 50;

    Generator generator(long seed) {
      Random rnd = new Random(seed);
      if (this == UNIFORM) {
        return () -> HnswIndexTest.gaussian(rnd, DIM);
      }
      // Centres come from their own fixed seed so data and queries share the same clusters.
      Random centreRnd = new Random(12345);
      float[][] centres = new float[CLUSTERS][];
      for (int c = 0; c < CLUSTERS; c++) {
        centres[c] = HnswIndexTest.gaussian(centreRnd, DIM);
        for (int i = 0; i < DIM; i++) {
          centres[c][i] *= 10f;
        }
      }
      return () -> {
        float[] centre = centres[rnd.nextInt(CLUSTERS)];
        float[] v = HnswIndexTest.gaussian(rnd, DIM);
        for (int i = 0; i < DIM; i++) {
          v[i] += centre[i];
        }
        return v;
      };
    }
  }

  /** Supplies the next random vector. */
  interface Generator {
    float[] next();
  }

  /** A built HNSW index plus the exact index over the same store. */
  record Built(HnswIndex hnsw, FlatIndex flat, double buildSeconds) {}

  static Built build(Metric metric, HnswParams params, Dataset dataset) {
    VectorStore store = new VectorStore(DIM);
    Generator gen = dataset.generator(2024);
    HnswIndex hnsw = new HnswIndex(store, metric.distance(), params);
    FlatIndex flat = new FlatIndex(store, metric.distance());
    long start = System.nanoTime();
    for (int i = 0; i < N; i++) {
      float[] v = metric.prepare(gen.next());
      int ord = store.add(v);
      hnsw.add(ord, v);
      flat.add(ord, v);
    }
    return new Built(hnsw, flat, (System.nanoTime() - start) / 1e9);
  }

  /** Recall@K of the HNSW index vs exact search, averaged over seeded queries. */
  static double recallAtK(Metric metric, Built built, Dataset dataset, int ef) {
    Generator gen = dataset.generator(77);
    double total = 0;
    for (int q = 0; q < QUERIES; q++) {
      float[] query = metric.prepare(gen.next());
      int[] truth = built.flat().search(query, K, SearchParams.DEFAULT, null).ords();
      int[] got = built.hnsw().search(query, K, new SearchParams(ef), null).ords();
      total += IntStream.of(got).filter(o -> IntStream.of(truth).anyMatch(t -> t == o)).count();
    }
    return total / (QUERIES * (double) K);
  }

  @ParameterizedTest
  @EnumSource(Metric.class)
  void defaultParamsReachTargetRecallForEveryMetric(Metric metric) {
    Built built = build(metric, HnswParams.defaults(), Dataset.UNIFORM);
    double recall = recallAtK(metric, built, Dataset.UNIFORM, HnswParams.DEFAULT_EF_SEARCH);
    System.out.printf(
        Locale.ROOT,
        "HNSW defaults recall@%d=%.4f metric=%s data=UNIFORM n=%d dim=%d M=%d efC=%d ef=%d"
            + " build=%.1fs%n",
        K,
        recall,
        metric,
        N,
        DIM,
        HnswParams.DEFAULT_M,
        HnswParams.DEFAULT_EF_CONSTRUCTION,
        HnswParams.DEFAULT_EF_SEARCH,
        built.buildSeconds());
    assertThat(recall).isGreaterThanOrEqualTo(0.90);
  }

  /**
   * Logs simple vs heuristic selection side by side (task 1.5 comparison) and checks the paper's
   * claim where it applies: on clustered data the heuristic gives higher recall at low ef.
   */
  @Test
  void heuristicVersusSimpleSelection() {
    double[] clusteredAt16 = new double[NeighborSelection.values().length];
    for (Dataset dataset : Dataset.values()) {
      for (NeighborSelection selection : NeighborSelection.values()) {
        Built built = build(Metric.L2, HnswParams.defaults().withSelection(selection), dataset);
        double at16 = recallAtK(Metric.L2, built, dataset, 16);
        double at64 = recallAtK(Metric.L2, built, dataset, 64);
        System.out.printf(
            Locale.ROOT,
            "SELECTION data=%s selection=%s recall@10 ef16=%.4f ef64=%.4f build=%.1fs"
                + " graphBytes=%d%n",
            dataset,
            selection,
            at16,
            at64,
            built.buildSeconds(),
            built.hnsw().memoryBytes());
        assertThat(at64).isGreaterThanOrEqualTo(0.85);
        if (dataset == Dataset.CLUSTERED) {
          clusteredAt16[selection.ordinal()] = at16;
        }
      }
    }
    assertThat(clusteredAt16[NeighborSelection.HEURISTIC.ordinal()])
        .isGreaterThan(clusteredAt16[NeighborSelection.SIMPLE.ordinal()]);
  }
}
