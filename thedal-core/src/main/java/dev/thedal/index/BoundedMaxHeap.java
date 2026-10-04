package dev.thedal.index;

/**
 * Keeps the {@code capacity} best (smallest-distance) entries seen so far. The worst kept entry
 * sits at the root, so deciding whether a new candidate gets in is O(1) and inserting is O(log k).
 *
 * <p>Ordering is total: by distance, then by ordinal. That makes top-k results deterministic when
 * distances tie. Primitive arrays and {@link #clear()} let one heap serve many queries without
 * allocation.
 */
final class BoundedMaxHeap {

  private final int[] ords;
  private final float[] dists;
  private int size;

  BoundedMaxHeap(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
    }
    ords = new int[capacity];
    dists = new float[capacity];
  }

  /** Adds the entry if it is among the best seen so far; returns whether it was kept. */
  boolean offer(int ord, float dist) {
    if (size < ords.length) {
      ords[size] = ord;
      dists[size] = dist;
      siftUp(size++);
      return true;
    }
    if (!worse(dists[0], ords[0], dist, ord)) {
      return false;
    }
    ords[0] = ord;
    dists[0] = dist;
    siftDown(0);
    return true;
  }

  int size() {
    return size;
  }

  boolean isFull() {
    return size == ords.length;
  }

  /** Distance of the worst kept entry. Only valid when non-empty. */
  float worstDistance() {
    return dists[0];
  }

  void clear() {
    size = 0;
  }

  /** Empties the heap into a result ordered closest first. */
  SearchResult drainSorted() {
    int n = size;
    int[] outOrds = new int[n];
    float[] outDists = new float[n];
    for (int i = n - 1; i >= 0; i--) {
      outOrds[i] = ords[0];
      outDists[i] = dists[0];
      size--;
      if (size > 0) {
        ords[0] = ords[size];
        dists[0] = dists[size];
        siftDown(0);
      }
    }
    return n == 0 ? SearchResult.empty() : new SearchResult(outOrds, outDists);
  }

  /** True if entry a ranks after entry b. */
  private static boolean worse(float distA, int ordA, float distB, int ordB) {
    return distA > distB || (distA == distB && ordA > ordB);
  }

  private void siftUp(int i) {
    int ord = ords[i];
    float dist = dists[i];
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (!worse(dist, ord, dists[parent], ords[parent])) {
        break;
      }
      ords[i] = ords[parent];
      dists[i] = dists[parent];
      i = parent;
    }
    ords[i] = ord;
    dists[i] = dist;
  }

  private void siftDown(int i) {
    int ord = ords[i];
    float dist = dists[i];
    while (true) {
      int child = 2 * i + 1;
      if (child >= size) {
        break;
      }
      int right = child + 1;
      if (right < size && worse(dists[right], ords[right], dists[child], ords[child])) {
        child = right;
      }
      if (!worse(dists[child], ords[child], dist, ord)) {
        break;
      }
      ords[i] = ords[child];
      dists[i] = dists[child];
      i = child;
    }
    ords[i] = ord;
    dists[i] = dist;
  }
}
