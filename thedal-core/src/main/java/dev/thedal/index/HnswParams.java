package dev.thedal.index;

/**
 * HNSW build and search parameters.
 *
 * @param m links per node on upper layers; layer 0 allows {@link #m0()} = 2M
 * @param efConstruction candidate-list size while inserting (higher = better graph, slower build)
 * @param efSearch default candidate-list size while searching (higher = better recall, slower)
 * @param seed RNG seed for level assignment, so the same inserts always build the same graph
 */
public record HnswParams(int m, int efConstruction, int efSearch, long seed) {

  /** Default M from the paper's recommended range (5-48). */
  public static final int DEFAULT_M = 16;

  /** Default efConstruction. */
  public static final int DEFAULT_EF_CONSTRUCTION = 200;

  /** Default efSearch. */
  public static final int DEFAULT_EF_SEARCH = 64;

  /** Default level-generation seed. */
  public static final long DEFAULT_SEED = 42L;

  /** Validates the parameters. */
  public HnswParams {
    if (m < 2) {
      throw new IllegalArgumentException("m must be >= 2: " + m);
    }
    if (efConstruction < 1) {
      throw new IllegalArgumentException("efConstruction must be >= 1: " + efConstruction);
    }
    if (efSearch < 1) {
      throw new IllegalArgumentException("efSearch must be >= 1: " + efSearch);
    }
  }

  /** The paper's defaults (M=16, efConstruction=200, efSearch=64, seed=42). */
  public static HnswParams defaults() {
    return new HnswParams(DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH, DEFAULT_SEED);
  }

  /** Maximum links per node on layer 0. */
  public int m0() {
    return 2 * m;
  }

  /** Level multiplier mL = 1 / ln(M). */
  public double levelMultiplier() {
    return 1.0 / Math.log(m);
  }
}
