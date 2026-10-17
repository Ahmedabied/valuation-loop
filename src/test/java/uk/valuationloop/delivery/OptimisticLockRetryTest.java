package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The in-process retry for lost optimistic-lock races (H2-review E2-8). */
class OptimisticLockRetryTest {

    final AtomicInteger calls = new AtomicInteger();

    @Test
    void retriesAConflictAndReturnsTheResultOfTheTryThatWon() {
        String result = OptimisticLockRetry.run(5, Duration.ZERO, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("AgentLead", "ld_x"); // what Spring throws at commit
            }
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(3);
    }

    @Test
    void alsoRetriesTheRawJpaException() {
        String result = OptimisticLockRetry.run(5, Duration.ZERO, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new OptimisticLockException("row changed");
            }
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(2);
    }

    @Test
    void givesUpAfterMaxAttemptsAndRethrows() {
        assertThatThrownBy(() -> OptimisticLockRetry.run(4, Duration.ZERO, () -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("AgentLead", "ld_x");
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void otherErrorsAreNotRetried() {
        // an adapter failure must reach the listener at once, so @RetryableTopic (not this loop) handles it
        assertThatThrownBy(() -> OptimisticLockRetry.run(5, Duration.ZERO, () -> {
            calls.incrementAndGet();
            throw new CrmAdapter.CrmUnavailable("down");
        })).isInstanceOf(CrmAdapter.CrmUnavailable.class);
        assertThat(calls).hasValue(1);
    }
}
