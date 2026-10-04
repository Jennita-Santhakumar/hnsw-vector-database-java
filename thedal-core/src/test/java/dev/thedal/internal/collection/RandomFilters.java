package dev.thedal.internal.collection;

import dev.thedal.filter.Filter;
import dev.thedal.filter.Metadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Seeded generators of metadata and nested filters over a small shared vocabulary. */
final class RandomFilters {

  private static final String[] LANGS = {"ta", "en", "hi"};
  private static final String[] TAGS = {"news", "blog", "paper", "forum"};

  private RandomFilters() {}

  /** Random metadata: each field present with probability 0.8. */
  static Map<String, Object> metadata(Random rnd) {
    Map<String, Object> raw = new HashMap<>();
    if (rnd.nextDouble() < 0.8) {
      raw.put("lang", LANGS[rnd.nextInt(LANGS.length)]);
    }
    if (rnd.nextDouble() < 0.8) {
      raw.put("year", 2015 + rnd.nextInt(10));
    }
    if (rnd.nextDouble() < 0.8) {
      raw.put("tag", TAGS[rnd.nextInt(TAGS.length)]);
    }
    if (rnd.nextDouble() < 0.8) {
      raw.put("draft", rnd.nextBoolean());
    }
    return Metadata.normalize(raw);
  }

  /** Random filter tree of at most {@code depth} and/or levels. */
  static Filter filter(Random rnd, int depth) {
    int pick = rnd.nextInt(depth > 0 ? 6 : 4);
    return switch (pick) {
      case 0 -> new Filter.Eq("lang", LANGS[rnd.nextInt(LANGS.length)]);
      case 1 -> {
        List<Object> tags = new ArrayList<>();
        int n = 1 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
          tags.add(TAGS[rnd.nextInt(TAGS.length)]);
        }
        yield new Filter.In("tag", tags);
      }
      case 2 -> {
        double lo = 2014 + rnd.nextInt(12);
        double hi = lo + rnd.nextInt(6);
        yield rnd.nextBoolean()
            ? new Filter.Range("year", null, lo, null, hi)
            : new Filter.Range("year", lo, null, hi, null);
      }
      case 3 -> new Filter.Eq("draft", rnd.nextBoolean());
      default -> {
        List<Filter> children = new ArrayList<>();
        int n = 1 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
          children.add(filter(rnd, depth - 1));
        }
        yield pick == 4 ? new Filter.And(children) : new Filter.Or(children);
      }
    };
  }
}
