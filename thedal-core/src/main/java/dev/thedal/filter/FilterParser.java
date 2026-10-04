package dev.thedal.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the filter JSON from ARCHITECTURE.md, given as the generic tree a JSON library produces
 * ({@code Map}, {@code List}, scalars):
 *
 * <pre>{@code
 * {"and": [{"field": "lang", "eq": "ta"},
 *          {"field": "year", "range": {"gte": 2020}},
 *          {"field": "tag", "in": ["news", "blog"]}]}
 * }</pre>
 *
 * Every error is an {@link IllegalArgumentException} with a message fit to return to the client.
 */
public final class FilterParser {

  /** Deepest accepted and/or nesting, to bound recursion on hostile input. */
  public static final int MAX_DEPTH = 16;

  private static final Set<String> LEAF_OPS = Set.of("eq", "in", "range");
  private static final Set<String> RANGE_KEYS = Set.of("gt", "gte", "lt", "lte");

  private FilterParser() {}

  /** Parses a filter tree. */
  public static Filter parse(Object json) {
    return parse(json, 0);
  }

  private static Filter parse(Object json, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("filter nested deeper than " + MAX_DEPTH);
    }
    if (!(json instanceof Map<?, ?> node)) {
      throw new IllegalArgumentException("a filter must be a JSON object");
    }
    if (node.containsKey("and") || node.containsKey("or")) {
      if (node.size() != 1) {
        throw new IllegalArgumentException("'and'/'or' must be the only key in its object");
      }
      String op = node.containsKey("and") ? "and" : "or";
      if (!(node.get(op) instanceof List<?> children)) {
        throw new IllegalArgumentException("'" + op + "' must be a list of filters");
      }
      List<Filter> parsed = new ArrayList<>();
      for (Object child : children) {
        parsed.add(parse(child, depth + 1));
      }
      return op.equals("and") ? new Filter.And(parsed) : new Filter.Or(parsed);
    }
    return parseLeaf(node);
  }

  private static Filter parseLeaf(Map<?, ?> node) {
    if (!(node.get("field") instanceof String field)) {
      throw new IllegalArgumentException("a filter needs a string 'field' (or 'and'/'or')");
    }
    List<String> ops =
        node.keySet().stream().map(String::valueOf).filter(LEAF_OPS::contains).toList();
    if (ops.size() != 1 || node.size() != 2) {
      throw new IllegalArgumentException(
          "filter on '" + field + "' needs 'field' plus exactly one of eq, in, range");
    }
    Object arg = node.get(ops.get(0));
    return switch (ops.get(0)) {
      case "eq" -> new Filter.Eq(field, arg);
      case "in" -> {
        if (!(arg instanceof List<?> values)) {
          throw new IllegalArgumentException("'in' on field '" + field + "' must be a list");
        }
        yield new Filter.In(field, new ArrayList<>(values));
      }
      default -> parseRange(field, arg);
    };
  }

  private static Filter parseRange(String field, Object arg) {
    if (!(arg instanceof Map<?, ?> bounds) || bounds.isEmpty()) {
      throw new IllegalArgumentException(
          "'range' on field '" + field + "' must be an object of gt/gte/lt/lte");
    }
    for (Object key : bounds.keySet()) {
      if (!RANGE_KEYS.contains(String.valueOf(key))) {
        throw new IllegalArgumentException("unknown range bound '" + key + "'");
      }
    }
    return new Filter.Range(
        field,
        bound(field, bounds.get("gt")),
        bound(field, bounds.get("gte")),
        bound(field, bounds.get("lt")),
        bound(field, bounds.get("lte")));
  }

  private static Double bound(String field, Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number n)) {
      throw new IllegalArgumentException("range bounds on field '" + field + "' must be numbers");
    }
    return n.doubleValue();
  }
}
