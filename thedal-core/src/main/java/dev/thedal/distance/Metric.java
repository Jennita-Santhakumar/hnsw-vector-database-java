package dev.thedal.distance;

import java.util.Arrays;

/** Similarity metric of a collection. Fixed when the collection is created. */
public enum Metric {
  /** Squared Euclidean distance. */
  L2(L2Distance.INSTANCE),
  /** Negative inner product, so larger dot products rank closer. */
  DOT(DotProductDistance.INSTANCE),
  /**
   * Cosine similarity, implemented as negative dot product over vectors that {@link #prepare}
   * normalized to unit length on insert and on query.
   */
  COSINE(CosineDistance.INSTANCE);

  private final Distance distance;

  Metric(Distance distance) {
    this.distance = distance;
  }

  /** Returns the distance function for this metric (smaller = closer). */
  public Distance distance() {
    return distance;
  }

  /**
   * Returns the form of {@code vector} that this metric stores and searches with: a unit-length
   * copy for {@link #COSINE}, an unchanged copy otherwise. The input is never modified.
   *
   * @throws IllegalArgumentException for a zero vector under {@link #COSINE}, whose direction is
   *     undefined
   */
  public float[] prepare(float[] vector) {
    float[] copy = Arrays.copyOf(vector, vector.length);
    if (this == COSINE) {
      VectorMath.normalizeInPlace(copy);
    }
    return copy;
  }
}
