package dev.thedal;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/**
 * Verifies the build wiring itself: tests run on the Java 21 toolchain and both the JUnit Jupiter
 * and jqwik engines are discovered.
 */
class BuildSmokeTest {

  @Test
  void runsOnJava21Toolchain() {
    assertThat(Runtime.version().feature()).isEqualTo(21);
  }

  @Property(tries = 50)
  void jqwikEngineRuns(@ForAll int value) {
    assertThat(Math.abs((long) value)).isNotNegative();
  }
}
