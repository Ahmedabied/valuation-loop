package uk.valuationloop.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import uk.valuationloop.delivery.DeliveryIntegrationTestBase;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.request.RequestState;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/** The agent's reply API: 201 / 409 on a second reply / 404 for someone else's lead / 422 bad range / 423 contact. */
class ReplyApiTest extends DeliveryIntegrationTestBase {

    static final String AGENT = "ag_fictional_01";
    static final String OTHER_AGENT = "ag_fictional_05";

    @Autowired ValuationRequestRepository requests;
    @Autowired TransactionTemplate tx;

    ResultActions reply(String agentId, String leadId, String body) throws Exception {
        return mvc.perform(post("/agents/" + agentId + "/leads/" + leadId + "/reply")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String deliveredRequest(String sellerRef) throws Exception {
        String requestId = postRequest(sellerRef, "SW11 4DD", List.of(AGENT, OTHER_AGENT));
        awaitAllLeads(requestId, LeadState.DELIVERED);
        return requestId;
    }

    @Test
    void replyIs201WithAnEventAndMovesTheRequestToRepliesIn() throws Exception {
        String requestId = deliveredRequest("seller-reply-1");
        String leadId = leadOf(requestId, AGENT).getId();

        reply(AGENT, leadId, "{\"low\": 500000, \"high\": 540000, \"message\": \"two comparables on the same street\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leadId").value(leadId))
                .andExpect(jsonPath("$.state").value("REPLIED"))
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.low").value(500000))
                .andExpect(jsonPath("$.high").value(540000))
                .andExpect(jsonPath("$.repliedAt").isNotEmpty());

        List<JsonNode> events = outboxEvents(EventTypes.LEAD_REPLIED, leadId);
        assertThat(events).hasSize(1);
        EventSchemas.assertValid(events.get(0).toString());
        assertThat(events.get(0).at("/payload/late").asBoolean()).isFalse();
        assertThat(events.get(0).at("/payload/deliveredAt").asString()).isNotBlank();
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(RequestState.REPLIES_IN);

        // the second reply is a conflict, not a second fact
        reply(AGENT, leadId, "{\"low\": 1, \"high\": 2}").andExpect(status().isConflict());
        assertThat(outboxEvents(EventTypes.LEAD_REPLIED, leadId)).hasSize(1);

        // the other agent's reply keeps the request in REPLIES_IN
        String otherLead = leadOf(requestId, OTHER_AGENT).getId();
        reply(OTHER_AGENT, otherLead, "{\"low\": 520000, \"high\": 560000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(nullValue()));
        EventSchemas.assertValid(outboxEvents(EventTypes.LEAD_REPLIED, otherLead).get(0).toString());
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(RequestState.REPLIES_IN);
    }

    @Test
    void someoneElsesLeadIs404ForReplyAndContact() throws Exception {
        String requestId = deliveredRequest("seller-reply-2");
        String leadId = leadOf(requestId, AGENT).getId();

        reply(OTHER_AGENT, leadId, "{\"low\": 500000, \"high\": 540000}").andExpect(status().isNotFound());
        mvc.perform(get("/agents/" + OTHER_AGENT + "/leads/" + leadId + "/contact")).andExpect(status().isNotFound());
        reply(AGENT, "ld_does_not_exist", "{\"low\": 500000, \"high\": 540000}").andExpect(status().isNotFound());
        assertThat(leadOf(requestId, AGENT).getState()).isEqualTo(LeadState.DELIVERED);
    }

    @Test
    void contactIsLockedUntilTheAgentReplies() throws Exception {
        String requestId = deliveredRequest("seller-reply-3");
        String leadId = leadOf(requestId, AGENT).getId();

        mvc.perform(get("/agents/" + AGENT + "/leads/" + leadId + "/contact"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("contact is released after you reply"));

        reply(AGENT, leadId, "{\"low\": 500000, \"high\": 540000}").andExpect(status().isCreated());

        mvc.perform(get("/agents/" + AGENT + "/leads/" + leadId + "/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerRef").value("seller-reply-3"))
                .andExpect(jsonPath("$.contact.email").value("seller+seller-reply-3@example.invalid"))
                .andExpect(jsonPath("$.contact.phone").value(nullValue()));
    }

    @Test
    void badRangesAre422AndChangeNothing() throws Exception {
        String requestId = deliveredRequest("seller-reply-4");
        String leadId = leadOf(requestId, AGENT).getId();

        reply(AGENT, leadId, "{\"low\": 600000, \"high\": 500000}").andExpect(status().isUnprocessableContent());
        reply(AGENT, leadId, "{\"low\": 0, \"high\": 500000}").andExpect(status().isUnprocessableContent());
        reply(AGENT, leadId, "{\"low\": -5, \"high\": 500000}").andExpect(status().isUnprocessableContent());
        reply(AGENT, leadId, "{\"high\": 500000}").andExpect(status().isUnprocessableContent());
        reply(AGENT, leadId, "{\"low\": 500000.50, \"high\": 600000}").andExpect(status().isUnprocessableContent());

        assertThat(leadOf(requestId, AGENT).getState()).isEqualTo(LeadState.DELIVERED);
        assertThat(outboxEvents(EventTypes.LEAD_REPLIED, leadId)).isEmpty();
    }

    @Test
    void aLeadThatWasNeverDeliveredCannotTakeAReply() throws Exception {
        // a CREATED lead (delivery not yet run): write it directly so the listener never touches it
        AgentLead created = tx.execute(status -> {
            var now = clock.instant();
            var r = uk.valuationloop.request.ValuationRequest.receive("seller-reply-5", "SW11 5EE",
                    uk.valuationloop.request.PropertyType.FLAT, 1, null, now);
            requests.save(r);
            return leads.save(AgentLead.create(r.getId(), AGENT, now));
        });
        reply(AGENT, created.getId(), "{\"low\": 500000, \"high\": 540000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CREATED")));
    }

    @Test
    void anAgentListsItsOwnLeadsOnly() throws Exception {
        deliveredRequest("seller-reply-6");
        mvc.perform(get("/agents/" + AGENT + "/leads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[*].agentId", everyItem(is(AGENT))))
                .andExpect(jsonPath("$[0].state").isNotEmpty())
                // the fields are always present, null until they happen (jsonPath exists() treats null as absent)
                .andExpect(jsonPath("$[0]", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.hasKey("deliveredAt"), org.hamcrest.Matchers.hasKey("replyDueAt"),
                        org.hamcrest.Matchers.hasKey("repliedAt"), org.hamcrest.Matchers.hasKey("late"))));
        mvc.perform(get("/agents/ag_fictional_99/leads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
