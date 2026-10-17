package uk.valuationloop.delivery;

import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Re-runs a transactional step when it lost an optimistic-lock race (H2-review E2-8).
 *
 * agent_lead and valuation_request carry @Version: when two workers (delivery, deadline scanner, reply API) update
 * the same row, the second commit fails instead of silently overwriting the first. That is a "try again with fresh
 * data" signal, not a delivery failure, so it must never travel to the Kafka retry topics or the DLT. This loop
 * catches it, waits a moment, and calls the step again; the step reloads the row and decides afresh (often:
 * "already done, skip").
 *
 * The step must be a call through a Spring proxy (another bean's @Transactional method) so that each try is a new
 * transaction. Plain loop instead of Spring Retry: five lines the reader can see, no annotation magic.
 */
public final class OptimisticLockRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetry.class);

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration BACKOFF = Duration.ofMillis(20);

    private OptimisticLockRetry() {}

    public static <T> T run(Supplier<T> step) {
        return run(MAX_ATTEMPTS, BACKOFF, step);
    }

    /** Runs a step that returns nothing. */
    public static void runVoid(Runnable step) {
        run(() -> {
            step.run();
            return null;
        });
    }

    public static <T> T run(int maxAttempts, Duration backoff, Supplier<T> step) {
        for (int attempt = 1; ; attempt++) {
            try {
                return step.get();
            } catch (OptimisticLockingFailureException | OptimisticLockException e) {
                if (attempt >= maxAttempts) {
                    throw e; // five lost races in a row is not noise any more; let the caller see it
                }
                log.debug("optimistic lock conflict, retrying (attempt {} of {})", attempt, maxAttempts);
                sleep(backoff.multipliedBy(attempt));
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off an optimistic lock retry", e);
        }
    }
}
