package uk.valuationloop.delivery;

import java.util.Map;
import java.util.UUID;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;

/**
 * The DeliverLead command as the delivery worker needs it: which lead, for which agent, through which adapter.
 * Parsed from the envelope on lead.deliver.v1 (schema: events/DeliverLead.schema.json).
 * A Java record is a frozen dataclass: constructor, getters, equals/hashCode and toString come for free.
 */
public record DeliverLead(UUID eventId, String requestId, String leadId, String agentId, String adapter) {

    /** Checks the envelope is really a DeliverLead before anything acts on it. */
    public static DeliverLead from(EventEnvelope envelope) {
        if (envelope == null || !EventTypes.DELIVER_LEAD.equals(envelope.eventType())) {
            throw new MalformedCommand("not a DeliverLead envelope: " + (envelope == null ? null : envelope.eventType()));
        }
        Map<String, Object> payload = envelope.payload();
        Object adapter = payload == null ? null : payload.get("adapter");
        if (envelope.leadId() == null || envelope.agentId() == null || !(adapter instanceof String name)) {
            throw new MalformedCommand("DeliverLead without leadId, agentId or adapter: " + envelope.eventId());
        }
        return new DeliverLead(envelope.eventId(), envelope.requestId(), envelope.leadId(), envelope.agentId(), name);
    }

    /**
     * A record that can never be processed (bad JSON, wrong type). Retrying cannot fix it, so the listener lists it
     * as a non-retryable exception and spring-kafka sends it straight to the dead letter topic.
     */
    public static class MalformedCommand extends RuntimeException {
        public MalformedCommand(String message) {
            super(message);
        }

        public MalformedCommand(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
