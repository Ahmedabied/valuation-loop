package uk.valuationloop.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Test helper: reads topics and keeps every record it has seen, so a test can poll inside Awaitility and then
 * filter by requestId or leadId (the shared test context means earlier tests' records are on the topics too).
 * Partitions are assigned by hand rather than through a consumer group, so there is no rebalance delay.
 */
public class KafkaTail implements AutoCloseable {

    private final Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    private KafkaTail(EmbeddedKafkaBroker broker, boolean fromEnd, String... topics) {
        consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("tail-" + System.nanoTime(), "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            consumer.partitionsFor(topic, Duration.ofSeconds(10))
                    .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        }
        consumer.assign(partitions);
        if (fromEnd) {
            consumer.seekToEnd(partitions);
        } else {
            consumer.seekToBeginning(partitions);
        }
        partitions.forEach(consumer::position); // resolve the seek now, not lazily on the first poll
    }

    public static KafkaTail fromBeginning(EmbeddedKafkaBroker broker, String... topics) {
        return new KafkaTail(broker, false, topics);
    }

    public static KafkaTail fromEnd(EmbeddedKafkaBroker broker, String... topics) {
        return new KafkaTail(broker, true, topics);
    }

    /** Polls once and returns everything seen so far that matches. */
    public List<ConsumerRecord<String, String>> poll(Predicate<ConsumerRecord<String, String>> filter) {
        consumer.poll(Duration.ofMillis(100)).forEach(seen::add);
        return seen.stream().filter(filter).toList();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
