package dev.thedal.index;

import dev.thedal.distance.Distance;
import dev.thedal.internal.store.VectorStore;

/** Exact top-k over an explicit list of ordinals; used for highly selective filters. */
public final class ExactSearch {

  private ExactSearch() {}

  /** Scores every ordinal in {@code ords} and returns the k closest, closest first. */
  public static SearchResult topK(
      VectorStore store, Distance distance, float[] query, int k, int[] ords) {
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1: " + k);
    }
    if (ords.length == 0) {
      return SearchResult.empty();
    }
    BoundedMaxHeap heap = new BoundedMaxHeap(Math.min(k, ords.length));
    for (int ord : ords) {
      heap.offer(ord, store.distance(distance, query, ord));
    }
    return heap.drainSorted();
  }
}
