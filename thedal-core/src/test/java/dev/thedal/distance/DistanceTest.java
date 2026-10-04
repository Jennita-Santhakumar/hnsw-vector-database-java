package dev.thedal.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Example-based tests: known values, offsets and edge cases for every metric. */
class DistanceTest {

  private static final float EPS = 1e-6f;

  @Test
  void l2IsSquaredEuclidean() {
    assertThat(Metric.L2.distance().distance(new float[] {0, 0}, new float[] {3, 4}))
        .isEqualTo(25f);
  }

  @Test
  void dotIsNegativeInnerProduct() {
    float d = Metric.DOT.distance().distance(new float[] {1, 2, 3}, new float[] {4, -5, 6});
    assertThat(d).isEqualTo(-12f); // 4 - 10 + 18 = 12
  }

  @Test
  void dotRanksLargerInnerProductCloser() {
    float[] q = {1, 0};
    Distance dot = Metric.DOT.distance();
    assertThat(dot.distance(q, new float[] {5, 0})).isLessThan(dot.distance(q, new float[] {1, 0}));
  }

  @Test
  void cosineOfParallelOppositeAndOrthogonalVectors() {
    Metric m = Metric.COSINE;
    float[] x = m.prepare(new float[] {2, 0});
    assertThat(m.distance().distance(x, m.prepare(new float[] {7, 0}))).isCloseTo(-1f, within(EPS));
    assertThat(m.distance().distance(x, m.prepare(new float[] {-3, 0}))).isCloseTo(1f, within(EPS));
    assertThat(m.distance().distance(x, m.prepare(new float[] {0, 5}))).isEqualTo(0f);
  }

  @Test
  void orthogonalDotIsPositiveZero() {
    float d = Metric.DOT.distance().distance(new float[] {1, 0}, new float[] {0, 1});
    assertThat(Float.floatToRawIntBits(d)).isEqualTo(Float.floatToRawIntBits(0f));
  }

  @Test
  void offsetsReadVectorsInsideAPackedSegment() {
    // Three 2-d vectors packed back to back: [0,0] [3,4] [6,8].
    float[] segment = {0, 0, 3, 4, 6, 8};
    Distance l2 = Metric.L2.distance();
    assertThat(l2.distance(segment, 0, segment, 2, 2)).isEqualTo(25f);
    assertThat(l2.distance(segment, 2, segment, 4, 2)).isEqualTo(25f);
    assertThat(l2.distance(segment, 0, segment, 4, 2)).isEqualTo(100f);
  }

  @ParameterizedTest
  @EnumSource(
      value = Metric.class,
      names = {"L2", "DOT"})
  void zeroVectorIsAllowedForL2AndDot(Metric metric) {
    float[] zero = metric.prepare(new float[3]);
    assertThat(metric.distance().distance(zero, zero)).isEqualTo(0f);
    assertThat(metric.distance().distance(zero, new float[] {1, 2, 3})).isFinite();
  }

  @Test
  void zeroVectorIsRejectedForCosine() {
    assertThatThrownBy(() -> Metric.COSINE.prepare(new float[3]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-zero");
  }

  @Test
  void nonFiniteNormIsRejectedForCosine() {
    assertThatThrownBy(() -> Metric.COSINE.prepare(new float[] {Float.NaN, 1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(Metric.class)
  void dimensionMismatchIsRejected(Metric metric) {
    assertThatThrownBy(() -> metric.distance().distance(new float[] {1, 2}, new float[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimension mismatch: 2 vs 3");
  }

  @ParameterizedTest
  @EnumSource(Metric.class)
  void rangeOutsideArrayIsRejected(Metric metric) {
    float[] segment = new float[4];
    assertThatThrownBy(() -> metric.distance().distance(segment, 3, segment, 0, 2))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> metric.distance().distance(segment, 0, segment, -1, 2))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void prepareNeverMutatesItsInput() {
    float[] v = {3, 4};
    float[] prepared = Metric.COSINE.prepare(v);
    assertThat(v).containsExactly(3f, 4f);
    assertThat(prepared).containsExactly(new float[] {0.6f, 0.8f}, within(EPS));
    assertThat(Metric.L2.prepare(v)).isNotSameAs(v).containsExactly(3f, 4f);
  }

  @Test
  void normalizeHandlesComponentsWhoseSquaresOverflowFloat() {
    float[] v = {3e30f, 4e30f};
    VectorMath.normalizeInPlace(v);
    assertThat(v).containsExactly(new float[] {0.6f, 0.8f}, within(EPS));
  }
}
