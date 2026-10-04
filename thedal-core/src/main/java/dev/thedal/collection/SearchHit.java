package dev.thedal.collection;

import java.util.Map;

/**
 * One search result.
 *
 * @param score the metric's distance, smaller = closer (squared L2; negative dot or cosine)
 */
public record SearchHit(String id, float score, Map<String, Object> metadata) {

  /** Takes an immutable copy of the metadata. */
  public SearchHit {
    metadata = Map.copyOf(metadata);
  }
}
