package dev.thedal.filter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and normalization of point metadata. Values are scalars only: strings, numbers (stored
 * as double; integers beyond 2^53 lose precision) and booleans.
 */
public final class Metadata {

  /** Longest accepted field name. */
  public static final int MAX_FIELD_NAME_LENGTH = 128;

  private Metadata() {}

  /**
   * Returns a normalized, unmodifiable copy of {@code raw}: numbers become {@code Double}, -0.0
   * becomes 0.0.
   *
   * @throws IllegalArgumentException for bad field names, nulls, nested values or non-finite
   *     numbers
   */
  public static Map<String, Object> normalize(Map<String, ?> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach(
        (field, value) -> {
          requireFieldName(field);
          out.put(field, normalizeValue(field, value));
        });
    return java.util.Collections.unmodifiableMap(out);
  }

  /** Normalizes one scalar value (see {@link #normalize}). */
  public static Object normalizeValue(String field, Object value) {
    if (value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number n) {
      double d = n.doubleValue();
      if (!Double.isFinite(d)) {
        throw new IllegalArgumentException("field '" + field + "' has a non-finite number");
      }
      return d == 0.0 ? 0.0 : d; // fold -0.0 into 0.0 so equality is unsurprising
    }
    throw new IllegalArgumentException(
        "field '"
            + field
            + "' must be a string, number or boolean, got "
            + (value == null ? "null" : value.getClass().getSimpleName()));
  }

  /** Checks a field name: non-empty and at most {@link #MAX_FIELD_NAME_LENGTH} chars. */
  public static void requireFieldName(String field) {
    if (field == null || field.isEmpty() || field.length() > MAX_FIELD_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "field names must be 1-" + MAX_FIELD_NAME_LENGTH + " chars: " + field);
    }
  }
}
