package uk.valuationloop.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import uk.valuationloop.delivery.DeliverLead.MalformedCommand;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.Topics;

/**
 * The Kafka consumer for lead.deliver.v1, with non-blocking retries and a dead letter topic (ARCHITECTURE.md §6).
 *
 * <pre>
 *   lead.deliver.v1          attempt 1  --fails-->  lead.deliver.v1-retry-0  (after 500 ms)
 *   lead.deliver.v1-retry-0  attempt 2  --fails-->  lead.deliver.v1-retry-1  (after 1000 ms)
 *   lead.deliver.v1-retry-1  attempt 3  --fails-->  lead.deliver.v1-dlt      -> onDeadLetter: DELIVERY_FAILED
 * </pre>
 *
 * "Non-blocking": a failing record is re-published to the next topic and the partition moves on, so one flaky CRM
 * never holds up the leads behind it. spring-kafka creates the extra topics and listeners from the annotation.
 *
 * Shape (H2-review E2-5): this method is NOT @Transactional. It calls the service (which commits its own
 * transactions through Spring's proxy) and returns; the container then commits the offset (ack-mode: record).
 * So the offset is only committed after the database commit; a crash in between means the record comes again,
 * and the service's idempotency check makes the second copy a no-op.
 */
@Component
public class LeadDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(LeadDeliveryListener.class);

    private final LeadDeliveryService service;
    private final ObjectMapper objectMapper;

    public LeadDeliveryListener(LeadDeliveryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @RetryableTopic(
            attempts = "3",                                          // 1 try on the main topic + 2 retry topics
            backoff = @Backoff(delay = 500, multiplier = 2.0),       // 500 ms, then 1000 ms
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, // -retry-0, -retry-1 (not -retry-500)
            retryTopicSuffix = "-retry",
            dltTopicSuffix = "-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,                 // if the DLT handler itself fails: log, do not loop
            exclude = {MalformedCommand.class, AdapterRegistry.UnknownAdapter.class}, // cannot heal: straight to the DLT
            kafkaTemplate = "kafkaTemplate")                         // Boot's template publishes to the retry/DLT topics
    @KafkaListener(
            id = "lead-delivery",
            topics = Topics.LEAD_DELIVER,
            groupId = "delivery",
            autoStartup = "${valuationloop.delivery.listener.auto-startup:true}")
    public void onDeliverLead(String envelopeJson) {
        service.deliver(parse(envelopeJson));
    }

    /**
     * Every attempt failed. The record is already on lead.deliver.v1-dlt (that is how it got here), so it stays
     * inspectable and replayable; this marks the lead DELIVERY_FAILED and emits LeadDeliveryFailed (agent-private).
     */
    @DltHandler
    public void onDeadLetter(String envelopeJson,
                             @Header(name = KafkaHeaders.EXCEPTION_CAUSE_FQCN, required = false) byte[] causeClass) {
        DeliverLead cmd;
        try {
            cmd = parse(envelopeJson);
        } catch (MalformedCommand e) {
            log.error("unreadable record in the DLT, left there for a human: {}", e.getMessage());
            return;
        }
        // spring-kafka records the class of the exception the listener threw in a header (bytes, like every Kafka
        // header); the cause is the adapter's own exception, e.g. CrmUnavailable
        String cause = causeClass == null ? "unknown error" : simpleName(new String(causeClass, StandardCharsets.UTF_8));
        service.giveUp(cmd, "adapter '" + cmd.adapter() + "' failed on every attempt (" + cause + ")");
    }

    private DeliverLead parse(String envelopeJson) {
        try {
            return DeliverLead.from(objectMapper.readValue(envelopeJson, EventEnvelope.class));
        } catch (JsonProcessingException e) {
            throw new MalformedCommand("lead.deliver.v1 record is not a JSON envelope", e);
        }
    }

    /** "uk.valuationloop.delivery.CrmAdapter$CrmUnavailable" -> "CrmUnavailable" */
    private static String simpleName(String fqcn) {
        return fqcn.substring(Math.max(fqcn.lastIndexOf('.'), fqcn.lastIndexOf('$')) + 1);
    }
}
