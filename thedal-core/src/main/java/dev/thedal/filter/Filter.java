package dev.thedal.filter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata filter: {@code eq}, {@code in}, {@code range}, {@code and}, {@code or}. Values are
 * normalized metadata values (String, Double or Boolean; see {@link Metadata}). Matching is
 * type-strict: the string "2020" never equals the number 2020.
 */
public sealed interface Filter {

  /**
   * Reference evaluation against one point's normalized fields. Indexed evaluation must always
   * agree with this.
   */
  boolean matches(Map<String, Object> fields);

  /** {@code field == value}. */
  record Eq(String field, Object value) implements Filter {
    /** Validates and normalizes the value. */
    public Eq {
      Metadata.requireFieldName(field);
      value = Metadata.normalizeValue(field, value);
    }

    @Override
    public boolean matches(Map<String, Object> fields) {
      return value.equals(fields.get(field));
    }
  }

  /** {@code field} equals any of {@code values}. */
  record In(String field, List<Object> values) implements Filter {
    /** Validates and normalizes the values. */
    public In {
      Metadata.requireFieldName(field);
      if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException("'in' on field '" + field + "' needs a non-empty list");
      }
      values = List.copyOf(values.stream().map(v -> Metadata.normalizeValue(field, v)).toList());
    }

    @Override
    public boolean matches(Map<String, Object> fields) {
      Object actual = fields.get(field);
      return actual != null && values.contains(actual);
    }
  }

  /**
   * Numeric range; {@code null} bounds are open. Only numeric field values can match.
   *
   * @param gt exclusive lower bound
   * @param gte inclusive lower bound
   * @param lt exclusive upper bound
   * @param lte inclusive upper bound
   */
  record Range(String field, Double gt, Double gte, Double lt, Double lte) implements Filter {
    /** Validates the bounds. */
    public Range {
      Metadata.requireFieldName(field);
      if (gt == null && gte == null && lt == null && lte == null) {
        throw new IllegalArgumentException("'range' on field '" + field + "' needs a bound");
      }
      if ((gt != null && gte != null) || (lt != null && lte != null)) {
        throw new IllegalArgumentException(
            "'range' on field '" + field + "' has two bounds on the same side");
      }
      for (Double bound : new Double[] {gt, gte, lt, lte}) {
        if (bound != null && !Double.isFinite(bound)) {
          throw new IllegalArgumentException("'range' bounds must be finite numbers");
        }
      }
    }

    @Override
    public boolean matches(Map<String, Object> fields) {
      if (!(fields.get(field) instanceof Double x)) {
        return false;
      }
      return (gt == null || x > gt)
          && (gte == null || x >= gte)
          && (lt == null || x < lt)
          && (lte == null || x <= lte);
    }
  }

  /** All children match. */
  record And(List<Filter> filters) implements Filter {
    /** Validates the children. */
    public And {
      requireChildren("and", filters);
      filters = List.copyOf(filters);
    }

    @Override
    public boolean matches(Map<String, Object> fields) {
      return filters.stream().allMatch(f -> f.matches(fields));
    }
  }

  /** At least one child matches. */
  record Or(List<Filter> filters) implements Filter {
    /** Validates the children. */
    public Or {
      requireChildren("or", filters);
      filters = List.copyOf(filters);
    }

    @Override
    public boolean matches(Map<String, Object> fields) {
      return filters.stream().anyMatch(f -> f.matches(fields));
    }
  }

  private static void requireChildren(String op, List<Filter> filters) {
    if (filters == null || filters.isEmpty()) {
      throw new IllegalArgumentException("'" + op + "' needs a non-empty list of filters");
    }
    filters.forEach(Objects::requireNonNull);
  }
}
