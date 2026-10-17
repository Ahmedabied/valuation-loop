package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.request.RequestState;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/** Reply window closes -> LeadExpired; every lead terminal -> RequestClosed; a late reply is still accepted. */
class DeadlineScannerTest extends DeliveryIntegrationTestBase {

    @Autowired DeadlineScanner scanner;
    @Autowired ValuationRequestRepository requests;

    @Test
    void leadsExpireAfterTheWindowAndTheRequestClosesWhenAllAreTerminal() throws Exception {
        String requestId = postRequest("seller-deadline-1", "SW11 3CC", List.of("ag_fictional_01", "ag_fictional_05"));
        awaitAllLeads(requestId, LeadState.DELIVERED);

        // inside the window: nothing expires
        scanner.scanOnce();
        assertThat(leads.findByRequestIdOrderByCreatedAt(requestId))
                .allSatisfy(l -> assertThat(l.getState()).isEqualTo(LeadState.DELIVERED));

        clock.advance(Duration.ofHours(25));
        DeadlineScanner.ScanResult result = scanner.scanOnce();
        assertThat(result.expired()).isGreaterThanOrEqualTo(2);   // earlier tests' delivered leads may expire too

        for (AgentLead lead : leads.findByRequestIdOrderByCreatedAt(requestId)) {
            assertThat(lead.getState()).isEqualTo(LeadState.EXPIRED);
            List<JsonNode> expired = outboxEvents(EventTypes.LEAD_EXPIRED, lead.getId());
            assertThat(expired).hasSize(1);
            EventSchemas.assertValid(expired.get(0).toString());
            assertThat(expired.get(0).at("/payload/replyDueAt").asText()).isNotBlank();
        }

        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(RequestState.CLOSED);
        List<String> closed = outboxEventsForRequest(EventTypes.REQUEST_CLOSED, requestId);
        assertThat(closed).hasSize(1);
        EventSchemas.assertValid(closed.get(0));
        assertThat(readTree(closed.get(0)).at("/payload/reason").asText()).isEqualTo("ALL_LEADS_TERMINAL");

        // a second scan finds nothing new and does not close the request twice
        scanner.scanOnce();
        assertThat(outboxEventsForRequest(EventTypes.REQUEST_CLOSED, requestId)).hasSize(1);

        // the agent replies a day late: accepted, recorded as late, the request stays closed
        String leadId = leadOf(requestId, "ag_fictional_01").getId();
        mvc.perform(post("/agents/ag_fictional_01/leads/" + leadId + "/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"low\": 610000, \"high\": 650000, \"message\": \"sorry for the delay\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REPLIED"))
                .andExpect(jsonPath("$.late").value(true));
        List<JsonNode> replied = outboxEvents(EventTypes.LEAD_REPLIED, leadId);
        assertThat(replied).hasSize(1);
        EventSchemas.assertValid(replied.get(0).toString());
        assertThat(replied.get(0).at("/payload/late").asBoolean()).isTrue();
        assertThat(requests.findById(requestId).orElseThrow().getState()).isEqualTo(RequestState.CLOSED);
    }
}
