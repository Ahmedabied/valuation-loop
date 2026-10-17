package uk.valuationloop.outbox;

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
 * at-least-once delivery. Consumers dedupe on eventId (or upsert by natural key), which makes the whole chain
 * effectively-once. That sentence is the one to say in the interview.
 *
 * The scheduled tick and the transactional batch are separate beans on purpose: a @Transactional method called
 * from inside the same class is NOT proxied, so the transaction would silently not exist.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayBatch batch;
    private final boolean enabled;

    public OutboxRelay(OutboxRelayBatch batch, @Value("${valuationloop.outbox.enabled:true}") boolean enabled) {
        this.batch = batch;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${valuationloop.outbox.relay-delay-ms:300}")
    public void tick() {
        if (!enabled) {
            return;
        }
        int sent;
        do {
            sent = batch.relayOnce();
        } while (sent > 0); // drain a backlog quickly, then go back to sleep
    }

    @Component
    public static class OutboxRelayBatch {

        private final OutboxRepository repository;
        private final KafkaTemplate<String, String> kafka;
        private final int batchSize;

        public OutboxRelayBatch(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                                @Value("${valuationloop.outbox.batch-size:100}") int batchSize) {
            this.repository = repository;
            this.kafka = kafka;
            this.batchSize = batchSize;
        }

        /** Sends one locked batch. Returns how many rows were marked sent. */
        @Transactional
        public int relayOnce() {
            List<OutboxEvent> rows = repository.lockUnsentBatch(batchSize);
            int sent = 0;
            for (OutboxEvent row : rows) {
                try {
                    kafka.send(row.getTopic(), row.getPartitionKey(), row.getPayload()).get(10, TimeUnit.SECONDS);
                    row.markSent(Instant.now());
                    sent++;
                } catch (Exception e) {
                    row.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("outbox send failed for {} ({}), will retry", row.getId(), row.getEventType(), e);
                    break; // keep ordering: do not send a later row for the same key before this one succeeds
                }
            }
            return sent;
        }
    }
}
