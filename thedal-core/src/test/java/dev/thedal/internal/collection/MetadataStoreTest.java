package dev.thedal.internal.collection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thedal.filter.Filter;
import dev.thedal.filter.Metadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

class MetadataStoreTest {

  @Test
  void indexesEqInAndRange() {
    MetadataStore store = new MetadataStore();
    store.put(0, Metadata.normalize(Map.of("lang", "ta", "year", 2020)));
    store.put(1, Metadata.normalize(Map.of("lang", "en", "year", 2022)));
    store.put(2, Metadata.normalize(Map.of("lang", "ta", "year", 2024, "draft", true)));

    assertThat(store.match(new Filter.Eq("lang", "ta")).toArray()).containsExactly(0, 2);
    assertThat(store.match(new Filter.Eq("year", 2022)).toArray()).containsExactly(1);
    assertThat(store.match(new Filter.In("lang", List.of("en", "fr"))).toArray())
        .containsExactly(1);
    assertThat(store.match(new Filter.Range("year", null, 2021.0, null, null)).toArray())
        .containsExactly(1, 2);
    assertThat(store.match(new Filter.Range("year", 2020.0, null, 2024.0, null)).toArray())
        .containsExactly(1);
    assertThat(store.match(new Filter.Range("lang", null, 0.0, null, null)).isEmpty()).isTrue();
    assertThat(store.match(new Filter.Eq("missing", 1)).isEmpty()).isTrue();
    assertThat(store.match(new Filter.Range("missing", 1.0, null, null, null)).isEmpty()).isTrue();
    assertThat(store.get(2)).containsEntry("draft", true);
    assertThat(store.get(99)).isEmpty();
  }

  @Test
  void matchReturnsCopiesThatCallersMayModify() {
    MetadataStore store = new MetadataStore();
    store.put(0, Metadata.normalize(Map.of("lang", "ta")));
    RoaringBitmap first = store.match(new Filter.Eq("lang", "ta"));
    first.add(42);
    assertThat(store.match(new Filter.Eq("lang", "ta")).toArray()).containsExactly(0);
  }

  @Test
  void removeAndReplaceUpdateTheIndexes() {
    MetadataStore store = new MetadataStore();
    store.put(0, Metadata.normalize(Map.of("lang", "ta", "year", 2020)));
    store.put(0, Metadata.normalize(Map.of("lang", "en")));
    assertThat(store.match(new Filter.Eq("lang", "ta")).isEmpty()).isTrue();
    assertThat(store.match(new Filter.Range("year", null, 0.0, null, null)).isEmpty()).isTrue();
    store.remove(0);
    store.remove(0);
    store.remove(7);
    assertThat(store.match(new Filter.Eq("lang", "en")).isEmpty()).isTrue();
    assertThat(store.get(0)).isEmpty();
  }

  /** Indexed evaluation must equal the reference evaluator, also after removes and a remap. */
  @Property(tries = 300)
  void indexedMatchEqualsReferenceEvaluation(
      @ForAll long seed, @ForAll @IntRange(min = 0, max = 150) int points) {
    Random rnd = new Random(seed);
    MetadataStore store = new MetadataStore();
    List<Map<String, Object>> model = new ArrayList<>();
    for (int ord = 0; ord < points; ord++) {
      Map<String, Object> fields = RandomFilters.metadata(rnd);
      store.put(ord, fields);
      model.add(fields);
    }
    int[] remap = new int[points];
    int next = 0;
    for (int ord = 0; ord < points; ord++) {
      if (rnd.nextInt(4) == 0) {
        store.remove(ord);
        model.set(ord, null);
        remap[ord] = -1;
      } else {
        remap[ord] = next++;
      }
    }
    if (rnd.nextBoolean()) {
      store.remap(remap);
      List<Map<String, Object>> remapped = new ArrayList<>();
      for (Map<String, Object> fields : model) {
        if (fields != null) {
          remapped.add(fields);
        }
      }
      model = remapped;
    }

    for (int i = 0; i < 10; i++) {
      Filter filter = RandomFilters.filter(rnd, 2);
      RoaringBitmap expected = new RoaringBitmap();
      for (int ord = 0; ord < model.size(); ord++) {
        if (model.get(ord) != null && filter.matches(model.get(ord))) {
          expected.add(ord);
        }
      }
      assertThat(store.match(filter).toArray())
          .as(filter.toString())
          .containsExactly(expected.toArray());
    }
  }
}
