package uk.valuationloop.scorecard;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.outbox.UuidV7;
import uk.valuationloop.support.EventSchemas;

/**
 * The scripted event log both scorecard tests use: 2 requests x 3 agents, every event type the scorecard reads,
 * written in a deliberately bad order, with one duplicate inside the log and three more for the "a repeat changes
 * nothing" step. ScorecardReplayTest sends it through Kafka; RandomOrderProjectionTest shuffles it.
 *
 * Every envelope is checked against its JSON Schema as it is built, so the script itself honours the contract.
 */
final class ScorecardScript {

    static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");
    static final String A = "vr_replay_a", B = "vr_replay_b";
    static final String AG1 = "ag_fictional_01", AG2 = "ag_fictional_02", AG3 = "ag_fictional_03";
    static final String A1 = "ld_replay_a1", A2 = "ld_replay_a2", A3 = "ld_replay_a3";
    static final String B1 = "ld_replay_b1", B2 = "ld_replay_b2", B3 = "ld_replay_b3";
    static final Set<String> LEADS = Set.of(A1, A2, A3, B1, B2, B3);

    /** ISO-8601 instants, like the app's own mapper; used to write the envelopes and to read them back. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)   // Jackson 3 has java.time built in
            .build();

    /** One record as it goes on the wire: topic, key (the requestId) and the envelope JSON. */
    record Scripted(String topic, String key, String json) {}

    final String a2Reply;
    final String a1Instructed;
    final String a1Completed;
    private final List<Scripted> log = new ArrayList<>();

    ScorecardScript() {
        Duration m = Duration.ofMinutes(1);
        a2Reply = envelope(EventTypes.LEAD_REPLIED, A, A2, AG2, m.multipliedBy(300), reply(520000, 580000, false, m.multipliedBy(300)));
        a1Instructed = envelope(EventTypes.INSTRUCTION_RECORDED, A, A1, AG1, Duration.ofDays(3),
                Map.of("leadRef", A1, "source", "fake-crm", "recordedAt", T0.plus(Duration.ofDays(3)).toString()));
        a1Completed = envelope(EventTypes.COMPLETION_MATCHED, A, A1, AG1, Duration.ofDays(40),
                completion("ppd-replay-a1", 531_000, "2026-11-06"));

        // the outcome topic first: A1's completion, then its instruction, arrive before anything else is known about A1
        outcome(A, a1Completed);
        outcome(A, a1Instructed);
        outcome(B, envelope(EventTypes.INSTRUCTION_RECORDED, B, B2, AG2, Duration.ofDays(2),
                Map.of("leadRef", B2, "source", "fake-crm", "recordedAt", T0.plus(Duration.ofDays(2)).toString())));
        // B2's completion arrives in the natural order: after its instruction
        outcome(B, envelope(EventTypes.COMPLETION_MATCHED, B, B2, AG2, Duration.ofDays(50),
                completion("ppd-replay-b2", 640_000, "2026-11-15")));

        // request A on lead.events.v1, in this (bad) order
        lead(A, envelope(EventTypes.VISIT_BOOKED, A, A1, AG1, Duration.ofDays(1), mapOf("visitAt", null)));
        lead(A, envelope(EventTypes.SELLER_SELECTED, A, A1, AG1, Duration.ofHours(6), Map.of()));
        lead(A, a2Reply);
        lead(A, envelope(EventTypes.LEAD_EXPIRED, A, A3, AG3, Duration.ofHours(24), Map.of("replyDueAt", T0.plus(Duration.ofHours(24)).toString())));
        lead(A, envelope(EventTypes.LEAD_REPLIED, A, A1, AG1, m.multipliedBy(90), reply(500000, 540000, false, m.multipliedBy(90))));
        lead(A, envelope(EventTypes.LEAD_DELIVERED, A, A1, AG1, Duration.ZERO, Map.of("adapter", "ok", "attempt", 1)));
        lead(A, envelope(EventTypes.EVIDENCE_ATTACHED, A, A1, AG1, m.multipliedBy(95), Map.of("packId", "ep_replay_a1",
                "comparablesCount", 4, "comparablesRange", Map.of("low", 495000, "mid", 520000, "high", 545000))));
        lead(A, envelope(EventTypes.SELLER_ENGAGED, A, A2, AG2, Duration.ofHours(5), Map.of("action", "VIEWED")));
        lead(A, envelope(EventTypes.LEAD_REPLIED, A, A3, AG3, Duration.ofHours(30), reply(480000, 500000, true, Duration.ofHours(30)))); // late, after expiry
        lead(A, a2Reply);   // the same event again (same eventId): at-least-once delivery
        lead(A, envelope(EventTypes.LEAD_DELIVERED, A, A2, AG2, Duration.ZERO, Map.of("adapter", "slow", "attempt", 1)));
        lead(A, envelope(EventTypes.SELLER_ENGAGED, A, A1, AG1, Duration.ofHours(5), Map.of("action", "CONTACTED")));
        lead(A, envelope(EventTypes.LEAD_DELIVERED, A, A3, AG3, Duration.ZERO, Map.of("adapter", "flaky", "attempt", 3)));
        // request B
        lead(B, envelope(EventTypes.VISIT_BOOKED, B, B1, AG1, Duration.ofHours(20), mapOf("visitAt", T0.plus(Duration.ofDays(2)).toString())));
        lead(B, envelope(EventTypes.LEAD_REPLIED, B, B2, AG2, m.multipliedBy(120), reply(650000, 690000, false, m.multipliedBy(120))));
        lead(B, envelope(EventTypes.LEAD_DELIVERY_FAILED, B, B3, AG3, Duration.ofMinutes(2), Map.of("adapter", "flaky", "attempts", 3, "reason", "adapter 'flaky' failed on every attempt (CrmUnavailable)")));
        lead(B, envelope(EventTypes.LEAD_DELIVERED, B, B1, AG1, Duration.ZERO, Map.of("adapter", "ok", "attempt", 1)));
        lead(B, envelope(EventTypes.LEAD_REPLIED, B, B1, AG1, m.multipliedBy(60), reply(700000, 760000, false, m.multipliedBy(60))));
        lead(B, envelope(EventTypes.LEAD_DELIVERED, B, B2, AG2, Duration.ZERO, Map.of("adapter", "slow", "attempt", 1)));
    }

    /** The log in the scripted order: the outcome records first, then lead.events.v1. */
    List<Scripted> log() {
        return List.copyOf(log);
    }

    /** Copies of events already in the log (same eventIds), as an at-least-once relay would resend them. */
    List<Scripted> duplicates() {
        return List.of(new Scripted(Topics.LEAD_EVENTS, A, a2Reply),
                new Scripted(Topics.OUTCOME_RECORDED, A, a1Instructed),
                new Scripted(Topics.OUTCOME_RECORDED, A, a1Completed));
    }

    static EventEnvelope parse(String json) {
        try {
            return JSON.readValue(json, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private void outcome(String requestId, String json) {
        log.add(new Scripted(Topics.OUTCOME_RECORDED, requestId, json));
    }

    private void lead(String requestId, String json) {
        log.add(new Scripted(Topics.LEAD_EVENTS, requestId, json));
    }

    /** An envelope as the outbox would write it, at a fixed time; returned as the JSON that goes on the wire. */
    private static String envelope(String type, String requestId, String leadId, String agentId, Duration at,
                                   Map<String, Object> payload) {
        try {
            String json = JSON.writeValueAsString(new EventEnvelope(UuidV7.next(), type, 1, T0.plus(at),
                    requestId, leadId, agentId, null, payload));
            EventSchemas.assertValid(json);
            return json;
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> reply(long low, long high, boolean late, Duration at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("low", low);
        p.put("high", high);
        p.put("message", null);
        p.put("late", late);
        p.put("deliveredAt", T0.toString());
        p.put("repliedAt", T0.plus(at).toString());
        return p;
    }

    static Map<String, Object> completion(String ppdTransactionId, long price, String completedAt) {
        return Map.of("ppdTransactionId", ppdTransactionId, "completedPrice", price, "completedAt", completedAt,
                "matchRule", "postcode+propertyType+dateOfTransfer>=instructedAt-30d");
    }

    /** Map.of() refuses null values; VisitBooked's visitAt may be null. */
    static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
