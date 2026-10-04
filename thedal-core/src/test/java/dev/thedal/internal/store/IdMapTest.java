package dev.thedal.internal.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class IdMapTest {

  @Test
  void bindsAndLooksUpBothWays() {
    IdMap map = new IdMap();
    assertThat(map.bind("a", 0)).isEqualTo(IdMap.ABSENT);
    assertThat(map.bind("b", 1)).isEqualTo(IdMap.ABSENT);
    assertThat(map.ordOf("a")).isZero();
    assertThat(map.idOf(1)).isEqualTo("b");
    assertThat(map.size()).isEqualTo(2);
  }

  @Test
  void rebindingAnIdReturnsAndReleasesTheOldOrdinal() {
    IdMap map = new IdMap();
    map.bind("a", 0);
    assertThat(map.bind("a", 3)).isZero();
    assertThat(map.ordOf("a")).isEqualTo(3);
    assertThat(map.idOf(0)).isNull();
    assertThat(map.idOf(3)).isEqualTo("a");
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  void rebindingToTheSameOrdinalIsANoOp() {
    IdMap map = new IdMap();
    map.bind("a", 2);
    assertThat(map.bind("a", 2)).isEqualTo(2);
    assertThat(map.idOf(2)).isEqualTo("a");
  }

  @Test
  void unbindRemovesBothDirections() {
    IdMap map = new IdMap();
    map.bind("a", 0);
    assertThat(map.unbind("a")).isZero();
    assertThat(map.ordOf("a")).isEqualTo(IdMap.ABSENT);
    assertThat(map.idOf(0)).isNull();
    assertThat(map.unbind("a")).isEqualTo(IdMap.ABSENT);
    assertThat(map.size()).isZero();
  }

  @Test
  void unknownLookupsReturnSentinels() {
    IdMap map = new IdMap();
    assertThat(map.ordOf("missing")).isEqualTo(IdMap.ABSENT);
    assertThat(map.idOf(0)).isNull();
    assertThat(map.idOf(-1)).isNull();
  }

  @Test
  void rejectsInvalidIdsAndOrdinals() {
    IdMap map = new IdMap();
    assertThatThrownBy(() -> map.bind(null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.bind("", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.bind("x".repeat(IdMap.MAX_ID_LENGTH + 1), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> map.bind("a", -1)).isInstanceOf(IllegalArgumentException.class);
    map.bind("x".repeat(IdMap.MAX_ID_LENGTH), 0);
    assertThat(map.size()).isEqualTo(1);
  }

  @Test
  void refusesToStealAnOrdinalBoundToAnotherId() {
    IdMap map = new IdMap();
    map.bind("a", 0);
    assertThatThrownBy(() -> map.bind("b", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already bound to id a");
    assertThat(map.ordOf("b")).isEqualTo(IdMap.ABSENT);
  }

  @Provide
  Arbitrary<String> ids() {
    return Arbitraries.of("a", "b", "c", "d", "e");
  }

  /** Random bind/unbind sequences against a HashMap model; ordinals are always fresh, as in use. */
  @Property(tries = 300)
  void matchesAMapModel(
      @ForAll("ids") String id1,
      @ForAll("ids") String id2,
      @ForAll("ids") String id3,
      @ForAll("ids") String id4) {
    IdMap map = new IdMap();
    Map<String, Integer> model = new HashMap<>();
    String[] ops = {id1, id2, id3, id4, id1, id3};
    int nextOrd = 0;
    for (int step = 0; step < ops.length; step++) {
      String id = ops[step];
      if (step % 3 == 2) {
        Integer expected = model.remove(id);
        assertThat(map.unbind(id)).isEqualTo(expected == null ? IdMap.ABSENT : expected);
      } else {
        Integer expected = model.put(id, nextOrd);
        assertThat(map.bind(id, nextOrd)).isEqualTo(expected == null ? IdMap.ABSENT : expected);
        nextOrd++;
      }
    }
    assertThat(map.size()).isEqualTo(model.size());
    model.forEach(
        (id, ord) -> {
          assertThat(map.ordOf(id)).isEqualTo(ord);
          assertThat(map.idOf(ord)).isEqualTo(id);
        });
  }
}
