package dev.thedal.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterParserTest {

  @Test
  void parsesTheArchitectureExample() {
    Object json =
        Map.of(
            "and",
            List.of(
                Map.of("field", "lang", "eq", "ta"),
                Map.of("field", "year", "range", Map.of("gte", 2020)),
                Map.of("field", "tag", "in", List.of("news", "blog"))));
    Filter filter = FilterParser.parse(json);
    assertThat(filter)
        .isEqualTo(
            new Filter.And(
                List.of(
                    new Filter.Eq("lang", "ta"),
                    new Filter.Range("year", null, 2020.0, null, null),
                    new Filter.In("tag", List.of("news", "blog")))));

    assertThat(filter.matches(Map.of("lang", "ta", "year", 2021.0, "tag", "blog"))).isTrue();
    assertThat(filter.matches(Map.of("lang", "ta", "year", 2019.0, "tag", "blog"))).isFalse();
    assertThat(filter.matches(Map.of("lang", "ta", "year", 2021.0))).isFalse();
  }

  @Test
  void parsesOrAndNormalizesNumbersAndBooleans() {
    Filter filter =
        FilterParser.parse(
            Map.of(
                "or",
                List.of(
                    Map.of("field", "year", "eq", 2020),
                    Map.of("field", "draft", "eq", true),
                    Map.of("field", "score", "range", Map.of("gt", 0.5, "lte", 1)))));
    assertThat(filter.matches(Map.of("year", 2020.0))).isTrue();
    assertThat(filter.matches(Map.of("draft", true))).isTrue();
    assertThat(filter.matches(Map.of("score", 1.0))).isTrue();
    assertThat(filter.matches(Map.of("score", 0.5))).isFalse();
    assertThat(filter.matches(Map.of("year", "2020"))).isFalse(); // type-strict
  }

  @Test
  void rangeBoundsAreInclusiveOrExclusiveAsWritten() {
    Filter.Range gtLt = new Filter.Range("x", 1.0, null, 3.0, null);
    Filter.Range gteLte = new Filter.Range("x", null, 1.0, null, 3.0);
    for (double x : new double[] {1, 3}) {
      assertThat(gtLt.matches(Map.of("x", x))).isFalse();
      assertThat(gteLte.matches(Map.of("x", x))).isTrue();
    }
    assertThat(gtLt.matches(Map.of("x", 2.0))).isTrue();
    assertThat(gtLt.matches(Map.of("x", "2"))).isFalse();
  }

  @Test
  void rejectsMalformedFilters() {
    assertInvalid("not an object", "JSON object");
    assertInvalid(Map.of("eq", "x"), "'field'");
    assertInvalid(Map.of("field", "a"), "exactly one");
    assertInvalid(Map.of("field", "a", "eq", 1, "in", List.of(1)), "exactly one");
    assertInvalid(Map.of("field", "a", "like", "x"), "exactly one");
    assertInvalid(Map.of("field", "a", "in", "x"), "must be a list");
    assertInvalid(Map.of("field", "a", "in", List.of()), "non-empty list");
    assertInvalid(Map.of("field", "a", "range", Map.of()), "gt/gte/lt/lte");
    assertInvalid(Map.of("field", "a", "range", Map.of("ge", 1)), "unknown range bound");
    assertInvalid(Map.of("field", "a", "range", Map.of("gt", "1")), "must be numbers");
    assertInvalid(Map.of("field", "a", "range", Map.of("gt", 1, "gte", 2)), "same side");
    assertInvalid(Map.of("field", "a", "range", Map.of("lt", Double.NaN)), "finite");
    assertInvalid(Map.of("and", List.of(), "x", 1), "only key");
    assertInvalid(Map.of("and", "x"), "list of filters");
    assertInvalid(Map.of("or", List.of()), "non-empty list");
    assertInvalid(Map.of("field", "a", "eq", List.of(1)), "string, number or boolean");
    assertInvalid(Map.of("field", "", "eq", 1), "field names");
    Map<String, Object> nullEq = new HashMap<>();
    nullEq.put("field", "a");
    nullEq.put("eq", null);
    assertInvalid(nullEq, "got null");
  }

  @Test
  void rejectsExcessiveNesting() {
    Object json = Map.of("field", "a", "eq", 1);
    for (int i = 0; i <= FilterParser.MAX_DEPTH; i++) {
      json = Map.of("and", List.of(json));
    }
    Object tooDeep = json;
    assertThatThrownBy(() -> FilterParser.parse(tooDeep))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nested deeper");
  }

  @Test
  void metadataNormalization() {
    Map<String, Object> raw = new HashMap<>();
    raw.put("year", 2020);
    raw.put("score", -0.0f);
    raw.put("lang", "ta");
    raw.put("draft", false);
    Map<String, Object> normalized = Metadata.normalize(raw);
    assertThat(normalized)
        .containsEntry("year", 2020.0)
        .containsEntry("lang", "ta")
        .containsEntry("draft", false);
    assertThat(Double.doubleToRawLongBits((Double) normalized.get("score")))
        .isEqualTo(Double.doubleToRawLongBits(0.0));
    assertThat(Metadata.normalize(null)).isEmpty();
    assertThatThrownBy(() -> normalized.put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> Metadata.normalize(Map.of("x", Double.POSITIVE_INFINITY)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Metadata.normalize(Map.of("x", Map.of("y", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Metadata.normalize(Map.of("x".repeat(200), 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalid(Object json, String messagePart) {
    assertThatThrownBy(() -> FilterParser.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(messagePart);
  }
}
