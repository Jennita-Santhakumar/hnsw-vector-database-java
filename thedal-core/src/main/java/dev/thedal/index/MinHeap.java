package dev.thedal.index;

import java.util.Arrays;

/**
 * Growable min-heap of (ordinal, distance) on primitive arrays: the closest entry is at the root.
 * Holds the HNSW candidate queue. Same total order as {@link BoundedMaxHeap}: distance, then
 * ordinal.
 */
final class MinHeap {

  private int[] ords;
  private float[] dists;
  private int size;

  MinHeap(int initialCapacity) {
    int capacity = Math.max(1, initialCapacity);
    ords = new int[capacity];
    dists = new float[capacity];
  }

  void push(int ord, float dist) {
    if (size == ords.length) {
      ords = Arrays.copyOf(ords, size * 2);
      dists = Arrays.copyOf(dists, size * 2);
    }
    int i = size++;
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (!before(dist, ord, dists[parent], ords[parent])) {
        break;
      }
      ords[i] = ords[parent];
      dists[i] = dists[parent];
      i = parent;
    }
    ords[i] = ord;
    dists[i] = dist;
  }

  /** Ordinal of the closest entry. Only valid when non-empty. */
  int peekOrd() {
    return ords[0];
  }

  /** Distance of the closest entry. Only valid when non-empty. */
  float peekDist() {
    return dists[0];
  }

  /** Removes the closest entry. */
  void pop() {
    size--;
    if (size == 0) {
      return;
    }
    int ord = ords[size];
    float dist = dists[size];
    int i = 0;
    while (true) {
      int child = 2 * i + 1;
      if (child >= size) {
        break;
      }
      int right = child + 1;
      if (right < size && before(dists[right], ords[right], dists[child], ords[child])) {
        child = right;
      }
      if (!before(dists[child], ords[child], dist, ord)) {
        break;
      }
      ords[i] = ords[child];
      dists[i] = dists[child];
      i = child;
    }
    ords[i] = ord;
    dists[i] = dist;
  }

  boolean isEmpty() {
    return size == 0;
  }

  int size() {
    return size;
  }

  void clear() {
    size = 0;
  }

  /** True if entry a ranks before (closer than) entry b. */
  private static boolean before(float distA, int ordA, float distB, int ordB) {
    return distA < distB || (distA == distB && ordA < ordB);
  }
}
