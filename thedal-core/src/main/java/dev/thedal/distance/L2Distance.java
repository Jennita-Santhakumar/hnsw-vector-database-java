package dev.thedal.distance;

/**
 * Squared Euclidean distance. The square root is skipped: it is monotonic, so ranking is the same
 * and every comparison saves a sqrt.
 */
final class L2Distance implements Distance {

  static final L2Distance INSTANCE = new L2Distance();

  private L2Distance() {}

  @Override
  public float distance(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    return VectorMath.squaredL2(a, aOffset, b, bOffset, dim);
  }
}
