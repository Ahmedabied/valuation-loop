package uk.valuationloop.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.valuationloop.evidence.FakeEvidenceSources.sale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/**
 * The agent's evidence API over HTTP (MockMvc), H2 for the database, in-memory fakes instead of Elasticsearch.
 * build -> exclude -> attach, plus every way attach and curation must refuse.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        // its own in-memory database, never shared with another test context
        "spring.datasource.url=jdbc:h2:mem:evidence_api_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Import(EvidenceApiTest.FakeSources.class)
class EvidenceApiTest {

    /** @Primary: when two beans fit (the ES one and the fake), Spring injects this one. */
    @TestConfiguration
    static class FakeSources {
        @Bean @Primary FakeEvidenceSources.Search fakeSearch() { return new FakeEvidenceSources.Search(); }
        @Bean @Primary FakeEvidenceSources.Centroids fakeCentroids() { return new FakeEvidenceSources.Centroids(); }
    }

    private static final String AGENT = "ag_fictional_01";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakeEvidenceSources.Search search;
    @Autowired ValuationRequestRepository requests;
    @Autowired AgentLeadRepository leads;
    @Autowired EvidencePackRepository packs;
    @Autowired OutboxRepository outbox;

    @BeforeEach
    void comparables() {
        search.results.clear();
        search.results.addAll(List.of(
                sale("T-1", 500_000, 68.0, 100), sale("T-2", 600_000, null, 200), sale("T-3", 700_000, 70.0, 300),
                sale("T-4", 800_000, 66.0, 400), sale("T-5", 2_500_000, 69.0, 500)));
    }

    /** A lead for AGENT on a fresh request, delivered, and replied to when {@code replied} is true. */
    private AgentLead lead(boolean replied) {
        Instant now = Instant.now();
        ValuationRequest r = requests.save(ValuationRequest.receive("seller-evidence-test", "ZZ1 1ZZ",
                PropertyType.FLAT, 2, new BigDecimal("68.0"), now));
        AgentLead lead = AgentLead.create(r.getId(), AGENT, now);
        lead.startDelivery(now);
        lead.delivered(now, Duration.ofHours(24));
        if (replied) {
            lead.reply(new BigDecimal("640000"), new BigDecimal("690000"), "happy to visit", now);
        }
        return leads.save(lead);
    }

    private String base(String agentId, AgentLead lead) {
        return "/agents/" + agentId + "/leads/" + lead.getId() + "/evidence";
    }

    private String buildPack(AgentLead lead) throws Exception {
        String body = mvc.perform(post(base(AGENT, lead))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("packId").asText();
    }

    @Test
    void buildExcludeAttachHappyPath() throws Exception {
        AgentLead lead = lead(true);

        String created = mvc.perform(post(base(AGENT, lead)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pack.comparables.length()").value(5))
                .andExpect(jsonPath("$.pack.comparablesRange.mid").value(700_000))
                .andExpect(jsonPath("$.pack.method").value("lower quartile / median / upper quartile of attached comparables"))
                .andExpect(jsonPath("$.pack.provenance.attributions.length()").value(3))
                .andExpect(jsonPath("$.pack.provenance.index").value("comparables-v1"))
                .andExpect(jsonPath("$.pack.comparables[1].floorAreaM2").doesNotExist())
                .andExpect(jsonPath("$.attachedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String packId = json.readTree(created).get("packId").asText();
        assertThat(packId).startsWith("ep_");

        // the agent drops the 2.5m outlier: range from the remaining four, exclusion recorded privately
        mvc.perform(delete(base(AGENT, lead) + "/" + packId + "/comparables/T-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pack.comparables.length()").value(4))
                .andExpect(jsonPath("$.pack.comparablesRange.low").value(575_000))
                .andExpect(jsonPath("$.pack.comparablesRange.mid").value(650_000))
                .andExpect(jsonPath("$.pack.comparablesRange.high").value(725_000))
                .andExpect(jsonPath("$.excludedPpdTransactionIds[0]").value("T-5"));

        EvidencePack row = packs.findById(packId).orElseThrow();
        assertThat(row.getPackJson()).doesNotContain("T-5");          // what the seller will see has no trace of it
        assertThat(row.getComparablesCount()).isEqualTo(4);
        assertThat(row.getComparablesMid()).isEqualByComparingTo("650000");

        mvc.perform(get(base(AGENT, lead) + "/" + packId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pack.comparables.length()").value(4))
                .andExpect(jsonPath("$.excludedPpdTransactionIds.length()").value(1));

        mvc.perform(post(base(AGENT, lead) + "/" + packId + "/attach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachedAt").exists());

        AgentLead after = leads.findById(lead.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(LeadState.EVIDENCED);
        assertThat(after.getEvidencePackId()).isEqualTo(packId);

        OutboxEvent event = outbox.findAll().stream()
                .filter(e -> e.getEventType().equals("EvidenceAttached") && e.getPayload().contains(lead.getId()))
                .findFirst().orElseThrow();
        assertThat(event.getTopic()).isEqualTo(Topics.LEAD_EVENTS);
        assertThat(event.getPartitionKey()).isEqualTo(lead.getRequestId());
        EventSchemas.assertValid(event.getPayload());
        JsonNode payload = json.readTree(event.getPayload()).get("payload");
        assertThat(payload.get("packId").asText()).isEqualTo(packId);
        assertThat(payload.get("comparablesCount").asInt()).isEqualTo(4);
        assertThat(payload.at("/comparablesRange/mid").asLong()).isEqualTo(650_000);
    }

    @Test
    void attachBeforeTheReplyIs409() throws Exception {
        AgentLead lead = lead(false);
        String packId = buildPack(lead);
        mvc.perform(post(base(AGENT, lead) + "/" + packId + "/attach")).andExpect(status().isConflict());
        assertThat(leads.findById(lead.getId()).orElseThrow().getState()).isEqualTo(LeadState.DELIVERED);
        assertThat(packs.findById(packId).orElseThrow().getAttachedAt()).isNull();
    }

    @Test
    void secondAttachIs409EvenWithAnotherPack() throws Exception {
        AgentLead lead = lead(true);
        String first = buildPack(lead);
        String second = buildPack(lead);
        mvc.perform(post(base(AGENT, lead) + "/" + first + "/attach")).andExpect(status().isOk());
        mvc.perform(post(base(AGENT, lead) + "/" + first + "/attach")).andExpect(status().isConflict());
        mvc.perform(post(base(AGENT, lead) + "/" + second + "/attach")).andExpect(status().isConflict());
        long events = outbox.findAll().stream()
                .filter(e -> e.getEventType().equals("EvidenceAttached") && e.getPayload().contains(lead.getId())).count();
        assertThat(events).isEqualTo(1);
    }

    @Test
    void anotherAgentGets404Everywhere() throws Exception {
        AgentLead lead = lead(true);
        String packId = buildPack(lead);
        String other = "ag_fictional_02";
        mvc.perform(post(base(other, lead))).andExpect(status().isNotFound());
        mvc.perform(get(base(other, lead) + "/" + packId)).andExpect(status().isNotFound());
        mvc.perform(delete(base(other, lead) + "/" + packId + "/comparables/T-1")).andExpect(status().isNotFound());
        mvc.perform(post(base(other, lead) + "/" + packId + "/attach")).andExpect(status().isNotFound());
    }

    @Test
    void curatingAnAttachedPackIs409() throws Exception {
        AgentLead lead = lead(true);
        String packId = buildPack(lead);
        mvc.perform(post(base(AGENT, lead) + "/" + packId + "/attach")).andExpect(status().isOk());
        mvc.perform(delete(base(AGENT, lead) + "/" + packId + "/comparables/T-1")).andExpect(status().isConflict());
        assertThat(packs.findById(packId).orElseThrow().getComparablesCount()).isEqualTo(5);
    }

    @Test
    void aPackWithNoRangeCannotBeAttached() throws Exception {
        search.results.subList(2, search.results.size()).clear();   // only two comparables
        AgentLead lead = lead(true);
        String created = mvc.perform(post(base(AGENT, lead)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pack.comparablesRange").isEmpty())
                .andExpect(jsonPath("$.pack.rangeNote").value("fewer than 3 comparables: no range is shown"))
                .andReturn().getResponse().getContentAsString();
        String packId = json.readTree(created).get("packId").asText();
        mvc.perform(post(base(AGENT, lead) + "/" + packId + "/attach")).andExpect(status().isConflict());
        assertThat(leads.findById(lead.getId()).orElseThrow().getState()).isEqualTo(LeadState.REPLIED);
    }

    @Test
    void excludingAComparableNotInThePackIs404() throws Exception {
        AgentLead lead = lead(true);
        String packId = buildPack(lead);
        mvc.perform(delete(base(AGENT, lead) + "/" + packId + "/comparables/NOT-THERE")).andExpect(status().isNotFound());
    }
}
