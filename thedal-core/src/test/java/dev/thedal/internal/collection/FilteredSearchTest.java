package dev.thedal.internal.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.SearchParams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Task 1.8: filtered search correctness (flat) and recall (HNSW), and strategy selection. */
class FilteredSearchTest {

  /** Forces each path: always exact scan, always filtered index, and the default split. */
  private static final List<PointSet.FilterConfig> CONFIGS =
      List.of(
          new PointSet.FilterConfig(1.0, 4096),
          new PointSet.FilterConfig(0.0, 4096),
          PointSet.FilterConfig.DEFAULT);

  /** Task 1.8 property: with a flat index, filtered results are exactly brute force. */
  @Property(tries = 150)
  void flatFilteredSearchEqualsBruteForce(
      @ForAll long seed,
      @ForAll @IntRange(min = 0, max = 300) int n,
      @ForAll @IntRange(min = 1, max = 20) int k) {
    Random rnd = new Random(seed);
    List<String> ids = new ArrayList<>();
    Map<String, float[]> vectors = new HashMap<>();
    Map<String, Map<String, Object>> fields = new HashMap<>();
    for (int i = 0; i < n; i++) {
      String id = "p" + i;
      ids.add(id);
      vectors.put(id, PointSetTest.gaussian(rnd, 4));
      fields.put(id, RandomFilters.metadata(rnd));
    }
    for (PointSet.FilterConfig config : CONFIGS) {
      PointSet points = new PointSet(4, Metric.L2, PointSetTest.FLAT, config);
      for (String id : ids) {
        points.upsert(id, vectors.get(id), fields.get(id));
      }
      for (int q = 0; q < 5; q++) {
        Filter filter = RandomFilters.filter(rnd, 2);
        float[] query = PointSetTest.gaussian(rnd, 4);
        List<String> expected =
            ids.stream()
                .filter(id -> filter.matches(fields.get(id)))
                .sorted(
                    Comparator.<String>comparingDouble(id -> squaredL2(vectors.get(id), query))
                        .thenComparingInt(id -> Integer.parseInt(id.substring(1))))
                .limit(k)
                .toList();
        PointSet.SearchOutcome outcome = points.search(query, k, SearchParams.DEFAULT, filter);
        assertThat(outcome.hits().stream().map(PointSet.Hit::id).toList())
            .as("%s via %s", filter, outcome.strategy())
            .isEqualTo(expected);
        for (PointSet.Hit hit : outcome.hits()) {
          assertThat(hit.metadata()).isEqualTo(fields.get(hit.id()));
        }
      }
    }
  }

  @Test
  void strategyFollowsSelectivity() {
    PointSet points = new PointSet(8, Metric.L2, PointSetTest.HNSW);
    Random rnd = new Random(3);
    for (int i = 0; i < 2_000; i++) {
      Map<String, Object> meta = new HashMap<>();
      meta.put("bucket", i % 100); // 1% per bucket
      meta.put("half", i % 2 == 0);
      points.upsert("p" + i, PointSetTest.gaussian(rnd, 8), meta);
    }
    float[] query = PointSetTest.gaussian(rnd, 8);

    PointSet.SearchOutcome rare =
        points.search(query, 5, SearchParams.DEFAULT, new Filter.Eq("bucket", 7));
    assertThat(rare.strategy()).isEqualTo(PointSet.Strategy.EXACT_SCAN);
    assertThat(rare.selectivity()).isEqualTo(0.01);
    assertThat(rare.hits())
        .hasSize(5)
        .allSatisfy(h -> assertThat(h.metadata()).containsEntry("bucket", 7.0));

    PointSet.SearchOutcome common =
        points.search(query, 5, SearchParams.DEFAULT, new Filter.Eq("half", true));
    assertThat(common.strategy()).isEqualTo(PointSet.Strategy.FILTERED_INDEX);
    assertThat(common.hits())
        .hasSize(5)
        .allSatisfy(h -> assertThat(h.metadata()).containsEntry("half", true));

    PointSet.SearchOutcome none =
        points.search(query, 5, SearchParams.DEFAULT, new Filter.Eq("bucket", 1000));
    assertThat(none.strategy()).isEqualTo(PointSet.Strategy.NO_MATCH);
    assertThat(none.hits()).isEmpty();

    PointSet.SearchOutcome all = points.search(query, 5, SearchParams.DEFAULT, (Filter) null);
    assertThat(all.strategy()).isEqualTo(PointSet.Strategy.UNFILTERED);
    assertThat(all.hits()).hasSize(5);

    assertThatThrownBy(
            () -> points.search(query, 0, SearchParams.DEFAULT, new Filter.Eq("half", true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletedAndReplacedPointsNeverMatchFilters() {
    PointSet points = new PointSet(2, Metric.L2, PointSetTest.FLAT);
    points.upsert("a", new float[] {0, 0}, Map.of("lang", "ta"));
    points.upsert("b", new float[] {1, 1}, Map.of("lang", "ta"));
    points.upsert("b", new float[] {1, 1}, Map.of("lang", "en"));
    points.delete("a");
    Filter ta = new Filter.Eq("lang", "ta");
    assertThat(points.search(new float[2], 5, SearchParams.DEFAULT, ta).hits()).isEmpty();
    assertThat(points.getMetadata("b")).containsEntry("lang", "en");
    assertThat(points.getMetadata("a")).isEmpty();
    points.compact();
    assertThat(
            points
                .search(new float[2], 5, SearchParams.DEFAULT, new Filter.Eq("lang", "en"))
                .hits())
        .extracting(PointSet.Hit::id)
        .containsExactly("b");
  }

  /** HNSW path recall vs exact filtered answers at several selectivities (PRD target >= 0.9). */
  @Test
  void hnswFilteredRecallAtSeveralSelectivities() {
    int n = 10_000;
    int dim = 32;
    PointSet hnsw = new PointSet(dim, Metric.L2, PointSetTest.HNSW);
    PointSet exact =
        new PointSet(dim, Metric.L2, PointSetTest.FLAT, new PointSet.FilterConfig(1.0, 1));
    Random rnd = new Random(2024);
    for (int i = 0; i < n; i++) {
      float[] v = PointSetTest.gaussian(rnd, dim);
      Map<String, Object> meta = Map.of("pct", (double) (i % 100));
      hnsw.upsert("p" + i, v, meta);
      exact.upsert("p" + i, v, meta);
    }
    for (int percent : new int[] {5, 10, 30, 60}) {
      Filter filter = new Filter.Range("pct", null, null, (double) percent, null);
      double hits = 0;
      int queries = 100;
      PointSet.Strategy strategy = null;
      for (int q = 0; q < queries; q++) {
        float[] query = PointSetTest.gaussian(rnd, dim);
        PointSet.SearchOutcome got = hnsw.search(query, 10, SearchParams.DEFAULT, filter);
        List<String> truth =
            exact.search(query, 10, SearchParams.DEFAULT, filter).hits().stream()
                .map(PointSet.Hit::id)
                .toList();
        strategy = got.strategy();
        hits += got.hits().stream().filter(h -> truth.contains(h.id())).count();
      }
      double recall = hits / (queries * 10.0);
      System.out.printf(
          Locale.ROOT,
          "FILTERED HNSW selectivity=%d%% strategy=%s recall@10=%.4f n=%d dim=%d M=16 efC=200"
              + " baseEf=64%n",
          percent,
          strategy,
          recall,
          n,
          dim);
      assertThat(strategy).isEqualTo(PointSet.Strategy.FILTERED_INDEX);
      assertThat(recall).isGreaterThanOrEqualTo(0.9);
    }
  }

  private static double squaredL2(float[] a, float[] b) {
    double s = 0;
    for (int i = 0; i < a.length; i++) {
      double d = (double) a[i] - b[i];
      s += d * d;
    }
    return s;
  }
}
