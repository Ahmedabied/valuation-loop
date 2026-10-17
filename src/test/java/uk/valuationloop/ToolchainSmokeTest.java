package uk.valuationloop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Proves the toolchain (JDK 21, Gradle, JUnit 5) before any real code exists. */
class ToolchainSmokeTest {

    record Agent(String id, String name) {}

    @Test
    void java21RecordsAndPatternSwitchCompile() {
        Object o = new Agent("ag_1", "fixture");
        String s = switch (o) {
            case Agent a -> a.name();
            default -> "other";
        };
        assertThat(s).isEqualTo("fixture");
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
