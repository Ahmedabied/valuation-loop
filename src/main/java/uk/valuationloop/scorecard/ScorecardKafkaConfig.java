package uk.valuationloop.scorecard;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uk.valuationloop.observability.LoopMetrics;
import uk.valuationloop.outbox.EventEnvelope;

/**
 * The scorecard listener's own container factory: what happens when the projector throws on a valid event (for
 * example Postgres is briefly down).
 *
 * <pre>
 *   attempt 1 fails  -> the container seeks back to that record, the partition pauses, wait retry-ms
 *   ... up to retry-attempts more tries, retry-ms apart (default 10 x 1000 ms: a 10 s outage heals in place)
 *   still failing    -> ERROR log with eventId / eventType / leadId, scorecard_skipped_total + 1,
 *                       offset committed, the partition moves on
 * </pre>
 *
 * Blocking, not non-blocking like delivery: the records behind a failing one wait, so the events of one request are
 * applied in order. Delivery uses retry topics because one flaky CRM must not hold up other agents' leads; here the
 * thing that fails is our own database, which every record needs, so moving on would only fail the next one too.
 *
 * No dead letter topic: the read model is rebuildable with `make replay` (ScorecardRebuilder reads both topics from
 * offset 0), and the skipped event is still on its topic. So after an outage longer than the retries, a skip is
 * logged and counted, and `make replay` restores the missing facts. A DLT would be a second copy of an event Kafka
 * already keeps.
 *
 * The rest of the factory is Boot's own configuration (ack-mode record, the consumer factory from application.yml),
 * applied through Boot's configurer, so only the error handler differs from the default factory the delivery
 * listener uses.
 */
@Configuration
public class ScorecardKafkaConfig {

    public static final String CONTAINER_FACTORY = "scorecardContainerFactory";

    private static final Logger log = LoggerFactory.getLogger(ScorecardKafkaConfig.class);

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> scorecardContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            LoopMetrics metrics, ObjectMapper objectMapper,
            @Value("${valuationloop.scorecard.retry-ms:1000}") long retryMs,
            @Value("${valuationloop.scorecard.retry-attempts:10}") long retryAttempts) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler(metrics, objectMapper, retryMs, retryAttempts));
        return factory;
    }

    /**
     * Retries in place retry-ms apart, retry-attempts times after the first try; then the recoverer logs, counts and
     * skips. Exceptions spring-kafka classes as never retryable (a record it cannot deserialize, for example) go
     * straight to the recoverer. Deliberately not a bean: Boot would apply a CommonErrorHandler bean to its default
     * factory too, and so to the delivery listener.
     */
    static DefaultErrorHandler errorHandler(LoopMetrics metrics, ObjectMapper objectMapper, long retryMs, long retryAttempts) {
        return new DefaultErrorHandler((record, ex) -> skip(record, ex, metrics, objectMapper),
                new FixedBackOff(retryMs, retryAttempts));
    }

    private static void skip(ConsumerRecord<?, ?> record, Exception ex, LoopMetrics metrics, ObjectMapper objectMapper) {
        metrics.scorecardSkipped();
        String where = record.topic() + "-" + record.partition() + "@" + record.offset();
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        try {
            EventEnvelope e = objectMapper.readValue(String.valueOf(record.value()), EventEnvelope.class);
            log.error("scorecard skips event {} {} for lead {} at {} after every retry failed ({}); "
                            + "scorecard_fact misses it until make replay",
                    e.eventId(), e.eventType(), e.leadId(), where, cause.toString());
        } catch (JacksonException notAnEnvelope) {
            log.error("scorecard skips an unreadable record at {} after every retry failed ({})", where, cause.toString());
        }
    }
}
