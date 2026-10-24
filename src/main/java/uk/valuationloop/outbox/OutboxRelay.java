package uk.valuationloop.outbox;

import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.observability.EventTracing;
import uk.valuationloop.observability.LoopMetrics;

/**
 * Moves committed outbox rows onto Kafka.
 *
 * <pre>
 *   lock a batch of unsent rows (FOR UPDATE SKIP LOCKED)
 *   for each: send to Kafka and wait for the broker ack; mark sent_at
 *   commit
 * </pre>
 *
 * If the process dies after the send but before the commit, the row is still unsent and goes again on restart:
 * at-least-once delivery. Every consumer is idempotent by natural key (lead state, upsert by lead_id), which makes
 * the whole chain effectively-once; there is no dedupe table. That is the delivery guarantee.
 *
 * The scheduled tick and the transactional batch are separate beans on purpose: a @Transactional method called
 * from inside the same class is NOT proxied, so the transaction would silently not exist.
 *
 * Every tick ends by sampling the unsent count into the outbox_unsent gauge (a growing value means Kafka is
 * unreachable or the relay is stuck). {@link #pause()} / {@link #resume()} exist for one caller only, the demo
 * profile's simulator (POST /simulator/outbox-gap), which uses them to show the gap between commit and send
 * without killing the JVM; nothing else calls them, so in a normal run the relay is never paused.
 */
@Component
public class OutboxRelay {

    private final OutboxRelayBatch batch;
    private final OutboxRepository repository;
    private final LoopMetrics metrics;
    private final boolean enabled;
    private volatile boolean paused;   // volatile: set by a request thread, read by the scheduler thread

    public OutboxRelay(OutboxRelayBatch batch, OutboxRepository repository, LoopMetrics metrics,
                       @Value("${valuationloop.outbox.enabled:true}") boolean enabled) {
        this.batch = batch;
        this.repository = repository;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /** Whether this process sends outbox rows (valuationloop.outbox.enabled: on in "worker" and one process, off in "api"). */
    public boolean isEnabled() {
        return enabled;
    }

    @Scheduled(fixedDelayString = "${valuationloop.outbox.relay-delay-ms:300}")
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!paused) {
            int sent;
            do {
                sent = batch.relayOnce();
            } while (sent > 0); // drain a backlog quickly, then go back to sleep
        }
        metrics.outboxUnsent(repository.countBySentAtIsNull());
    }

    /** Demo only: stop sending (rows keep committing and pile up as unsent). */
    public void pause() {
        paused = true;
    }

    /** Demo only: send again from the next tick. */
    public void resume() {
        paused = false;
    }

    @Component
    public static class OutboxRelayBatch {

        private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

        private final OutboxRepository repository;
        private final KafkaTemplate<String, String> kafka;
        private final ObjectMapper objectMapper;
        private final int batchSize;

        public OutboxRelayBatch(OutboxRepository repository, KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                @Value("${valuationloop.outbox.batch-size:100}") int batchSize) {
            this.repository = repository;
            this.kafka = kafka;
            this.objectMapper = objectMapper;
            this.batchSize = batchSize;
        }

        /** Sends one locked batch. Returns how many rows were marked sent. */
        @Transactional
        public int relayOnce() {
            List<OutboxEvent> rows = repository.lockUnsentBatch(batchSize);
            int sent = 0;
            for (OutboxEvent row : rows) {
                boolean ok = EventTracing.inTrace(traceparentOf(row), "outbox.send", () -> send(row));
                if (!ok) {
                    break; // keep ordering: do not send a later row for the same key before this one succeeds
                }
                sent++;
            }
            return sent;
        }

        private boolean send(OutboxEvent row) {
            try {
                kafka.send(row.getTopic(), row.getPartitionKey(), row.getPayload()).get(10, TimeUnit.SECONDS);
                row.markSent(Instant.now());
                log.info("outbox sent {} {} to {}", row.getEventType(), row.getId(), row.getTopic());
                return true;
            } catch (Exception e) {
                row.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("outbox send failed for {} ({}), will retry", row.getId(), row.getEventType(), e);
                return false;
            }
        }

        /** The traceparent the envelope was written with, so this send logs under the request's traceId. */
        private String traceparentOf(OutboxEvent row) {
            try {
                return objectMapper.readTree(row.getPayload()).path("traceparent").asString(null);
            } catch (Exception e) {
                return null;   // not our envelope shape: send it anyway, just without a trace
            }
        }
    }
}
