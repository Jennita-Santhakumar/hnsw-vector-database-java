package dev.thedal.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class BoundedMaxHeapTest {

  @Test
  void keepsTheSmallestEntriesSortedAscending() {
    BoundedMaxHeap heap = new BoundedMaxHeap(3);
    float[] dists = {5, 1, 4, 2, 3};
    for (int i = 0; i < dists.length; i++) {
      heap.offer(i, dists[i]);
    }
    assertThat(heap.isFull()).isTrue();
    assertThat(heap.worstDistance()).isEqualTo(3f);
    SearchResult r = heap.drainSorted();
    assertThat(r.ords()).containsExactly(1, 3, 4);
    assertThat(r.distance(0)).isEqualTo(1f);
    assertThat(r.distance(2)).isEqualTo(3f);
    assertThat(heap.size()).isZero();
  }

  @Test
  void offerReportsWhetherTheEntryWasKept() {
    BoundedMaxHeap heap = new BoundedMaxHeap(1);
    assertThat(heap.offer(0, 2f)).isTrue();
    assertThat(heap.offer(1, 3f)).isFalse();
    assertThat(heap.offer(2, 1f)).isTrue();
    assertThat(heap.drainSorted().ords()).containsExactly(2);
  }

  @Test
  void breaksDistanceTiesByOrdinal() {
    BoundedMaxHeap heap = new BoundedMaxHeap(2);
    heap.offer(9, 1f);
    heap.offer(3, 1f);
    heap.offer(5, 1f);
    assertThat(heap.drainSorted().ords()).containsExactly(3, 5);
  }

  @Test
  void clearAllowsReuseAndEmptyDrainIsEmpty() {
    BoundedMaxHeap heap = new BoundedMaxHeap(2);
    heap.offer(1, 1f);
    heap.clear();
    assertThat(heap.size()).isZero();
    assertThat(heap.drainSorted().size()).isZero();
  }

  @Test
  void rejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new BoundedMaxHeap(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void matchesSortingEverything(
      @ForAll @Size(max = 300) List<@FloatRange(min = -50, max = 50) Float> dists,
      @ForAll @IntRange(min = 1, max = 40) int k) {
    BoundedMaxHeap heap = new BoundedMaxHeap(k);
    List<int[]> order = new ArrayList<>();
    for (int i = 0; i < dists.size(); i++) {
      heap.offer(i, dists.get(i));
      order.add(new int[] {i});
    }
    order.sort(Comparator.<int[]>comparingDouble(o -> dists.get(o[0])).thenComparingInt(o -> o[0]));
    int[] expected = order.stream().limit(k).mapToInt(o -> o[0]).toArray();
    assertThat(heap.drainSorted().ords()).containsExactly(expected);
  }
}
