package dev.thedal.index;

/**
 * Search hits ordered closest first (ascending distance, ties by ascending ordinal). Immutable;
 * accessors avoid exposing the backing arrays.
 */
public final class SearchResult {

  private final int[] ords;
  private final float[] distances;

  /** Takes ownership of the arrays; callers must not modify them afterwards. */
  SearchResult(int[] ords, float[] distances) {
    if (ords.length != distances.length) {
      throw new IllegalArgumentException("ords and distances differ in length");
    }
    this.ords = ords;
    this.distances = distances;
  }

  /** The result with no hits. */
  public static SearchResult empty() {
    return new SearchResult(new int[0], new float[0]);
  }

  /** Number of hits. */
  public int size() {
    return ords.length;
  }

  /** Ordinal of hit {@code i} (0 = closest). */
  public int ord(int i) {
    return ords[i];
  }

  /** Distance of hit {@code i} (smaller = closer). */
  public float distance(int i) {
    return distances[i];
  }

  /** Copy of all ordinals, closest first. */
  public int[] ords() {
    return ords.clone();
  }
}
