package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequestService;

/**
 * A CRM that never answers is cut off at valuationloop.delivery.adapter-timeout-ms (300 ms here) and treated as a
 * failed attempt: lead.deliver.v1 -> -retry-0 -> -retry-1 -> -dlt, DELIVERY_FAILED after 3 attempts, and each hung
 * call was interrupted (not left running in the background).
 *
 * The "hang" adapter exists only in this test's context. No fixture agent uses it, so the test takes the DeliverLead
 * the request committed to the outbox, swaps its adapter for "hang", and publishes that to lead.deliver.v1 itself
 * (the relay is off in this context, so the original command is never sent).
 */
@SpringBootTest
@EmbeddedKafka(kraft = true, partitions = 6, topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER, Topics.LEAD_EVENTS})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:adapter_timeout_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.outbox.enabled=false",
        "valuationloop.delivery.listener.auto-startup=true",
        "valuationloop.delivery.deadline-scanner.enabled=false",
        "valuationloop.delivery.adapter-timeout-ms=300"})
@DirtiesContext
class AdapterTimeoutTest {

    /** A CRM that accepts the connection and then never answers. Counts the calls that were interrupted. */
    static class HangAdapter implements CrmAdapter {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();

        @Override
        public String name() {
            return "hang";
        }

        @Override
        public void send(LeadPush push) {
            calls.incrementAndGet();
            try {
                Thread.sleep(Duration.ofMinutes(10));
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
                throw new CrmUnavailable("hang adapter interrupted");
            }
        }
    }

    @TestConfiguration
    static class Config {
        @Bean HangAdapter hangAdapter() {
            return new HangAdapter();
        }
    }

    @Autowired ValuationRequestService requestService;
    @Autowired AgentLeadRepository leads;
    @Autowired OutboxRepository outbox;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired HangAdapter hang;

    @Test
    void aCrmThatNeverAnswersTimesOutThreeTimesAndLandsInTheDlt() throws Exception {
        String requestId = requestService.create(new ValuationRequestService.CreateCommand(
                "seller-hang", "SW11 1AA", PropertyType.FLAT, 2, new BigDecimal("68.5"),
                List.of("ag_fictional_01"))).request().getId();
        AgentLead lead = leads.findByRequestIdAndAgentId(requestId, "ag_fictional_01").orElseThrow();

        OutboxEvent deliver = outbox.findAll().stream()
                .filter(r -> r.getEventType().equals(EventTypes.DELIVER_LEAD) && r.getPartitionKey().equals(lead.getId()))
                .findFirst().orElseThrow();
        ObjectNode envelope = (ObjectNode) json.readTree(deliver.getPayload());
        ((ObjectNode) envelope.get("payload")).put("adapter", "hang");

        try (KafkaTail dlt = KafkaTail.fromBeginning(broker, Topics.LEAD_DELIVER_DLT)) {
            kafka.send(Topics.LEAD_DELIVER, lead.getId(), json.writeValueAsString(envelope)).get();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(leads.findById(lead.getId()).orElseThrow().getState()).isEqualTo(LeadState.DELIVERY_FAILED);
                assertThat(dlt.poll(r -> lead.getId().equals(r.key()))).hasSize(1);
            });
        }

        assertThat(leads.findById(lead.getId()).orElseThrow().getDeliveryAttempts()).isEqualTo(3);
        assertThat(hang.calls.get()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(hang.interrupted.get()).isEqualTo(3));

        String failed = outbox.findAll().stream()
                .filter(r -> r.getEventType().equals(EventTypes.LEAD_DELIVERY_FAILED) && r.getPayload().contains(lead.getId()))
                .map(OutboxEvent::getPayload).findFirst().orElseThrow();
        assertThat(json.readTree(failed).at("/payload/reason").asText()).contains("AdapterTimeout");
        assertThat(json.readTree(failed).at("/payload/adapter").asText()).isEqualTo("hang");
    }
}
