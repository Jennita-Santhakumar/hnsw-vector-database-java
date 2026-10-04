package dev.thedal.index;

/**
 * Per-query search options.
 *
 * @param ef candidate-list size override for graph indexes; {@code 0} uses the index default. Exact
 *     indexes ignore it.
 */
public record SearchParams(int ef) {

  /** Index defaults for everything. */
  public static final SearchParams DEFAULT = new SearchParams(0);

  /** Validates the parameters. */
  public SearchParams {
    if (ef < 0) {
      throw new IllegalArgumentException("ef must be >= 0: " + ef);
    }
  }
}
