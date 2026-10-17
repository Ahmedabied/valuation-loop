package uk.valuationloop.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
 * The outbox claim: the state change and its event commit together; the relay sends at least once;
 * a crash between commit and send loses nothing. The relay scheduler is OFF in tests so the test can stand
 * in the gap and look at it.
 */
@SpringBootTest(properties = // its own in-memory database, never shared with another test context
        "spring.datasource.url=jdbc:h2:mem:outbox_relay_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@EmbeddedKafka(partitions = 1, topics = Topics.LEAD_EVENTS)
class OutboxRelayTest {

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
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "outbox-test-" + System.nanoTime(), false);
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, Topics.LEAD_EVENTS);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void eventCommitsWithTheTransactionAndIsSentExactlyOnceByTheRelay() throws Exception {
        EventEnvelope envelope = EventEnvelope.of("LeadReplied", "vr_test", "ld_test", "ag_fictional_01",
                Map.of("low", 500000, "high", 540000));

        // 1. the state change and the event are one commit
        tx.executeWithoutResult(status -> writer.write(Topics.LEAD_EVENTS, "vr_test", envelope));
        assertThat(repository.countBySentAtIsNull()).isEqualTo(1);

        // 2. "the process died here": nothing is on the topic yet
        ConsumerRecords<String, String> nothing = KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500));
        assertThat(nothing.count()).isZero();

        // 3. restart: the relay finds the committed row and sends it
        assertThat(relay.relayOnce()).isEqualTo(1);
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        assertThat(records.count()).isEqualTo(1);
        var record = records.iterator().next();
        assertThat(record.key()).isEqualTo("vr_test");
        EventEnvelope onWire = objectMapper.readValue(record.value(), EventEnvelope.class);
        assertThat(onWire.eventId()).isEqualTo(envelope.eventId());
        assertThat(onWire.eventType()).isEqualTo("LeadReplied");
        assertThat(onWire.payload()).containsEntry("low", 500000);

        // 4. a second relay run has nothing to do: the row is marked, no duplicate on the topic
        assertThat(relay.relayOnce()).isZero();
        assertThat(repository.countBySentAtIsNull()).isZero();
        assertThat(KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).count()).isZero();
    }

    @Test
    void rolledBackTransactionLeavesNoEventBehind() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.write(Topics.LEAD_EVENTS, "vr_rollback", EventEnvelope.of("LeadReplied", "vr_rollback", null, null, Map.of()));
            throw new RuntimeException("simulated failure after writing the event");
        })).hasMessageContaining("simulated failure");
        assertThat(repository.count()).isZero();
    }

    @Test
    void writingOutsideATransactionFailsLoudly() {
        assertThatThrownBy(() -> writer.write(Topics.LEAD_EVENTS, "vr_x", EventEnvelope.of("LeadReplied", "vr_x", null, null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }
}
