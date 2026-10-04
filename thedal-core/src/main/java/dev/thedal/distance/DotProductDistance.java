package dev.thedal.distance;

/** Negative inner product: larger dot products rank closer. */
final class DotProductDistance implements Distance {

  static final DotProductDistance INSTANCE = new DotProductDistance();

  private DotProductDistance() {}

  @Override
  public float distance(float[] a, int aOffset, float[] b, int bOffset, int dim) {
    // 0f - x rather than -x so orthogonal vectors give 0.0f, not -0.0f.
    return 0f - VectorMath.dot(a, aOffset, b, bOffset, dim);
  }
}
