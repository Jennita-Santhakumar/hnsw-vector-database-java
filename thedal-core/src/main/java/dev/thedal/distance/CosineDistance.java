package dev.thedal.distance;

/**
 * Cosine distance over unit-length vectors: negative dot product, in [-1, 1]. Inputs must already
 * be normalized by {@link Metric#prepare}; normalizing per comparison would waste the hot path.
 */
final class CosineDistance implements Distance {

  static final CosineDistance INSTANCE = new CosineDistance();

  private CosineDistance() {}

  @Override
  public float distance(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    return 0f - VectorMath.dot(a, aOffset, b, bOffset, dim);
  }
}
