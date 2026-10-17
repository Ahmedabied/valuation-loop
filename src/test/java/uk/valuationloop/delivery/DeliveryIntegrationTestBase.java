package uk.valuationloop.delivery;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;

/**
 * One Spring context for every delivery/response integration test (same annotations = Spring reuses the context,
 * so the broker and the app start once). Like a Django TestCase base class with shared fixtures.
 *
 * The whole loop runs for real here: the outbox relay is ON, the delivery listener consumes lead.deliver.v1, and
 * spring-kafka creates the retry and DLT topics itself (they are deliberately not pre-created below, so the topic
 * names the tests assert are the ones spring-kafka produced). Flaky always fails (99) so it reaches the DLT; slow
 * is 50 ms; the deadline scanner is driven by hand with a movable clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 1,
        topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER, Topics.LEAD_EVENTS})
@TestPropertySource(properties = {
        // its own in-memory database: this context's relay and listener keep running while Spring caches the
        // context, and must never touch the rows of another test class's context (they share one JVM)
        "spring.datasource.url=jdbc:h2:mem:delivery_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.outbox.enabled=true",
        "valuationloop.delivery.listener.auto-startup=true",
        "valuationloop.delivery.flaky-failures=99",
        "valuationloop.delivery.slow-ms=50",
        "valuationloop.delivery.deadline-scanner.enabled=false"})
@Import(MutableClock.Config.class)
public abstract class DeliveryIntegrationTestBase {

    protected static final Duration AWAIT = Duration.ofSeconds(30);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected EmbeddedKafkaBroker broker;
    @Autowired protected AgentLeadRepository leads;
    @Autowired protected OutboxRepository outbox;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeCrm crm;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** POST /valuation-requests; returns the requestId. selectedAgentIds may be empty (then: every slot holder). */
    protected String postRequest(String sellerRef, String postcode, List<String> selectedAgentIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerRef", sellerRef);
        body.put("postcode", postcode);
        body.put("propertyType", "FLAT");
        body.put("bedrooms", 2);
        if (!selectedAgentIds.isEmpty()) {
            body.put("selectedAgentIds", selectedAgentIds);
        }
        String response = mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("requestId").asText();
    }

    protected AgentLead leadOf(String requestId, String agentId) {
        return leads.findByRequestIdAndAgentId(requestId, agentId).orElseThrow();
    }

    /** Waits until every lead of the request is in the given state. */
    protected void awaitAllLeads(String requestId, LeadState state) {
        await().atMost(AWAIT).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(leads.findByRequestIdOrderByCreatedAt(requestId))
                        .isNotEmpty()
                        .allSatisfy(l -> org.assertj.core.api.Assertions.assertThat(l.getState()).isEqualTo(state)));
    }

    /** Outbox rows (sent or not) of one event type for one lead, as the JSON envelopes that go on the wire. */
    protected List<JsonNode> outboxEvents(String eventType, String leadId) {
        return outbox.findAll().stream()
                .filter(r -> r.getEventType().equals(eventType))
                .map(OutboxEvent::getPayload)
                .map(this::readTree)
                .filter(env -> leadId.equals(env.path("leadId").asText(null)))
                .toList();
    }

    /** Outbox rows of one event type for one request. */
    protected List<String> outboxEventsForRequest(String eventType, String requestId) {
        return outbox.findAll().stream()
                .filter(r -> r.getEventType().equals(eventType) && r.getPartitionKey().equals(requestId))
                .map(OutboxEvent::getPayload)
                .toList();
    }

    protected JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + s, e);
        }
    }

    protected static boolean hasLeadId(ConsumerRecord<String, String> r, String leadId) {
        return r.value() != null && r.value().contains("\"leadId\":\"" + leadId + "\"");
    }

    protected static boolean hasRequestId(ConsumerRecord<String, String> r, String requestId) {
        return r.value() != null && r.value().contains("\"requestId\":\"" + requestId + "\"");
    }
}
