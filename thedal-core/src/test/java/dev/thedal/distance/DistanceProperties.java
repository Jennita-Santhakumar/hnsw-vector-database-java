package dev.thedal.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.FloatRange;

/** Property tests: float kernels agree with a double-precision reference and obey metric laws. */
class DistanceProperties {

  private static final int DIM = 32;

  @Provide
  Arbitrary<float[]> vectors() {
    return Arbitraries.floats().between(-100f, 100f).array(float[].class).ofSize(DIM);
  }

  @Property
  void l2MatchesDoubleReference(@ForAll("vectors") float[] a, @ForAll("vectors") float[] b) {
    double expected = 0;
    double scale = 1;
    for (int i = 0; i < DIM; i++) {
      double d = (double) a[i] - b[i];
      expected += d * d;
      scale += d * d;
    }
    assertThat((double) Metric.L2.distance().distance(a, b))
        .isCloseTo(expected, within(1e-5 * scale));
  }

  @Property
  void dotMatchesDoubleReference(@ForAll("vectors") float[] a, @ForAll("vectors") float[] b) {
    double expected = 0;
    double scale = 1;
    for (int i = 0; i < DIM; i++) {
      double p = (double) a[i] * b[i];
      expected += p;
      scale += Math.abs(p);
    }
    assertThat((double) Metric.DOT.distance().distance(a, b))
        .isCloseTo(-expected, within(1e-5 * scale));
  }

  @Property
  void l2IsSymmetricNonNegativeAndZeroOnSelf(
      @ForAll("vectors") float[] a, @ForAll("vectors") float[] b) {
    Distance l2 = Metric.L2.distance();
    assertThat(l2.distance(a, b)).isEqualTo(l2.distance(b, a)).isGreaterThanOrEqualTo(0f);
    assertThat(l2.distance(a, a)).isEqualTo(0f);
  }

  @Property
  void cosinePrepareYieldsUnitVectors(@ForAll("vectors") float[] v) {
    Assume.that(norm(v) > 1e-3);
    assertThat(norm(Metric.COSINE.prepare(v))).isCloseTo(1d, within(1e-5));
  }

  @Property
  void cosineDistanceIsBoundedAndScaleInvariant(
      @ForAll("vectors") float[] a,
      @ForAll("vectors") float[] b,
      @ForAll @FloatRange(min = 0.01f, max = 1000f) float scale) {
    Assume.that(norm(a) > 1e-3 && norm(b) > 1e-3);
    Metric m = Metric.COSINE;
    float d = m.distance().distance(m.prepare(a), m.prepare(b));
    assertThat(d).isBetween(-1.0001f, 1.0001f);

    float[] scaled = new float[DIM];
    for (int i = 0; i < DIM; i++) {
      scaled[i] = a[i] * scale;
    }
    assertThat(m.distance().distance(m.prepare(scaled), m.prepare(b))).isCloseTo(d, within(1e-4f));
  }

  private static double norm(float[] v) {
    double s = 0;
    for (float x : v) {
      s += (double) x * x;
    }
    return Math.sqrt(s);
  }
}
