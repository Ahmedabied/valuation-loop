package uk.valuationloop.scorecard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import uk.valuationloop.observability.EventTracing;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.Topics;

/**
 * The scorecard's Kafka consumer: lead.events.v1 + outcome.recorded.v1, consumer group "scorecard".
 *
 * Shape (H2-review E2-5): this method is NOT @Transactional. It calls the projector, whose @Transactional method
 * commits through Spring's proxy, and returns; only then does the container commit the offset. A crash between the
 * two means the record comes again, and the upsert by lead_id makes the second copy a no-op.
 *
 * auto.offset.reset=earliest: a brand-new scorecard group starts from the beginning of the topics, because the read
 * model is the whole history, not just what happens after it was deployed.
 */
@Component
public class ScorecardListener {

    /** The container id; ScorecardRebuilder stops and starts the listener by this name. */
    public static final String LISTENER_ID = "scorecard";
    public static final String GROUP_ID = "scorecard";

    private static final Logger log = LoggerFactory.getLogger(ScorecardListener.class);

    private final ScorecardProjector projector;
    private final ObjectMapper objectMapper;

    public ScorecardListener(ScorecardProjector projector, ObjectMapper objectMapper) {
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = {Topics.LEAD_EVENTS, Topics.OUTCOME_RECORDED},
            groupId = GROUP_ID,
            properties = "auto.offset.reset=earliest",
            autoStartup = "${valuationloop.scorecard.listener.auto-startup:true}")
    public void onEvent(String envelopeJson) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            // retrying cannot fix a record that is not an envelope; log it and move on rather than block the partition
            log.warn("scorecard skips a record that is not an event envelope: {}", e.getOriginalMessage());
            return;
        }
        // the envelope's traceparent makes this log under the traceId of the API request that caused the event
        EventTracing.inTrace(envelope.traceparent(), "scorecard.apply", () -> projector.apply(envelope));
    }
}
