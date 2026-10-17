package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.OutboxRelay.OutboxRelayBatch;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequestService;

/**
 * The v1 weakness, fixed: a slow CRM (1.5 s per call) no longer delays the other agents' leads of the same request.
 * lead.deliver.v1 has 6 partitions and the listener 3 threads (each owns 2 partitions); DeliverLead is keyed by
 * leadId. The seller picks [02 slow, 01 ok, 05 ok], slow first, so in v1 (one partition, one thread) both ok leads
 * waited the full 1.5 s behind it. Here both are DELIVERED in well under 700 ms while the slow one takes >= 1500 ms.
 *
 * <p>The partition-collision guard. leadIds are random, so the three leads can hash onto partitions owned by the SAME
 * consumer thread; then the ok lead really does queue behind the slow one (that is how a partitioned log works) and
 * the test would prove nothing, or fail for the wrong reason. So the relay is off in this context and the test
 * chooses before anything is sent: it computes each lead's partition exactly as the producer's default partitioner
 * does for a keyed record ({@code Utils.toPositive(Utils.murmur2(key)) % partitions}, which is what
 * {@code BuiltInPartitioner.partitionForKey} runs), looks up which consumer thread owns that partition
 * ({@code getAssignmentsByClientId}), and if the slow lead shares a thread with either ok lead it deletes that
 * request's outbox rows and creates another request. With 3 threads a request passes about 4 times in 9, so a
 * handful of tries is enough; 50 is the cap. Then one relay pass sends the chosen request's commands.
 */
@SpringBootTest
@EmbeddedKafka(kraft = true, partitions = 6,
        topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER, Topics.LEAD_EVENTS})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:slow_crm_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.outbox.enabled=false",          // the test runs the relay by hand, after the collision check
        "valuationloop.delivery.listener.auto-startup=true",
        "valuationloop.delivery.deadline-scanner.enabled=false",
        "valuationloop.delivery.slow-ms=1500",
        "valuationloop.delivery.partitions=6",
        "valuationloop.delivery.concurrency=3"})
@DirtiesContext   // its own broker and listeners: close them after this class instead of keeping them in the cache
class SlowCrmDoesNotBlockOthersTest {

    static final String SLOW = "ag_fictional_02";
    static final String OK_1 = "ag_fictional_01";
    static final String OK_2 = "ag_fictional_05";
    static final int PARTITIONS = 6;

    @Autowired ValuationRequestService requestService;
    @Autowired AgentLeadRepository leads;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelayBatch relay;
    @Autowired KafkaListenerEndpointRegistry registry;

    @Test
    void okLeadsAreDeliveredWhileTheSlowCrmIsStillWorking() {
        String requestId = null;
        for (int attempt = 1; attempt <= 50 && requestId == null; attempt++) {
            Map<Integer, String> threadOf = partitionOwners();   // re-read: the group may still be rebalancing
            String candidate = requestService.create(new ValuationRequestService.CreateCommand(
                    "seller-slow-" + attempt, "SW11 1AA", PropertyType.FLAT, 2, new BigDecimal("68.5"),
                    List.of(SLOW, OK_1, OK_2))).request().getId();
            String slowThread = threadOf.get(partitionOf(leadOf(candidate, SLOW).getId()));
            if (!slowThread.equals(threadOf.get(partitionOf(leadOf(candidate, OK_1).getId())))
                    && !slowThread.equals(threadOf.get(partitionOf(leadOf(candidate, OK_2).getId())))) {
                requestId = candidate;
            } else {
                // a collision: never send this request (its leads would queue on one thread by design)
                outbox.deleteAll(outbox.findAll().stream().filter(r -> r.getPayload().contains(candidate)).toList());
            }
        }
        assertThat(requestId).as("a request whose slow lead is on its own consumer thread").isNotNull();

        while (relay.relayOnce() > 0) {
            // send every committed row of the chosen request (one pass is normally enough)
        }

        String chosen = requestId;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(leads.findByRequestIdOrderByCreatedAt(chosen))
                        .allSatisfy(l -> assertThat(l.getState()).isEqualTo(LeadState.DELIVERED)));

        assertThat(deliveryMs(chosen, SLOW)).isGreaterThanOrEqualTo(1500);
        assertThat(deliveryMs(chosen, OK_1)).isLessThan(700);
        assertThat(deliveryMs(chosen, OK_2)).isLessThan(700);
    }

    /**
     * partition -> client id of the consumer thread that owns it, once the group has settled: each of the 3 consumers
     * owns 2 partitions. (Right after startup the first consumer to join briefly owns all 6; a snapshot taken then
     * would put every lead on one thread.)
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, String> partitionOwners() {
        var container = (ConcurrentMessageListenerContainer<String, String>) registry.getListenerContainer("lead-delivery");
        Map<Integer, String> owners = new HashMap<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            owners.clear();
            Map<String, Collection<TopicPartition>> byClient = container.getAssignmentsByClientId();
            assertThat(byClient).hasSize(3);   // concurrency 3: three consumers in group "delivery"
            byClient.forEach((client, tps) -> {
                assertThat(tps).hasSize(PARTITIONS / 3);
                tps.forEach(tp -> owners.put(tp.partition(), client));
            });
            assertThat(owners).hasSize(PARTITIONS);
        });
        return owners;
    }

    /** The producer's partition for a String key: murmur2 of the UTF-8 bytes, made positive, modulo the count. */
    static int partitionOf(String leadId) {
        return Utils.toPositive(Utils.murmur2(leadId.getBytes(StandardCharsets.UTF_8))) % PARTITIONS;
    }

    private AgentLead leadOf(String requestId, String agentId) {
        return leads.findByRequestIdAndAgentId(requestId, agentId).orElseThrow();
    }

    private long deliveryMs(String requestId, String agentId) {
        AgentLead l = leadOf(requestId, agentId);
        return Duration.between(l.getCreatedAt(), l.getDeliveredAt()).toMillis();
    }
}
