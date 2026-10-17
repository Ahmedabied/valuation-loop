package uk.valuationloop.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.OutboxRelay;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.support.EventSchemas;

/** POST a request -> one lead per slot holder -> one DeliverLead command per lead on the wire, keyed by leadId. */
@SpringBootTest(properties = // its own in-memory database, never shared with another test context
        "spring.datasource.url=jdbc:h2:mem:request_flow_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 3, topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER})
class ValuationRequestFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay.OutboxRelayBatch relay;
    @Autowired AgentLeadRepository leads;
    @Autowired EmbeddedKafkaBroker broker;

    Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("flow-" + System.nanoTime(), "false", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void sw11RequestFansOutToEverySlotHolderAndNothingElse() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sellerRef", "seller-test-1", "postcode", "sw11 1aa", "propertyType", "FLAT", "bedrooms", 2, "floorAreaM2", 68.5));

        String response = mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("AWAITING_REPLIES"))
                .andExpect(jsonPath("$.postcode").value("SW11 1AA"))
                .andExpect(jsonPath("$.outcode").value("SW11"))
                .andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(response);
        String requestId = view.get("requestId").asText();

        // four fixture agents hold SW11: 01, 02, 03, 05
        assertThat(view.get("leads")).hasSize(4);
        assertThat(leads.findByRequestIdOrderByCreatedAt(requestId)).hasSize(4)
                .allSatisfy(l -> assertThat(l.getState().name()).isEqualTo("CREATED"));

        // outbox: ValuationRequested + AgentsMatched + 4 DeliverLead, all committed with the request
        assertThat(outbox.count()).isEqualTo(6);
        assertThat(relay.relayOnce()).isEqualTo(6);

        Set<String> deliverKeys = new HashSet<>();
        int requestEvents = 0;
        for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))) {
            JsonNode env = json.readTree(r.value());
            EventSchemas.assertValid(r.value()); // the already-emitted events must match the shared contract
            if (r.topic().equals(Topics.LEAD_DELIVER)) {
                assertThat(env.get("eventType").asText()).isEqualTo("DeliverLead");
                assertThat(r.key()).isEqualTo(env.get("leadId").asText());
                deliverKeys.add(r.key());
            } else {
                assertThat(r.key()).isEqualTo(requestId);
                requestEvents++;
            }
        }
        assertThat(deliverKeys).hasSize(4);
        assertThat(requestEvents).isEqualTo(2);

        mvc.perform(get("/valuation-requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leads.length()").value(4));
    }

    @Test
    void sellerSelectedAgentsOverrideTheSlotLookup() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sellerRef", "seller-test-2", "postcode", "SW11 6ED", "propertyType", "HOUSE", "bedrooms", 3,
                "selectedAgentIds", java.util.List.of("ag_fictional_01", "ag_fictional_04")));
        mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leads.length()").value(2))
                .andExpect(jsonPath("$.leads[1].agentId").value("ag_fictional_04"));
    }

    @Test
    void outcodeWithNoSlotHolderIs422AndWritesNothing() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sellerRef", "seller-test-3", "postcode", "M20 4AP", "propertyType", "FLAT", "bedrooms", 1));
        mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertThat(outbox.count()).isZero();   // the whole transaction rolled back, including ValuationRequested
    }

    @Test
    void badPostcodeIs422() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sellerRef", "s", "postcode", "<b>not a postcode</b>", "propertyType", "FLAT", "bedrooms", 1));
        mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.error").value("invalid"))
                .andExpect(jsonPath("$.message").value("not a UK postcode"));   // the input is never echoed back
    }

    @Test
    void invalidBodyIs422WithTheFieldNamesAndUnknownRequestIs404() throws Exception {
        mvc.perform(post("/valuation-requests").contentType(MediaType.APPLICATION_JSON).content("{\"bedrooms\": 2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("postcode")));
        mvc.perform(get("/valuation-requests/vr_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("no request vr_does_not_exist"));
    }
}
