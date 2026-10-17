package uk.valuationloop.comparison;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;

/**
 * One Spring context for the comparison, outcome and scorecard tests (same annotations = Spring reuses it).
 *
 * Its own H2 database, so no other test class's relay or listener ever touches these rows. The outbox relay is off
 * (test application.yml) so emitted events stay in the outbox where a test can read them; the delivery listener and
 * the deadline scanner are off, and tests move leads to DELIVERED through the repository instead. The scorecard
 * listener is ON: the replay test feeds it by producing envelopes straight onto its topics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 2,
        topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER, Topics.LEAD_EVENTS, Topics.OUTCOME_RECORDED})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:loop_api_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.delivery.listener.auto-startup=false",
        "valuationloop.delivery.deadline-scanner.enabled=false",
        "valuationloop.scorecard.listener.auto-startup=true"})
public abstract class LoopApiTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AgentLeadRepository leads;
    @Autowired protected OutboxRepository outbox;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Clock clock;

    /** POST /valuation-requests for the given agents; returns the requestId. */
    protected String postRequest(String sellerRef, List<String> agentIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerRef", sellerRef);
        body.put("postcode", "SW11 1AA");
        body.put("propertyType", "FLAT");
        body.put("bedrooms", 2);
        body.put("selectedAgentIds", agentIds);
        String response = mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("requestId").asText();
    }

    /** Drives the lead CREATED -> DELIVERING -> DELIVERED, as the delivery worker would after an ok adapter call. */
    protected void markDelivered(String leadId) {
        tx.executeWithoutResult(s -> {
            AgentLead lead = leads.findById(leadId).orElseThrow();   // managed: JPA writes the changes at commit
            lead.startDelivery(clock.instant());
            lead.delivered(clock.instant(), Duration.ofHours(24));
        });
    }

    /** Drives the lead CREATED -> DELIVERING -> DELIVERY_FAILED, as the DLT handler would. */
    protected void markFailed(String leadId) {
        tx.executeWithoutResult(s -> {
            AgentLead lead = leads.findById(leadId).orElseThrow();
            lead.startDelivery(clock.instant());
            lead.failDelivery(clock.instant());
        });
    }

    protected ResultActions reply(String agentId, String leadId, long low, long high) throws Exception {
        return mvc.perform(post("/agents/" + agentId + "/leads/" + leadId + "/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"low\": " + low + ", \"high\": " + high + ", \"message\": \"comparables attached\"}"));
    }

    protected String leadIdOf(String requestId, String agentId) {
        return leads.findByRequestIdAndAgentId(requestId, agentId).orElseThrow().getId();
    }

    /** Outbox rows of one event type for one request, oldest first. */
    protected List<OutboxEvent> outboxRows(String eventType, String requestId) {
        return outbox.findAll().stream()
                .filter(r -> r.getEventType().equals(eventType) && r.getPartitionKey().equals(requestId))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    protected JsonNode tree(String s) throws Exception {
        return json.readTree(s);
    }
}
