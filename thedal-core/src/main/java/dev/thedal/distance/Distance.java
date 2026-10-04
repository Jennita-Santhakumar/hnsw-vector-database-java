package dev.thedal.distance;

/**
 * Distance between two vectors stored inside (possibly larger) float arrays.
 *
 * <p>Every implementation follows one convention: <b>smaller means closer</b>. That lets indexes
 * rank candidates the same way regardless of metric. The offset form exists because vectors live in
 * contiguous segment arrays; it avoids copying a vector out before comparing it.
 */
@FunctionalInterface
public interface Distance {

  /**
   * Returns the distance between {@code a[aOffset, aOffset + dim)} and {@code b[bOffset, bOffset +
   * dim)}.
   *
   * @throws IndexOutOfBoundsException if either range falls outside its array
   */
  float distance(float[] a, int aOffset, float[] b, int bOffset, int dim);

  /**
   * Returns the distance between two whole vectors.
   *
   * @throws IllegalArgumentException if the vectors have different dimensions
   */
  default float distance(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
    }
    return distance(a, 0, b, 0, a.length);
  }
}
