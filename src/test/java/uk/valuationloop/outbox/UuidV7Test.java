package uk.valuationloop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void idsMadeOneAfterAnotherAlwaysSortInTheOrderTheyWereMade() {
        UUID previous = UuidV7.next();
        for (int i = 0; i < 20_000; i++) {     // far more than one millisecond's worth: exercises the counter
            UUID next = UuidV7.next();
            assertThat(Long.compareUnsigned(next.getMostSignificantBits(), previous.getMostSignificantBits()))
                    .as("id %d sorts after the one before it", i).isPositive();
            assertThat(next.version()).isEqualTo(7);
            assertThat(next.variant()).isEqualTo(2);
            previous = next;
        }
    }
}
