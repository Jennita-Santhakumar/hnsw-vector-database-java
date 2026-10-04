package dev.thedal.collection;

import dev.thedal.distance.Metric;
import dev.thedal.index.HnswParams;
import java.util.regex.Pattern;

/**
 * Immutable settings of a collection, fixed at creation.
 *
 * @param hnsw graph parameters; used only when {@code indexType} is {@link IndexType#HNSW}
 */
public record CollectionConfig(
    String name, int dim, Metric metric, IndexType indexType, HnswParams hnsw) {

  /** Largest supported vector dimension. */
  public static final int MAX_DIM = 4096;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** Validates the settings. */
  public CollectionConfig {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "collection name must be 1-64 chars of letters, digits, '_' or '-': " + name);
    }
    if (dim < 1 || dim > MAX_DIM) {
      throw new IllegalArgumentException("dim must be 1-" + MAX_DIM + ": " + dim);
    }
    if (metric == null || indexType == null) {
      throw new IllegalArgumentException("metric and index type are required");
    }
    if (hnsw == null) {
      hnsw = HnswParams.defaults();
    }
  }

  /** HNSW collection with default graph parameters. */
  public static CollectionConfig hnsw(String name, int dim, Metric metric) {
    return new CollectionConfig(name, dim, metric, IndexType.HNSW, HnswParams.defaults());
  }

  /** Exact (flat) collection. */
  public static CollectionConfig flat(String name, int dim, Metric metric) {
    return new CollectionConfig(name, dim, metric, IndexType.FLAT, null);
  }
}
