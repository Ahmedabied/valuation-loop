package uk.valuationloop.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.RequestState;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/** The seller's comparison page, engagement and selection (ARCHITECTURE.md §8). */
class ComparisonViewTest extends LoopApiTestBase {

    static final String FIRST = "ag_fictional_01";
    static final String FAILS = "ag_fictional_03";
    static final String SECOND = "ag_fictional_05";

    @Autowired ValuationRequestRepository requests;

    ResultActions engage(String requestId, String leadId, String action) throws Exception {
        return mvc.perform(post("/valuation-requests/" + requestId + "/engagement").contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadId\": \"" + leadId + "\", \"action\": \"" + action + "\"}"));
    }

    ResultActions select(String requestId, String leadId) throws Exception {
        return mvc.perform(post("/valuation-requests/" + requestId + "/selection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"leadId\": \"" + leadId + "\"}"));
    }

    void insertPack(String packId, String leadId, String requestId, String agentId, boolean attached) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into evidence_pack (id, lead_id, request_id, agent_id, pack_json, comparables_count,
                    comparables_low, comparables_mid, comparables_high, data_vintage, attached_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, 2, 500000, 520000, 540000, 'PPD 2026-08', ?, ?, ?)
                """, packId, leadId, requestId, agentId,
                "{\"packId\":\"" + packId + "\",\"comparables\":[],\"comparablesRange\":{\"low\":500000,\"mid\":520000,\"high\":540000}}",
                attached ? now : null, now, now);
    }

    @Test
    void repliesInReplyOrderWithAttachedEvidenceThenEngagementAndSelection() throws Exception {
        String requestId = postRequest("seller-compare-1", List.of(FIRST, FAILS, SECOND));
        String firstLead = leadIdOf(requestId, FIRST);
        String failedLead = leadIdOf(requestId, FAILS);
        String secondLead = leadIdOf(requestId, SECOND);
        markDelivered(firstLead);
        markDelivered(secondLead);
        markFailed(failedLead);

        // SECOND replies before FIRST: reply order, not creation order, is the page order
        reply(SECOND, secondLead, 520000, 560000).andExpect(status().isCreated());
        Thread.sleep(5);
        reply(FIRST, firstLead, 500000, 540000).andExpect(status().isCreated());
        insertPack("ep_compare_attached", firstLead, requestId, FIRST, true);
        insertPack("ep_compare_draft", secondLead, requestId, SECOND, false);   // built but never attached: not shown

        // a lead with no reply cannot be engaged with or selected
        engage(requestId, failedLead, "VIEWED").andExpect(status().isConflict());
        select(requestId, failedLead).andExpect(status().isConflict());

        String body = mvc.perform(get("/valuation-requests/" + requestId + "/comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.state").value("REPLIES_IN"))
                .andExpect(jsonPath("$.disclaimer").value(ComparisonQuery.DISCLAIMER))
                .andExpect(jsonPath("$.replies.length()").value(2))
                .andExpect(jsonPath("$.replies[0].leadId").value(secondLead))
                .andExpect(jsonPath("$.replies[0].agentName").isNotEmpty())
                .andExpect(jsonPath("$.replies[0].range.low").value(520000))
                .andExpect(jsonPath("$.replies[0].range.high").value(560000))
                .andExpect(jsonPath("$.replies[0].evidence").isEmpty())
                .andExpect(jsonPath("$.replies[1].leadId").value(firstLead))
                .andExpect(jsonPath("$.replies[1].message").value("comparables attached"))
                .andExpect(jsonPath("$.replies[1].repliedAt").isNotEmpty())
                .andExpect(jsonPath("$.replies[1].evidence.packId").value("ep_compare_attached"))
                .andExpect(jsonPath("$.replies[1].evidence.comparablesRange.mid").value(520000))
                .andReturn().getResponse().getContentAsString();
        JsonNode page = tree(body);
        assertThat(page.path("replies").get(0).has("evidence")).isTrue();
        assertThat(page.path("replies").get(0).get("evidence").isNull()).isTrue();
        // nothing about the lead that failed: not listed, not counted, no word for it
        assertThat(body).doesNotContain(failedLead).doesNotContain(FAILS)
                .doesNotContainIgnoringCase("fail").doesNotContainIgnoringCase("expire")
                .doesNotContainIgnoringCase("late").doesNotContainIgnoringCase("overdue")
                .doesNotContainIgnoringCase("score");

        // engagement: 202 and a schema-valid SellerEngaged keyed by requestId, with leadId + agentId
        engage(requestId, firstLead, "CONTACTED").andExpect(status().isAccepted());
        List<OutboxEvent> engaged = outboxRows(EventTypes.SELLER_ENGAGED, requestId);
        assertThat(engaged).hasSize(1);
        assertThat(engaged.get(0).getTopic()).isEqualTo(Topics.LEAD_EVENTS);
        EventSchemas.assertValid(engaged.get(0).getPayload());
        JsonNode engagedEnvelope = tree(engaged.get(0).getPayload());
        assertThat(engagedEnvelope.path("leadId").asText()).isEqualTo(firstLead);
        assertThat(engagedEnvelope.path("agentId").asText()).isEqualTo(FIRST);
        assertThat(engagedEnvelope.at("/payload/action").asText()).isEqualTo("CONTACTED");
        engage(requestId, "ld_not_in_this_request", "VIEWED").andExpect(status().isNotFound());

        // selection closes the request: SellerSelected + RequestClosed {SELLER_SELECTED}, both schema-valid
        select(requestId, firstLead)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.selectedLeadId").value(firstLead));
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(RequestState.CLOSED);
        List<OutboxEvent> selected = outboxRows(EventTypes.SELLER_SELECTED, requestId);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getTopic()).isEqualTo(Topics.LEAD_EVENTS);
        EventSchemas.assertValid(selected.get(0).getPayload());
        assertThat(tree(selected.get(0).getPayload()).path("leadId").asText()).isEqualTo(firstLead);
        List<OutboxEvent> closed = outboxRows(EventTypes.REQUEST_CLOSED, requestId);
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getTopic()).isEqualTo(Topics.VALUATION_REQUESTED);
        EventSchemas.assertValid(closed.get(0).getPayload());
        assertThat(tree(closed.get(0).getPayload()).at("/payload/reason").asText()).isEqualTo("SELLER_SELECTED");

        // a second selection is a conflict and writes nothing
        select(requestId, secondLead).andExpect(status().isConflict());
        assertThat(outboxRows(EventTypes.SELLER_SELECTED, requestId)).hasSize(1);
        assertThat(outboxRows(EventTypes.REQUEST_CLOSED, requestId)).hasSize(1);
    }

    @Test
    void unknownRequestIs404() throws Exception {
        mvc.perform(get("/valuation-requests/vr_does_not_exist/comparison")).andExpect(status().isNotFound());
        engage("vr_does_not_exist", "ld_x", "VIEWED").andExpect(status().isNotFound());
        select("vr_does_not_exist", "ld_x").andExpect(status().isNotFound());
    }
}
