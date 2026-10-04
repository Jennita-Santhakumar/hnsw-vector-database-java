package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.util.Comparator;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class FlatIndexTest {

  /** Builds a store + flat index with {@code n} random vectors from {@code seed}. */
  private static FlatIndex build(VectorStore store, Metric metric, int n, long seed) {
    Random rnd = new Random(seed);
    FlatIndex index = new FlatIndex(store, metric.distance());
    for (int i = 0; i < n; i++) {
      float[] v = new float[store.dim()];
      for (int j = 0; j < v.length; j++) {
        v[j] = (float) rnd.nextGaussian();
      }
      v = metric.prepare(v);
      index.add(store.add(v), v);
    }
    return index;
  }

  /** Reference answer: score everything, sort by (distance, ordinal), take k. */
  private static int[] naiveTopK(
      VectorStore store, Metric metric, float[] query, int k, IntPredicate allowed) {
    return IntStream.range(0, store.size())
        .filter(allowed)
        .boxed()
        .sorted(
            Comparator.<Integer>comparingDouble(o -> store.distance(metric.distance(), query, o))
                .thenComparingInt(o -> o))
        .limit(k)
        .mapToInt(Integer::intValue)
        .toArray();
  }

  @Property(tries = 150)
  void topKEqualsNaiveSort(
      @ForAll Metric metric,
      @ForAll @IntRange(max = 400) int n,
      @ForAll @IntRange(min = 1, max = 16) int dim,
      @ForAll @IntRange(min = 1, max = 50) int k,
      @ForAll long seed) {
    VectorStore store = new VectorStore(dim);
    FlatIndex index = build(store, metric, n, seed);
    float[] query = metric.prepare(randomNonZero(dim, seed + 1));

    SearchResult result = index.search(query, k, SearchParams.DEFAULT, null);

    assertThat(result.ords()).containsExactly(naiveTopK(store, metric, query, k, o -> true));
    for (int i = 0; i < result.size(); i++) {
      assertThat(result.distance(i))
          .isEqualTo(store.distance(metric.distance(), query, result.ord(i)));
    }
  }

  @Property(tries = 100)
  void filteredTopKEqualsNaiveFilteredSort(
      @ForAll @IntRange(max = 300) int n,
      @ForAll @IntRange(min = 1, max = 30) int k,
      @ForAll @IntRange(min = 1, max = 7) int modulus,
      @ForAll long seed) {
    VectorStore store = new VectorStore(8);
    FlatIndex index = build(store, Metric.L2, n, seed);
    float[] query = randomNonZero(8, ~seed);
    IntPredicate allowed = o -> o % modulus == 0;

    SearchResult result = index.search(query, k, SearchParams.DEFAULT, allowed);

    assertThat(result.ords()).containsExactly(naiveTopK(store, Metric.L2, query, k, allowed));
  }

  @Test
  void findsExactNeighboursInAKnownLayout() {
    VectorStore store = new VectorStore(1);
    FlatIndex index = new FlatIndex(store, Metric.L2.distance());
    for (float x : new float[] {0, 10, 3, 7, 4}) {
      index.add(store.add(new float[] {x}), new float[] {x});
    }
    SearchResult r = index.search(new float[] {5}, 3, SearchParams.DEFAULT, null);
    // Values by ordinal: 0->0, 1->10, 2->3, 3->7, 4->4. From 5: ord 4 (d=1), then ords 2 and 3
    // tie at d=4 and the lower ordinal wins.
    assertThat(r.ords()).containsExactly(4, 2, 3);
    assertThat(r.distance(0)).isEqualTo(1f);
  }

  @Test
  void returnsFewerThanKWhenNotEnoughEligibleVectors() {
    VectorStore store = new VectorStore(2);
    FlatIndex index = build(store, Metric.L2, 3, 1);
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, null).size()).isEqualTo(3);
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, o -> o == 1).ords())
        .containsExactly(1);
    assertThat(index.search(new float[2], 10, SearchParams.DEFAULT, o -> false).size()).isZero();
    assertThat(index.size()).isEqualTo(3);
    assertThat(index.memoryBytes()).isZero();
  }

  @Test
  void emptyIndexReturnsNothing() {
    FlatIndex index = new FlatIndex(new VectorStore(2), Metric.DOT.distance());
    assertThat(index.search(new float[2], 5, new SearchParams(32), null).size()).isZero();
  }

  @Test
  void rejectsInvalidArguments() {
    VectorStore store = new VectorStore(2);
    FlatIndex index = build(store, Metric.L2, 2, 7);
    assertThatThrownBy(() -> index.search(new float[2], 0, SearchParams.DEFAULT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> index.search(new float[3], 1, SearchParams.DEFAULT, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimension mismatch");
    assertThatThrownBy(() -> new SearchParams(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOutOfOrderOrUnstoredOrdinals() {
    VectorStore store = new VectorStore(2);
    FlatIndex index = new FlatIndex(store, Metric.L2.distance());
    store.add(new float[2]);
    assertThatThrownBy(() -> index.add(1, new float[2]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected ordinal 0");
    index.add(0, new float[2]);
    assertThatThrownBy(() -> index.add(1, new float[2]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in the vector store");
  }

  private static float[] randomNonZero(int dim, long seed) {
    Random rnd = new Random(seed);
    float[] v = new float[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = (float) rnd.nextGaussian();
    }
    v[0] += 0.5f; // never all-zero, so cosine can normalize it
    return v;
  }
}
