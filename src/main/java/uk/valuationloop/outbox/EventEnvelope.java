package uk.valuationloop.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import uk.valuationloop.observability.EventTracing;

/**
 * The JSON envelope every event travels in (ARCHITECTURE.md §4).
 * eventId is the identity of one event (and the key schema validation reports against). Consumers do not dedupe on
 * it: each is idempotent by a natural key (lead state, the scorecard upsert by lead_id, instructed_at, the
 * completion_match primary key), so there is no dedupe table (docs/EVENTS.md, "Idempotency rule").
 * The envelope is the whole Kafka value, serialised as a JSON string; no type headers, no framework magic.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String requestId,
        String leadId,
        String agentId,
        String traceparent,
        Map<String, Object> payload) {

    public static EventEnvelope of(String eventType, String requestId, String leadId, String agentId,
                                   Map<String, Object> payload) {
        return new EventEnvelope(UuidV7.next(), eventType, 1, Instant.now(), requestId, leadId, agentId,
                                 currentTraceparent(), payload);
    }

    /**
     * The W3C traceparent of the span active when the event is written (normally the API request's), so the relay
     * and the consumers can log under the same traceId later. Null means "no trace" (tracing off, as in tests).
     */
    private static String currentTraceparent() {
        return EventTracing.currentTraceparent();
    }
}
