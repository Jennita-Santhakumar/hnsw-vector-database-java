package dev.thedal.internal.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.distance.Metric;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class VectorStoreTest {

  @Test
  void assignsDenseOrdinalsAndRoundTrips() {
    VectorStore store = new VectorStore(3);
    assertThat(store.add(new float[] {1, 2, 3})).isZero();
    assertThat(store.add(new float[] {4, 5, 6})).isEqualTo(1);
    assertThat(store.size()).isEqualTo(2);
    assertThat(store.dim()).isEqualTo(3);
    assertThat(store.get(0)).containsExactly(1, 2, 3);
    assertThat(store.get(1)).containsExactly(4, 5, 6);
  }

  @Test
  void vectorsAreContiguousWithinASegment() {
    VectorStore store = new VectorStore(2, 4);
    for (int i = 0; i < 4; i++) {
      store.add(new float[] {i, i});
    }
    float[] segment = store.segment(0);
    for (int ord = 0; ord < 4; ord++) {
      assertThat(store.segment(ord)).isSameAs(segment);
      assertThat(store.offset(ord)).isEqualTo(ord * 2);
    }
    assertThat(segment).containsExactly(0, 0, 1, 1, 2, 2, 3, 3);
  }

  @Test
  void crossesSegmentBoundaries() {
    VectorStore store = new VectorStore(2, 2);
    for (int i = 0; i < 5; i++) {
      store.add(new float[] {i, -i});
    }
    assertThat(store.segment(1)).isSameAs(store.segment(0));
    assertThat(store.segment(2)).isNotSameAs(store.segment(1));
    assertThat(store.offset(2)).isZero();
    assertThat(store.offset(3)).isEqualTo(2);
    assertThat(store.get(4)).containsExactly(4, -4);
    assertThat(store.memoryBytes()).isEqualTo(3L * 2 * 2 * Float.BYTES);
  }

  @Test
  void defaultSegmentsHoldAPowerOfTwoVectorsOfAbout4MiB() {
    VectorStore store = new VectorStore(128);
    store.add(new float[128]);
    assertThat(store.memoryBytes()).isEqualTo((long) VectorStore.SEGMENT_FLOATS * Float.BYTES);

    VectorStore odd = new VectorStore(784); // 2^20 / 784 = 1337 -> 1024 vectors per segment
    odd.add(new float[784]);
    assertThat(odd.memoryBytes()).isEqualTo(1024L * 784 * Float.BYTES);
  }

  @Test
  void hugeDimensionStillGetsOneVectorPerSegment() {
    int dim = VectorStore.SEGMENT_FLOATS + 1;
    VectorStore store = new VectorStore(dim);
    store.add(new float[dim]);
    assertThat(store.memoryBytes()).isEqualTo((long) dim * Float.BYTES);
  }

  @Test
  void addAndGetCopyInsteadOfSharing() {
    VectorStore store = new VectorStore(2);
    float[] input = {1, 2};
    store.add(input);
    input[0] = 99;
    assertThat(store.get(0)).containsExactly(1, 2);
    store.get(0)[1] = 99;
    assertThat(store.get(0)).containsExactly(1, 2);
  }

  @Test
  void distanceReadsStoredVectorsWithoutCopying() {
    VectorStore store = new VectorStore(2, 2);
    store.add(new float[] {0, 0});
    store.add(new float[] {3, 4});
    store.add(new float[] {6, 8});
    assertThat(store.distance(Metric.L2.distance(), new float[] {0, 0}, 2)).isEqualTo(100f);
    assertThat(store.distance(Metric.L2.distance(), 1, 2)).isEqualTo(25f);
  }

  @Test
  void rejectsBadInput() {
    VectorStore store = new VectorStore(2);
    assertThatThrownBy(() -> store.add(new float[3]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected 2, got 3");
    assertThatThrownBy(() -> store.add(new float[] {1, Float.NaN}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("component 1");
    assertThatThrownBy(() -> store.add(new float[] {Float.POSITIVE_INFINITY, 1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.distance(Metric.L2.distance(), new float[3], 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.size()).isZero();
  }

  @Test
  void rejectsOrdinalsOutsideTheStore() {
    VectorStore store = new VectorStore(2);
    store.add(new float[2]);
    assertThatThrownBy(() -> store.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> store.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> store.segment(5)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> store.offset(1)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void rejectsInvalidConstruction() {
    assertThatThrownBy(() -> new VectorStore(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VectorStore(-4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VectorStore(2, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("power of two");
  }

  @Provide
  Arbitrary<List<float[]>> vectorBatches() {
    return Arbitraries.floats()
        .between(-1e6f, 1e6f)
        .array(float[].class)
        .ofSize(5)
        .list()
        .ofMaxSize(200);
  }

  @Property(tries = 200)
  void everyAddedVectorRoundTrips(@ForAll("vectorBatches") List<float[]> vectors) {
    VectorStore store = new VectorStore(5, 8);
    List<Integer> ords = new ArrayList<>();
    for (float[] v : vectors) {
      ords.add(store.add(v));
    }
    assertThat(store.size()).isEqualTo(vectors.size());
    for (int i = 0; i < vectors.size(); i++) {
      assertThat(ords.get(i)).isEqualTo(i);
      assertThat(store.get(i)).containsExactly(vectors.get(i));
    }
  }
}
