package dev.thedal.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.HnswParams;
import dev.thedal.index.SearchParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionTest {

  @Test
  void upsertGetSearchDelete() {
    Collection c = new Collection(CollectionConfig.hnsw("docs", 2, Metric.L2));
    c.upsert(
        List.of(
            new Point("a", new float[] {0, 0}, Map.of("lang", "ta")),
            new Point("b", new float[] {5, 5}, Map.of("lang", "en"))));
    assertThat(c.get("a")).hasValueSatisfying(p -> assertThat(p.vector()).containsExactly(0, 0));
    assertThat(c.get("a").orElseThrow().metadata()).containsEntry("lang", "ta");
    assertThat(c.get("zzz")).isEmpty();

    SearchResponse r = c.search(new float[] {1, 1}, 2, SearchParams.DEFAULT, null);
    assertThat(r.hits()).extracting(SearchHit::id).containsExactly("a", "b");
    assertThat(r.strategy()).isEqualTo("UNFILTERED");
    assertThat(r.hits().get(0).score()).isEqualTo(2f);

    SearchResponse filtered =
        c.search(new float[] {1, 1}, 2, SearchParams.DEFAULT, new Filter.Eq("lang", "en"));
    assertThat(filtered.hits()).extracting(SearchHit::id).containsExactly("b");

    assertThat(c.delete("a")).isTrue();
    assertThat(c.delete("a")).isFalse();
    assertThat(c.stats().count()).isEqualTo(1);
    assertThat(c.name()).isEqualTo("docs");
  }

  @Test
  void batchesAreAllOrNothing() {
    Collection c = new Collection(CollectionConfig.flat("docs", 2, Metric.COSINE));
    c.upsert(List.of(new Point("keep", new float[] {1, 0})));
    List<Point> bad =
        List.of(new Point("x", new float[] {1, 1}), new Point("y", new float[] {0, 0}));
    assertThatThrownBy(() -> c.upsert(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("y")
        .hasMessageContaining("non-zero");
    assertThatThrownBy(() -> c.upsert(List.of(new Point("z", new float[] {1, 2, 3}))))
        .hasMessageContaining("expected dimension 2");
    assertThatThrownBy(() -> c.upsert(List.of(new Point("z", new float[] {Float.NaN, 1}))))
        .hasMessageContaining("finite");
    assertThat(c.stats().count()).isEqualTo(1);
    assertThat(c.get("x")).isEmpty();
  }

  @Test
  void repeatedIdInABatchKeepsTheLastVersion() {
    Collection c = new Collection(CollectionConfig.flat("docs", 1, Metric.L2));
    c.upsert(List.of(new Point("a", new float[] {1}), new Point("a", new float[] {2})));
    assertThat(c.get("a").orElseThrow().vector()).containsExactly(2);
    assertThat(c.stats().count()).isEqualTo(1);
  }

  @Test
  void cosineReturnsNormalizedVectors() {
    Collection c = new Collection(CollectionConfig.hnsw("docs", 2, Metric.COSINE));
    c.upsert(List.of(new Point("a", new float[] {3, 4})));
    assertThat(c.get("a").orElseThrow().vector())
        .containsExactly(new float[] {0.6f, 0.8f}, within(1e-6f));
  }

  @Test
  void compactsAutomaticallyPastTwentyPercentTombstones() {
    Collection c = new Collection(CollectionConfig.hnsw("docs", 2, Metric.L2));
    List<Point> batch = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      batch.add(new Point("p" + i, new float[] {i, -i}));
    }
    c.upsert(batch);
    for (int i = 0; i < 20; i++) {
      c.delete("p" + i);
    }
    assertThat(c.stats().tombstones()).isEqualTo(20);
    assertThat(c.stats().compactions()).isZero();
    c.delete("p20");
    CollectionStats stats = c.stats();
    assertThat(stats.tombstones()).isZero();
    assertThat(stats.compactions()).isEqualTo(1);
    assertThat(stats.count()).isEqualTo(79);
    assertThat(c.search(new float[] {50, -50}, 1, SearchParams.DEFAULT, null).hits())
        .extracting(SearchHit::id)
        .containsExactly("p50");

    c.compact();
    assertThat(c.stats().compactions()).isEqualTo(2);
    assertThat(c.stats().memoryBytes()).isPositive();
  }

  @Test
  void searchRejectsBadQueries() {
    Collection c = new Collection(CollectionConfig.hnsw("docs", 2, Metric.COSINE));
    c.upsert(List.of(new Point("a", new float[] {1, 0})));
    assertThatThrownBy(() -> c.search(new float[] {1, 0}, 0, SearchParams.DEFAULT, null))
        .hasMessageContaining("k must be");
    assertThatThrownBy(
            () -> c.search(new float[] {1, 0}, Collection.MAX_K + 1, SearchParams.DEFAULT, null))
        .hasMessageContaining("k must be");
    assertThatThrownBy(
            () -> c.search(new float[] {1, 0}, 1, new SearchParams(Collection.MAX_EF + 1), null))
        .hasMessageContaining("ef must be");
    assertThatThrownBy(() -> c.search(new float[] {1, 0, 0}, 1, SearchParams.DEFAULT, null))
        .hasMessageContaining("query: expected dimension 2");
    assertThatThrownBy(() -> c.search(new float[] {0, 0}, 1, SearchParams.DEFAULT, null))
        .hasMessageContaining("non-zero");
  }

  @Test
  void pointValidatesAndCopies() {
    float[] v = {1, 2};
    Point p = new Point("a", v);
    v[0] = 99;
    assertThat(p.vector()).containsExactly(1, 2);
    p.vector()[1] = 99;
    assertThat(p.vector()).containsExactly(1, 2);
    assertThatThrownBy(() -> new Point("", v)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Point("a", null)).hasMessageContaining("no vector");
    assertThatThrownBy(() -> new Point("a", v, Map.of("x", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configValidation() {
    assertThatThrownBy(() -> CollectionConfig.hnsw("bad name", 2, Metric.L2))
        .hasMessageContaining("collection name");
    assertThatThrownBy(() -> CollectionConfig.hnsw("x".repeat(65), 2, Metric.L2))
        .hasMessageContaining("collection name");
    assertThatThrownBy(() -> CollectionConfig.hnsw("ok", 0, Metric.L2)).hasMessageContaining("dim");
    assertThatThrownBy(() -> CollectionConfig.hnsw("ok", CollectionConfig.MAX_DIM + 1, Metric.L2))
        .hasMessageContaining("dim");
    assertThatThrownBy(() -> new CollectionConfig("ok", 2, null, IndexType.FLAT, null))
        .hasMessageContaining("required");
    CollectionConfig custom =
        new CollectionConfig("ok-1_A", 8, Metric.DOT, IndexType.HNSW, new HnswParams(8, 50, 20, 1));
    assertThat(custom.hnsw().m()).isEqualTo(8);
    assertThat(CollectionConfig.flat("f", 2, Metric.L2).hnsw()).isEqualTo(HnswParams.defaults());
  }

  @Test
  void managerCreatesListsGetsAndDrops() {
    CollectionManager manager = new CollectionManager();
    manager.create(CollectionConfig.hnsw("b", 2, Metric.L2));
    manager.create(CollectionConfig.flat("a", 3, Metric.DOT));
    assertThat(manager.list()).extracting(Collection::name).containsExactly("a", "b");
    assertThat(manager.get("a").config().dim()).isEqualTo(3);
    assertThatThrownBy(() -> manager.create(CollectionConfig.flat("a", 3, Metric.DOT)))
        .isInstanceOf(CollectionAlreadyExistsException.class)
        .hasMessageContaining("'a' already exists");
    manager.drop("a");
    assertThatThrownBy(() -> manager.get("a"))
        .isInstanceOf(CollectionNotFoundException.class)
        .hasMessageContaining("'a' does not exist");
    assertThatThrownBy(() -> manager.drop("a")).isInstanceOf(CollectionNotFoundException.class);
    assertThat(manager.list()).extracting(Collection::name).containsExactly("b");
  }
}
