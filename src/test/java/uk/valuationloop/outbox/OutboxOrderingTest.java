package uk.valuationloop.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay sends rows in a fixed order: created_at, then id. Several events written in one transaction reach the
 * topic in the order they were written, even when they share a timestamp. The relay scheduler is OFF in tests; each
 * test calls relayOnce() itself and reads back only its own partition key.
 */
@SpringBootTest(properties = // its own in-memory database, never shared with another test context
        "spring.datasource.url=jdbc:h2:mem:outbox_ordering_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@EmbeddedKafka(kraft = true, partitions = 1, topics = Topics.LEAD_EVENTS)
class OutboxOrderingTest {

    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay.OutboxRelayBatch relay;
    @Autowired OutboxRepository repository;
    @Autowired TransactionTemplate tx;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper objectMapper;

    Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        Map<String, Object> props = KafkaTestUtils.consumerProps("outbox-order-" + System.nanoTime(), "false", broker);
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, Topics.LEAD_EVENTS);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void threeEventsWrittenInOneTransactionReachTheTopicInWriteOrder() throws Exception {
        List<String> written = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (String type : List.of("LeadDelivered", "LeadReplied", "EvidenceAttached")) {
                EventEnvelope e = EventEnvelope.of(type, "vr_order", "ld_order", "ag_fictional_01", Map.of());
                writer.write(Topics.LEAD_EVENTS, "vr_order", e);
                written.add(e.eventId().toString());
            }
        });

        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(eventIdsOnTheTopic("vr_order", 3)).containsExactlyElementsOf(written);
    }

    @Test
    void rowsWithTheSameCreatedAtGoInIdOrderNotInsertOrder() throws Exception {
        Instant sameInstant = Instant.parse("2026-10-17T09:00:00Z");
        List<EventEnvelope> inIdOrder = new ArrayList<>();
        for (String type : List.of("LeadDelivered", "LeadReplied", "EvidenceAttached")) {
            inIdOrder.add(EventEnvelope.of(type, "vr_tie", "ld_tie", "ag_fictional_01", Map.of()));
        }
        // inserted newest id first, all with one created_at: only the id tie-break puts them back in order
        tx.executeWithoutResult(status -> inIdOrder.reversed().forEach(e -> repository.save(new OutboxEvent(
                e.eventId(), Topics.LEAD_EVENTS, "vr_tie", e.eventType(), json(e), sameInstant))));

        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(eventIdsOnTheTopic("vr_tie", 3))
                .containsExactlyElementsOf(inIdOrder.stream().map(e -> e.eventId().toString()).toList());
    }

    private String json(EventEnvelope e) {
        try {
            return objectMapper.writeValueAsString(e);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The eventIds on the topic for one partition key, in topic order (one partition, so offset order). */
    private List<String> eventIdsOnTheTopic(String key, int expected) throws Exception {
        List<String> ids = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ids.size() < expected && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                if (key.equals(r.key())) {
                    ids.add(objectMapper.readTree(r.value()).path("eventId").asText());
                }
            }
        }
        return ids;
    }
}
