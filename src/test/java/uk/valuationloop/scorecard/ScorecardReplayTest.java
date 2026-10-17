package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import uk.valuationloop.comparison.LoopApiTestBase;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.outbox.UuidV7;
import uk.valuationloop.support.EventSchemas;

/**
 * Claim 3: out-of-order and late events still join to the lead, a duplicate changes nothing, and a replay
 * (truncate, reset offsets, restart) rebuilds identical facts.
 *
 * The test plays the role of every producer: it writes schema-valid envelopes straight onto lead.events.v1 and
 * outcome.recorded.v1 in a deliberately bad order, for 2 requests x 3 agents, and lets the real listener consume them.
 */
class ScorecardReplayTest extends LoopApiTestBase {

    static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");
    static final String A = "vr_replay_a", B = "vr_replay_b";
    static final String AG1 = "ag_fictional_01", AG2 = "ag_fictional_02", AG3 = "ag_fictional_03";
    static final String A1 = "ld_replay_a1", A2 = "ld_replay_a2", A3 = "ld_replay_a3";
    static final String B1 = "ld_replay_b1", B2 = "ld_replay_b2", B3 = "ld_replay_b3";
    static final Set<String> OURS = Set.of(A1, A2, A3, B1, B2, B3);

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ScorecardRebuilder rebuilder;
    @Autowired ScorecardFactRepository facts;

    /** An envelope as the outbox would write it, at a fixed time; returned as the JSON that goes on the wire. */
    String envelope(String type, String requestId, String leadId, String agentId, Duration at, Map<String, Object> payload)
            throws Exception {
        String json = this.json.writeValueAsString(new EventEnvelope(UuidV7.next(), type, 1, T0.plus(at),
                requestId, leadId, agentId, null, payload));
        EventSchemas.assertValid(json);   // the script itself honours the contract
        return json;
    }

    static Map<String, Object> reply(long low, long high, boolean late) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("low", low);
        p.put("high", high);
        p.put("message", null);
        p.put("late", late);
        p.put("deliveredAt", T0.toString());
        return p;
    }

    void send(String topic, String requestId, String json) throws Exception {
        kafka.send(topic, requestId, json).get(10, TimeUnit.SECONDS);
    }

    List<FactSnapshot> ourFacts() {
        return facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of).filter(f -> OURS.contains(f.leadId())).toList();
    }

    FactSnapshot fact(List<FactSnapshot> all, String leadId) {
        return all.stream().filter(f -> f.leadId().equals(leadId)).findFirst().orElseThrow();
    }

    @Test
    void outOfOrderLateAndDuplicateEventsProjectDeterministicallyAndReplayRebuildsIdenticalFacts() throws Exception {
        Duration m = Duration.ofMinutes(1);
        String a2Reply = envelope(EventTypes.LEAD_REPLIED, A, A2, AG2, m.multipliedBy(300), reply(520000, 580000, false));
        String a1Instructed = envelope(EventTypes.INSTRUCTION_RECORDED, A, A1, AG1, Duration.ofDays(3),
                Map.of("leadRef", A1, "source", "fake-crm", "recordedAt", T0.plus(Duration.ofDays(3)).toString()));

        // the outcome topic first: A1's instruction arrives before anything else is known about A1
        send(Topics.OUTCOME_RECORDED, A, a1Instructed);
        send(Topics.OUTCOME_RECORDED, B, envelope(EventTypes.INSTRUCTION_RECORDED, B, B2, AG2, Duration.ofDays(2),
                Map.of("leadRef", B2, "source", "fake-crm", "recordedAt", T0.plus(Duration.ofDays(2)).toString())));

        List<String[]> lead = new ArrayList<>();   // {requestId, json} on lead.events.v1, in this (bad) order
        // request A
        lead.add(new String[]{A, envelope(EventTypes.VISIT_BOOKED, A, A1, AG1, Duration.ofDays(1), mapOf("visitAt", null))});
        lead.add(new String[]{A, envelope(EventTypes.SELLER_SELECTED, A, A1, AG1, Duration.ofHours(6), Map.of())});
        lead.add(new String[]{A, a2Reply});
        lead.add(new String[]{A, envelope(EventTypes.LEAD_EXPIRED, A, A3, AG3, Duration.ofHours(24), Map.of("replyDueAt", T0.plus(Duration.ofHours(24)).toString()))});
        lead.add(new String[]{A, envelope(EventTypes.LEAD_REPLIED, A, A1, AG1, m.multipliedBy(90), reply(500000, 540000, false))});
        lead.add(new String[]{A, envelope(EventTypes.LEAD_DELIVERED, A, A1, AG1, Duration.ZERO, Map.of("adapter", "ok", "attempt", 1))});
        lead.add(new String[]{A, envelope(EventTypes.EVIDENCE_ATTACHED, A, A1, AG1, m.multipliedBy(95), Map.of("packId", "ep_replay_a1",
                "comparablesCount", 4, "comparablesRange", Map.of("low", 495000, "mid", 520000, "high", 545000)))});
        lead.add(new String[]{A, envelope(EventTypes.SELLER_ENGAGED, A, A2, AG2, Duration.ofHours(5), Map.of("action", "VIEWED"))});
        lead.add(new String[]{A, envelope(EventTypes.LEAD_REPLIED, A, A3, AG3, Duration.ofHours(30), reply(480000, 500000, true))}); // late, after expiry
        lead.add(new String[]{A, a2Reply});   // the same event again (same eventId): at-least-once delivery
        lead.add(new String[]{A, envelope(EventTypes.LEAD_DELIVERED, A, A2, AG2, Duration.ZERO, Map.of("adapter", "slow", "attempt", 1))});
        lead.add(new String[]{A, envelope(EventTypes.SELLER_ENGAGED, A, A1, AG1, Duration.ofHours(5), Map.of("action", "CONTACTED"))});
        lead.add(new String[]{A, envelope(EventTypes.LEAD_DELIVERED, A, A3, AG3, Duration.ZERO, Map.of("adapter", "flaky", "attempt", 3))});
        // request B
        lead.add(new String[]{B, envelope(EventTypes.VISIT_BOOKED, B, B1, AG1, Duration.ofHours(20), mapOf("visitAt", T0.plus(Duration.ofDays(2)).toString()))});
        lead.add(new String[]{B, envelope(EventTypes.LEAD_REPLIED, B, B2, AG2, m.multipliedBy(120), reply(650000, 690000, false))});
        lead.add(new String[]{B, envelope(EventTypes.LEAD_DELIVERY_FAILED, B, B3, AG3, Duration.ofMinutes(2), Map.of("adapter", "flaky", "attempts", 3, "reason", "adapter 'flaky' failed on every attempt (CrmUnavailable)"))});
        lead.add(new String[]{B, envelope(EventTypes.LEAD_DELIVERED, B, B1, AG1, Duration.ZERO, Map.of("adapter", "ok", "attempt", 1))});
        lead.add(new String[]{B, envelope(EventTypes.LEAD_REPLIED, B, B1, AG1, m.multipliedBy(60), reply(700000, 760000, false))});
        lead.add(new String[]{B, envelope(EventTypes.LEAD_DELIVERED, B, B2, AG2, Duration.ZERO, Map.of("adapter", "slow", "attempt", 1))});
        for (String[] r : lead) {
            send(Topics.LEAD_EVENTS, r[0], r[1]);
        }

        rebuilder.awaitCaughtUp(Duration.ofSeconds(30));
        List<FactSnapshot> first = ourFacts();
        assertThat(first).hasSize(6);

        FactSnapshot a1 = fact(first, A1);
        assertThat(a1.deliveredAt()).isEqualTo(T0.toString());
        assertThat(a1.replyMinutes()).isEqualTo(90);
        assertThat(a1.withinDeadline()).isTrue();
        assertThat(a1.evidenceAttached()).isTrue();
        assertThat(a1.rangeRank()).isEqualTo("MIDDLE");   // midpoints: A2 550k, A1 520k, A3 490k
        assertThat(a1.sellerEngaged()).isTrue();
        assertThat(a1.sellerSelected()).isTrue();
        assertThat(a1.visitBooked()).isTrue();
        assertThat(a1.instructed()).isTrue();             // arrived first of all, still joined to the lead
        assertThat(a1.lastEventAt()).isEqualTo(T0.plus(Duration.ofDays(3)).toString());

        FactSnapshot a2 = fact(first, A2);
        assertThat(a2.replyMinutes()).isEqualTo(300);
        assertThat(a2.rangeRank()).isEqualTo("HIGHEST");
        assertThat(a2.withinDeadline()).isTrue();
        assertThat(a2.sellerEngaged()).isTrue();
        assertThat(a2.evidenceAttached()).isFalse();

        FactSnapshot a3 = fact(first, A3);
        assertThat(a3.replyMinutes()).isEqualTo(30 * 60);
        assertThat(a3.withinDeadline()).isFalse();        // expired, then replied late
        assertThat(a3.rangeRank()).isEqualTo("LOWEST");
        assertThat(a3.rangeLow()).isEqualTo(480000L);

        FactSnapshot b1 = fact(first, B1);
        assertThat(b1.rangeRank()).isEqualTo("HIGHEST");  // two replies: HIGHEST and LOWEST
        assertThat(b1.replyMinutes()).isEqualTo(60);
        assertThat(b1.visitBooked()).isTrue();            // VisitBooked arrived before LeadDelivered
        assertThat(b1.deliveredAt()).isEqualTo(T0.toString());

        FactSnapshot b2 = fact(first, B2);
        assertThat(b2.rangeRank()).isEqualTo("LOWEST");
        assertThat(b2.instructed()).isTrue();

        FactSnapshot b3 = fact(first, B3);                // delivery failed: one of the agent's leads, nothing else
        assertThat(b3.deliveredAt()).isNull();
        assertThat(b3.repliedAt()).isNull();
        assertThat(b3.withinDeadline()).isNull();
        assertThat(b3.rangeRank()).isNull();

        // a duplicate event changes nothing
        send(Topics.LEAD_EVENTS, A, a2Reply);
        send(Topics.OUTCOME_RECORDED, A, a1Instructed);
        rebuilder.awaitCaughtUp(Duration.ofSeconds(30));
        assertThat(ourFacts()).isEqualTo(first);

        // the agent sees its own numbers only: A1 + B1 for ag_fictional_01
        String card = mvc.perform(get("/agents/" + AG1 + "/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(AG1))
                .andExpect(jsonPath("$.leads").value(2))
                .andExpect(jsonPath("$.replied").value(2))
                .andExpect(jsonPath("$.medianReplyMinutes").value(75.0))
                .andExpect(jsonPath("$.withinDeadlineShare").value(1.0))
                .andExpect(jsonPath("$.evidenceAttachedShare").value(0.5))
                .andExpect(jsonPath("$.sellerEngaged").value(1))
                .andExpect(jsonPath("$.sellerSelected").value(1))
                .andExpect(jsonPath("$.visitsBooked").value(2))
                .andExpect(jsonPath("$.instructed").value(1))
                .andExpect(jsonPath("$.benchmark.label").value("fixture benchmark"))
                .andReturn().getResponse().getContentAsString();
        assertThat(card).doesNotContain(AG2).doesNotContain(AG3).doesNotContain("ld_");
        mvc.perform(get("/agents/ag_nobody/scorecard")).andExpect(status().isNotFound());

        // Claim 3: stop, truncate, reset offsets to earliest, start; the facts rebuild identical
        long rebuilt = rebuilder.rebuild(Duration.ofSeconds(60));
        assertThat(rebuilt).isGreaterThanOrEqualTo(6);
        List<FactSnapshot> replayed = ourFacts();
        assertThat(ScorecardRebuilder.diff(first, replayed)).isEmpty();
        assertThat(replayed).isEqualTo(first);

        // the snapshot endpoint the demo script diffs shows the same rows
        mvc.perform(get("/scorecard/snapshot")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    /** Map.of() refuses null values; VisitBooked's visitAt may be null. */
    static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

}
