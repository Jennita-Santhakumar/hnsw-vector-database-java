package dev.thedal.internal.store;

import dev.thedal.distance.Distance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Append-only storage of fixed-dimension vectors, addressed by dense internal ordinals (0, 1, 2,
 * ...).
 *
 * <p>Vectors are packed back to back in large {@code float[]} segments rather than one array per
 * vector: that removes per-object headers and keeps neighbours in cache during scans. Each segment
 * holds a power-of-two number of vectors so locating an ordinal is a shift and a mask.
 *
 * <p>Not thread-safe for writes. The owning collection serializes writers and publishes reads
 * through its lock.
 */
public final class VectorStore {

  /** Target segment size: 2^20 floats = 4 MiB. */
  static final int SEGMENT_FLOATS = 1 << 20;

  private final int dim;
  private final int shift;
  private final int mask;
  private final List<float[]> segments = new ArrayList<>();
  private int size;

  /** Creates an empty store for vectors of dimension {@code dim}. */
  public VectorStore(int dim) {
    this(dim, Integer.highestOneBit(Math.max(1, SEGMENT_FLOATS / requirePositive(dim))));
  }

  /** Test hook: forces small segments so boundary handling is exercised with few vectors. */
  VectorStore(int dim, int vectorsPerSegment) {
    this.dim = requirePositive(dim);
    if (Integer.bitCount(vectorsPerSegment) != 1) {
      throw new IllegalArgumentException(
          "vectorsPerSegment must be a power of two: " + vectorsPerSegment);
    }
    this.shift = Integer.numberOfTrailingZeros(vectorsPerSegment);
    this.mask = vectorsPerSegment - 1;
  }

  /**
   * Copies {@code vector} into the store and returns its ordinal.
   *
   * @throws IllegalArgumentException if the dimension is wrong or a component is NaN or infinite
   */
  public int add(float[] vector) {
    if (vector.length != dim) {
      throw new IllegalArgumentException(
          "dimension mismatch: expected " + dim + ", got " + vector.length);
    }
    for (int i = 0; i < vector.length; i++) {
      if (!Float.isFinite(vector[i])) {
        throw new IllegalArgumentException("component " + i + " is not finite: " + vector[i]);
      }
    }
    if (size == Integer.MAX_VALUE) {
      throw new IllegalStateException("vector store is full");
    }
    int ord = size;
    if ((ord >>> shift) == segments.size()) {
      segments.add(new float[(mask + 1) * dim]);
    }
    // Raw addressing: segment()/offset() would reject ord until size is incremented.
    System.arraycopy(vector, 0, segments.get(ord >>> shift), (ord & mask) * dim, dim);
    size++;
    return ord;
  }

  /** Returns a copy of the vector at {@code ord}. */
  public float[] get(int ord) {
    checkOrd(ord);
    int offset = offset(ord);
    return Arrays.copyOfRange(segment(ord), offset, offset + dim);
  }

  /**
   * Returns the segment array holding {@code ord}, for zero-copy reads with {@link #offset(int)}.
   * Callers must not modify it.
   */
  public float[] segment(int ord) {
    checkOrd(ord);
    return segments.get(ord >>> shift);
  }

  /** Returns where vector {@code ord} starts inside {@link #segment(int)}. */
  public int offset(int ord) {
    checkOrd(ord);
    return (ord & mask) * dim;
  }

  /** Distance from {@code query} to the stored vector {@code ord}, without copying it. */
  public float distance(Distance distance, float[] query, int ord) {
    if (query.length != dim) {
      throw new IllegalArgumentException(
          "dimension mismatch: expected " + dim + ", got " + query.length);
    }
    return distance.distance(query, 0, segment(ord), offset(ord), dim);
  }

  /** Distance between two stored vectors. */
  public float distance(Distance distance, int ordA, int ordB) {
    return distance.distance(segment(ordA), offset(ordA), segment(ordB), offset(ordB), dim);
  }

  /** Number of vectors stored. */
  public int size() {
    return size;
  }

  /** Vector dimension. */
  public int dim() {
    return dim;
  }

  /** Bytes allocated for vector data (whole segments, including unused tail capacity). */
  public long memoryBytes() {
    return (long) segments.size() * (mask + 1) * dim * Float.BYTES;
  }

  private void checkOrd(int ord) {
    Objects.checkIndex(ord, size);
  }

  private static int requirePositive(int dim) {
    if (dim <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dim);
    }
    return dim;
  }
}
