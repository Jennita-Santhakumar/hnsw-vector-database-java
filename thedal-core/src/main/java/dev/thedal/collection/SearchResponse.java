package dev.thedal.collection;

import java.util.List;

/**
 * Search results, closest first.
 *
 * @param strategy how the query was answered: UNFILTERED, NO_MATCH, EXACT_SCAN or FILTERED_INDEX
 * @param selectivity share of live points matching the filter (1 when unfiltered)
 */
public record SearchResponse(List<SearchHit> hits, String strategy, double selectivity) {

  /** Takes an immutable copy of the hits. */
  public SearchResponse {
    hits = List.copyOf(hits);
  }
}
