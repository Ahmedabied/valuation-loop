package uk.valuationloop.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uk.valuationloop.comparison.LoopApiTestBase;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.support.EventSchemas;

/** Visit booking (agent API) and the CRM webhook that carries leadRef (H2-review E2-6). */
class OutcomeApiTest extends LoopApiTestBase {

    static final String AGENT = "ag_fictional_01";
    static final String OTHER = "ag_fictional_05";

    ResultActions visit(String agentId, String leadId, String body) throws Exception {
        var req = post("/agents/" + agentId + "/leads/" + leadId + "/visit");
        return mvc.perform(body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions webhook(String leadRef) throws Exception {
        return mvc.perform(post("/outcomes/webhook").contentType(MediaType.APPLICATION_JSON).content(
                "{\"leadRef\": \"" + leadRef + "\", \"source\": \"fake-crm\", \"recordedAt\": \"2026-10-20T10:15:00Z\"}"));
    }

    @Test
    void visitIs200OnceThen409() throws Exception {
        String requestId = postRequest("seller-outcome-1", List.of(AGENT, OTHER));
        String lead = leadIdOf(requestId, AGENT);
        String unreplied = leadIdOf(requestId, OTHER);
        markDelivered(lead);
        markDelivered(unreplied);
        reply(AGENT, lead, 600000, 640000).andExpect(status().isCreated());

        visit(OTHER, lead, "{}").andExpect(status().isNotFound());              // not their lead
        visit(OTHER, unreplied, null).andExpect(status().isConflict());         // no reply yet

        visit(AGENT, lead, "{\"visitAt\": \"2026-10-22T09:30:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VISIT_BOOKED"));
        visit(AGENT, lead, null).andExpect(status().isConflict());              // second booking

        List<OutboxEvent> booked = outboxRows(EventTypes.VISIT_BOOKED, requestId);
        assertThat(booked).hasSize(1);
        assertThat(booked.get(0).getTopic()).isEqualTo(Topics.LEAD_EVENTS);
        EventSchemas.assertValid(booked.get(0).getPayload());
        assertThat(tree(booked.get(0).getPayload()).at("/payload/visitAt").asText()).isEqualTo("2026-10-22T09:30:00Z");
        assertThat(leads.findById(lead).orElseThrow().getState()).isEqualTo(LeadState.VISIT_BOOKED);
    }

    @Test
    void visitWithoutABodyHasANullVisitAt() throws Exception {
        String requestId = postRequest("seller-outcome-2", List.of(AGENT));
        String lead = leadIdOf(requestId, AGENT);
        markDelivered(lead);
        reply(AGENT, lead, 600000, 640000).andExpect(status().isCreated());

        visit(AGENT, lead, null).andExpect(status().isOk());
        List<OutboxEvent> booked = outboxRows(EventTypes.VISIT_BOOKED, requestId);
        EventSchemas.assertValid(booked.get(0).getPayload());
        assertThat(tree(booked.get(0).getPayload()).at("/payload/visitAt").isNull()).isTrue();
    }

    @Test
    void webhookIs202ThenIdempotent200AndUnknownLeadRefIs404() throws Exception {
        String requestId = postRequest("seller-outcome-3", List.of(AGENT));
        String lead = leadIdOf(requestId, AGENT);
        markDelivered(lead);
        reply(AGENT, lead, 600000, 640000).andExpect(status().isCreated());

        webhook(lead).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RECORDED"));
        webhook(lead).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_RECORDED"));
        webhook("ld_unknown_lead_ref").andExpect(status().isNotFound());

        List<OutboxEvent> recorded = outboxRows(EventTypes.INSTRUCTION_RECORDED, requestId);
        assertThat(recorded).hasSize(1);   // the repeat wrote nothing
        assertThat(recorded.get(0).getTopic()).isEqualTo(Topics.OUTCOME_RECORDED);
        EventSchemas.assertValid(recorded.get(0).getPayload());
        JsonNode envelope = tree(recorded.get(0).getPayload());
        assertThat(envelope.path("leadId").asText()).isEqualTo(lead);
        assertThat(envelope.path("agentId").asText()).isEqualTo(AGENT);
        assertThat(envelope.at("/payload/leadRef").asText()).isEqualTo(lead);
        assertThat(envelope.at("/payload/source").asText()).isEqualTo("fake-crm");
        assertThat(envelope.at("/payload/recordedAt").asText()).isEqualTo("2026-10-20T10:15:00Z");
        assertThat(leads.findById(lead).orElseThrow().getInstructedAt()).isNotNull();
    }

    @Test
    void webhookBeforeAReplyIsStillRecorded() throws Exception {
        String requestId = postRequest("seller-outcome-4", List.of(AGENT));
        String lead = leadIdOf(requestId, AGENT);
        markDelivered(lead);

        webhook(lead).andExpect(status().isAccepted());
        EventSchemas.assertValid(outboxRows(EventTypes.INSTRUCTION_RECORDED, requestId).get(0).getPayload());
        assertThat(leads.findById(lead).orElseThrow().getState()).isEqualTo(LeadState.DELIVERED);   // a fact, not a state
    }
}
