package dev.thedal.distance;

import java.util.Objects;

/** Scalar vector kernels shared by the distance implementations. */
public final class VectorMath {

  private VectorMath() {}

  /** Dot product of two ranges. Bounds are checked once up front, not per element. */
  public static float dot(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    checkRanges(a, aOffset, b, bOffset, dim);
    float sum = 0f;
    for (int i = 0; i < dim; i++) {
      sum += a[aOffset + i] * b[bOffset + i];
    }
    return sum;
  }

  /** Squared Euclidean distance of two ranges. */
  public static float squaredL2(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    checkRanges(a, aOffset, b, bOffset, dim);
    float sum = 0f;
    for (int i = 0; i < dim; i++) {
      float d = a[aOffset + i] - b[bOffset + i];
      sum += d * d;
    }
    return sum;
  }

  /**
   * Scales {@code vector} to unit Euclidean length.
   *
   * @throws IllegalArgumentException if the vector is all zeros (or its norm is not finite)
   */
  public static void normalizeInPlace(float[] vector) {
    // Accumulate in double: a float sum of squares overflows for large components.
    double sumSquares = 0d;
    for (float v : vector) {
      sumSquares += (double) v * v;
    }
    double norm = Math.sqrt(sumSquares);
    if (norm == 0d || !Double.isFinite(norm)) {
      throw new IllegalArgumentException(
          "cannot normalize a vector with norm " + norm + " (cosine needs a non-zero vector)");
    }
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
  }

  private static void checkRanges(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    Objects.checkFromIndexSize(aOffset, dim, a.length);
    Objects.checkFromIndexSize(bOffset, dim, b.length);
  }
}
