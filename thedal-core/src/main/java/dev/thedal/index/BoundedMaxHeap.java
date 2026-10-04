package dev.thedal.index;

/**
 * Keeps the {@code capacity} best (smallest-distance) entries seen so far. The worst kept entry
 * sits at the root, so deciding whether a new candidate gets in is O(1) and inserting is O(log k).
 *
 * <p>Ordering is total: by distance, then by ordinal. That makes top-k results deterministic when
 * distances tie. Primitive arrays and {@link #reset(int)} let one heap serve many queries without
 * allocation.
 */
final class BoundedMaxHeap {

  private int[] ords;
  private float[] dists;
  private int capacity;
  private int size;

  BoundedMaxHeap(int capacity) {
    ords = new int[0];
    dists = new float[0];
    reset(capacity);
  }

  /** Empties the heap and sets a new capacity, growing the arrays only when needed. */
  void reset(int newCapacity) {
    if (newCapacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1: " + newCapacity);
    }
    if (newCapacity > ords.length) {
      ords = new int[newCapacity];
      dists = new float[newCapacity];
    }
    capacity = newCapacity;
    size = 0;
  }

  /** Adds the entry if it is among the best seen so far; returns whether it was kept. */
  boolean offer(int ord, float dist) {
    if (size < capacity) {
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
    return size == capacity;
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
    if (n == 0) {
      return SearchResult.empty();
    }
    int[] outOrds = new int[n];
    float[] outDists = new float[n];
    drainSortedInto(outOrds, outDists);
    return new SearchResult(outOrds, outDists);
  }

  /**
   * Empties the heap into the first {@link #size()} slots of the given arrays, closest first, and
   * returns how many entries were written. Lets callers reuse output buffers.
   */
  int drainSortedInto(int[] outOrds, float[] outDists) {
    int n = size;
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
    return n;
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
