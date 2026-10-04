package dev.thedal.internal.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.thedal.distance.Metric;
import dev.thedal.index.FlatIndex;
import dev.thedal.index.HnswIndex;
import dev.thedal.index.HnswParams;
import dev.thedal.index.IndexProvider;
import dev.thedal.index.SearchParams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class PointSetTest {

  static final IndexProvider FLAT = (store, metric) -> new FlatIndex(store, metric.distance());
  static final IndexProvider HNSW =
      (store, metric) -> new HnswIndex(store, metric.distance(), HnswParams.defaults());

  static float[] gaussian(Random rnd, int dim) {
    float[] v = new float[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = (float) rnd.nextGaussian();
    }
    return v;
  }

  private static List<String> ids(List<PointSet.Hit> hits) {
    return hits.stream().map(PointSet.Hit::id).toList();
  }

  @Test
  void upsertGetAndDelete() {
    PointSet points = new PointSet(2, Metric.L2, FLAT);
    points.upsert("a", new float[] {1, 2});
    points.upsert("b", new float[] {3, 4});
    assertThat(points.size()).isEqualTo(2);
    assertThat(points.get("a")).containsExactly(1, 2);
    assertThat(points.get("missing")).isNull();

    assertThat(points.delete("a")).isTrue();
    assertThat(points.delete("a")).isFalse();
    assertThat(points.get("a")).isNull();
    assertThat(points.size()).isEqualTo(1);
    assertThat(points.tombstoneCount()).isEqualTo(1);
    assertThat(ids(points.searchByOrdinal(new float[] {1, 2}, 5, SearchParams.DEFAULT, null)))
        .containsExactly("b");
  }

  @Test
  void upsertOfAnExistingIdReplacesItAndTombstonesTheOldOrdinal() {
    PointSet points = new PointSet(2, Metric.L2, FLAT);
    int first = points.upsert("a", new float[] {0, 0});
    int second = points.upsert("a", new float[] {10, 10});
    assertThat(second).isNotEqualTo(first);
    assertThat(points.ordOf("a")).isEqualTo(second);
    assertThat(points.idOf(first)).isNull();
    assertThat(points.size()).isEqualTo(1);
    assertThat(points.tombstoneCount()).isEqualTo(1);
    List<PointSet.Hit> hits =
        points.searchByOrdinal(new float[] {0, 0}, 5, SearchParams.DEFAULT, null);
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).distance()).isEqualTo(200f);
  }

  @Test
  void invalidInputChangesNothing() {
    PointSet points = new PointSet(2, Metric.COSINE, HNSW);
    points.upsert("a", new float[] {1, 0});
    assertThatThrownBy(() -> points.upsert("", new float[] {1, 1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> points.upsert("b", new float[] {0, 0}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> points.upsert("b", new float[] {1, 1, 1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> points.upsert("b", new float[] {Float.NaN, 1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(points.size()).isEqualTo(1);
    assertThat(points.tombstoneCount()).isZero();
    points.upsert("b", new float[] {0, 3}); // ordinals are still dense after the failures
    assertThat(points.ordOf("b")).isEqualTo(1);
  }

  @Test
  void cosineStoresNormalizedVectors() {
    PointSet points = new PointSet(2, Metric.COSINE, FLAT);
    points.upsert("a", new float[] {3, 4});
    assertThat(points.get("a")).containsExactly(new float[] {0.6f, 0.8f}, within(1e-6f));
    assertThat(points.metric()).isEqualTo(Metric.COSINE);
    assertThat(points.dim()).isEqualTo(2);
  }

  @Test
  void hnswSkipsDeletedPointsButKeepsRecallThroughThem() {
    int n = 5_000;
    int dim = 24;
    PointSet hnsw = new PointSet(dim, Metric.L2, HNSW);
    PointSet exact = new PointSet(dim, Metric.L2, FLAT);
    Random rnd = new Random(31);
    for (int i = 0; i < n; i++) {
      float[] v = gaussian(rnd, dim);
      hnsw.upsert("p" + i, v);
      exact.upsert("p" + i, v);
    }
    Set<String> deleted = new HashSet<>();
    for (int i = 0; i < n; i++) {
      if (rnd.nextDouble() < 0.15) {
        hnsw.delete("p" + i);
        exact.delete("p" + i);
        deleted.add("p" + i);
      }
    }
    assertThat(hnsw.needsCompaction()).isFalse();

    double hits = 0;
    int queries = 100;
    for (int q = 0; q < queries; q++) {
      float[] query = gaussian(rnd, dim);
      List<String> got = ids(hnsw.searchByOrdinal(query, 10, SearchParams.DEFAULT, null));
      List<String> truth = ids(exact.searchByOrdinal(query, 10, SearchParams.DEFAULT, null));
      assertThat(got).hasSize(10).doesNotContainAnyElementsOf(deleted);
      hits += got.stream().filter(truth::contains).count();
    }
    assertThat(hits / (queries * 10.0)).isGreaterThanOrEqualTo(0.9);
  }

  @Test
  void compactionThresholdIsStrictlyAbove20Percent() {
    PointSet points = new PointSet(1, Metric.L2, FLAT);
    for (int i = 0; i < 10; i++) {
      points.upsert("p" + i, new float[] {i});
    }
    points.delete("p0");
    points.delete("p1");
    assertThat(points.tombstoneRatio()).isEqualTo(0.2);
    assertThat(points.needsCompaction()).isFalse();
    assertThat(points.compactIfNeeded()).isNull();
    points.delete("p2");
    assertThat(points.needsCompaction()).isTrue();
  }

  @Test
  void compactionRebuildsFromLivePointsAndReturnsTheRemap() {
    PointSet points = new PointSet(4, Metric.L2, HNSW);
    Random rnd = new Random(8);
    Map<String, float[]> live = new HashMap<>();
    for (int i = 0; i < 300; i++) {
      float[] v = gaussian(rnd, 4);
      points.upsert("p" + i, v);
      live.put("p" + i, v);
    }
    for (int i = 0; i < 300; i += 3) {
      points.delete("p" + i);
      live.remove("p" + i);
    }
    points.upsert("p1", new float[] {9, 9, 9, 9}); // overwrite -> another tombstone
    live.put("p1", new float[] {9, 9, 9, 9});
    int oldOrdOfP2 = points.ordOf("p2");
    assertThat(points.needsCompaction()).isTrue();

    int[] remap = points.compactIfNeeded();

    assertThat(remap).hasSize(301);
    assertThat(points.tombstoneCount()).isZero();
    assertThat(points.size()).isEqualTo(live.size());
    assertThat(remap[0]).isEqualTo(-1);
    assertThat(points.ordOf("p2")).isEqualTo(remap[oldOrdOfP2]);
    long kept = java.util.Arrays.stream(remap).filter(o -> o >= 0).count();
    assertThat(kept).isEqualTo(live.size());
    live.forEach((id, v) -> assertThat(points.get(id)).containsExactly(v));
    assertThat(ids(points.searchByOrdinal(new float[] {9, 9, 9, 9}, 1, SearchParams.DEFAULT, null)))
        .containsExactly("p1");
    assertThat(points.memoryBytes()).isPositive();
  }

  @Test
  void extraFilterCombinesWithTombstones() {
    PointSet points = new PointSet(1, Metric.L2, FLAT);
    for (int i = 0; i < 6; i++) {
      points.upsert("p" + i, new float[] {i});
    }
    points.delete("p2");
    List<PointSet.Hit> hits =
        points.searchByOrdinal(new float[] {0}, 10, SearchParams.DEFAULT, o -> o % 2 == 0);
    assertThat(ids(hits)).containsExactly("p0", "p4");
  }

  /**
   * Random upsert/delete sequences over a small id pool: the point set must always agree with a map
   * model, and exact search must equal brute force over the model.
   */
  @Property(tries = 200)
  void behavesLikeAMapModel(
      @ForAll long seed, @ForAll @IntRange(min = 1, max = 120) int operations) {
    Random rnd = new Random(seed);
    PointSet points = new PointSet(3, Metric.L2, FLAT);
    Map<String, float[]> model = new HashMap<>();
    for (int op = 0; op < operations; op++) {
      String id = "id" + rnd.nextInt(15);
      if (rnd.nextInt(3) == 0) {
        assertThat(points.delete(id)).isEqualTo(model.remove(id) != null);
      } else {
        float[] v = gaussian(rnd, 3);
        points.upsert(id, v);
        model.put(id, v);
      }
      if (rnd.nextInt(10) == 0) {
        points.compactIfNeeded();
      }
    }
    assertThat(points.size()).isEqualTo(model.size());
    model.forEach((id, v) -> assertThat(points.get(id)).containsExactly(v));

    float[] query = gaussian(rnd, 3);
    List<Map.Entry<String, float[]>> entries = new ArrayList<>(model.entrySet());
    entries.sort(Comparator.comparingDouble(e -> squaredL2(e.getValue(), query)));
    List<Float> expected =
        entries.stream().limit(5).map(e -> (float) squaredL2(e.getValue(), query)).toList();
    List<Float> got =
        points.searchByOrdinal(query, 5, SearchParams.DEFAULT, null).stream()
            .map(PointSet.Hit::distance)
            .toList();
    assertThat(got).hasSameSizeAs(expected);
    for (int i = 0; i < got.size(); i++) {
      assertThat(got.get(i)).isCloseTo(expected.get(i), within(1e-4f));
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
