package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import uk.valuationloop.comparison.LoopApiTestBase;

/**
 * Claim 3: out-of-order and late events still join to the lead (the Land Registry completion included: for A1 it
 * arrives before the instruction and before the reply, for B2 after both), a duplicate changes nothing, and a replay
 * (truncate, reset offsets, restart) rebuilds identical facts.
 *
 * The test plays the role of every producer: it writes the schema-valid envelopes of {@link ScorecardScript} straight
 * onto lead.events.v1 and outcome.recorded.v1 in a deliberately bad order, for 2 requests x 3 agents, and lets the real
 * listener consume them. RandomOrderProjectionTest projects the same log in 20 shuffled orders.
 */
class ScorecardReplayTest extends LoopApiTestBase {

    static final String A = ScorecardScript.A, B = ScorecardScript.B;
    static final String AG1 = ScorecardScript.AG1, AG2 = ScorecardScript.AG2, AG3 = ScorecardScript.AG3;
    static final String A1 = ScorecardScript.A1, A2 = ScorecardScript.A2, A3 = ScorecardScript.A3;
    static final String B1 = ScorecardScript.B1, B2 = ScorecardScript.B2, B3 = ScorecardScript.B3;
    static final Instant T0 = ScorecardScript.T0;

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ScorecardRebuilder rebuilder;
    @Autowired ScorecardFactRepository facts;

    void send(ScorecardScript.Scripted r) throws Exception {
        kafka.send(r.topic(), r.key(), r.json()).get(10, TimeUnit.SECONDS);
    }

    List<FactSnapshot> ourFacts() {
        return facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of)
                .filter(f -> ScorecardScript.LEADS.contains(f.leadId())).toList();
    }

    FactSnapshot fact(List<FactSnapshot> all, String leadId) {
        return all.stream().filter(f -> f.leadId().equals(leadId)).findFirst().orElseThrow();
    }

    @Test
    void outOfOrderLateAndDuplicateEventsProjectDeterministicallyAndReplayRebuildsIdenticalFacts() throws Exception {
        ScorecardScript script = new ScorecardScript();
        for (ScorecardScript.Scripted r : script.log()) {   // outcome records first, then lead.events.v1, in a bad order
            send(r);
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
        assertThat(a1.completedPrice()).isEqualTo(531_000L);   // arrived before the reply: the gap was derived later
        assertThat(a1.completedAt()).isEqualTo("2026-11-06");
        assertThat(a1.priceGapPct()).isEqualTo("2.12");   // (531k - 520k) / 520k = +2.115 %
        assertThat(a1.lastEventAt()).isEqualTo(T0.plus(Duration.ofDays(40)).toString());

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
        assertThat(b2.completedPrice()).isEqualTo(640_000L);
        assertThat(b2.priceGapPct()).isEqualTo("-4.48");  // (640k - 670k) / 670k = -4.478 %

        assertThat(a2.completedPrice()).isNull();         // no completion: no gap
        assertThat(a2.priceGapPct()).isNull();

        FactSnapshot b3 = fact(first, B3);                // delivery failed: one of the agent's leads, nothing else
        assertThat(b3.deliveredAt()).isNull();
        assertThat(b3.repliedAt()).isNull();
        assertThat(b3.withinDeadline()).isNull();
        assertThat(b3.rangeRank()).isNull();

        // a duplicate event changes nothing
        for (ScorecardScript.Scripted r : script.duplicates()) {
            send(r);
        }
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
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.medianPriceGapPct").value(2.12))
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
}
