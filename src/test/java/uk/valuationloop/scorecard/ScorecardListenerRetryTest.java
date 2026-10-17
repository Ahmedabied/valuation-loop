package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.observability.LoopMetrics;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.Topics;

/**
 * The scorecard listener's error handling (ScorecardKafkaConfig): a transient database failure is retried in place
 * and heals; one that never heals is skipped after the retries, counted in scorecard_skipped_total, and the next
 * record on the same partition is still applied.
 *
 * The real listener and container factory run against an embedded broker; only the projector is wrapped, so it can
 * be told to fail a given event N times before it applies it. One partition, so "the next event" is behind the
 * failing one. The back-off is 50 ms here (production: 1000 ms), the 10 retries are the production number.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {Topics.LEAD_EVENTS, Topics.OUTCOME_RECORDED})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:scorecard_retry_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.scorecard.listener.auto-startup=true",
        "valuationloop.scorecard.retry-ms=50",
        "valuationloop.scorecard.retry-attempts=10"})
@Import(ScorecardListenerRetryTest.Config.class)
class ScorecardListenerRetryTest {

    static final Duration AWAIT = Duration.ofSeconds(30);

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ScorecardFactRepository facts;
    @Autowired LoopMetrics metrics;
    @Autowired FlakyProjector projector;

    @Test
    void aTransientFailureIsRetriedInPlaceAndTheFactIsWrittenOnce() throws Exception {
        double skippedBefore = metrics.scorecardSkippedSoFar();
        String json = delivered("vr_retry_heal", "ld_retry_heal");
        UUID eventId = ScorecardScript.parse(json).eventId();
        projector.failNext(eventId, 2);

        send("vr_retry_heal", json);

        await().atMost(AWAIT).until(() -> facts.findById("ld_retry_heal").isPresent());
        assertThat(projector.calls(eventId)).isEqualTo(3);              // 2 failures, then applied
        assertThat(facts.findAll()).filteredOn(f -> f.getLeadId().equals("ld_retry_heal")).hasSize(1);
        assertThat(metrics.scorecardSkippedSoFar() - skippedBefore).isZero();
    }

    @Test
    void aFailureThatNeverHealsIsSkippedAndCountedAndTheNextEventIsStillApplied() throws Exception {
        double skippedBefore = metrics.scorecardSkippedSoFar();
        String broken = delivered("vr_retry_skip", "ld_retry_skip");
        String next = delivered("vr_retry_next", "ld_retry_next");
        UUID brokenId = ScorecardScript.parse(broken).eventId();
        projector.failNext(brokenId, Integer.MAX_VALUE);

        send("vr_retry_skip", broken);
        send("vr_retry_next", next);   // same (only) partition, behind the broken one

        await().atMost(AWAIT).until(() -> facts.findById("ld_retry_next").isPresent());
        assertThat(projector.calls(brokenId)).isEqualTo(11);            // the first try + 10 retries
        assertThat(facts.findById("ld_retry_skip")).isEmpty();          // missing until make replay
        assertThat(metrics.scorecardSkippedSoFar() - skippedBefore).isEqualTo(1.0);
    }

    private String delivered(String requestId, String leadId) {
        return ScorecardScript.envelope(EventTypes.LEAD_DELIVERED, requestId, leadId, ScorecardScript.AG1,
                Duration.ZERO, Map.of("adapter", "ok", "attempt", 1));
    }

    private void send(String key, String json) throws Exception {
        kafka.send(Topics.LEAD_EVENTS, key, json).get(10, TimeUnit.SECONDS);
    }

    /** The real projector, made to throw a transient database error for chosen events a set number of times. */
    static class FlakyProjector extends ScorecardProjector {

        private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

        FlakyProjector(ScorecardFactRepository facts, Clock clock) {
            super(facts, clock);
        }

        void failNext(UUID eventId, int times) {
            failuresLeft.put(eventId, new AtomicInteger(times));
        }

        int calls(UUID eventId) {
            AtomicInteger n = calls.get(eventId);
            return n == null ? 0 : n.get();
        }

        @Override
        @Transactional
        public void apply(EventEnvelope e) {
            calls.computeIfAbsent(e.eventId(), id -> new AtomicInteger()).incrementAndGet();
            AtomicInteger left = failuresLeft.get(e.eventId());
            if (left != null && left.getAndDecrement() > 0) {
                throw new TransientDataAccessResourceException("simulated: database unavailable");
            }
            super.apply(e);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FlakyProjector flakyProjector(ScorecardFactRepository facts, Clock clock) {
            return new FlakyProjector(facts, clock);
        }
    }
}
