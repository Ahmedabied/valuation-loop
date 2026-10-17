package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.support.EventSchemas;

/**
 * Claim 1 of ARCHITECTURE.md §0, as an executable test: a lead reaches each agent through an unreliable adapter;
 * the flaky one is retried on the retry topics, lands in the DLT after exactly 3 attempts, and is marked
 * DELIVERY_FAILED, while the other three agents' leads are delivered untouched.
 */
class DeliveryRetryAndDltTest extends DeliveryIntegrationTestBase {

    static final String DLT = Topics.LEAD_DELIVER + "-dlt";
    static final String RETRY_0 = Topics.LEAD_DELIVER + "-retry-0";
    static final String RETRY_1 = Topics.LEAD_DELIVER + "-retry-1";

    static final String FLAKY_AGENT = "ag_fictional_03";

    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    void springKafkaCreatedTheRetryAndDltTopicsWithTheseNames() {
        Set<String> topics = new HashSet<>();
        // getAllListenerContainers includes the retry/DLT containers spring-kafka registered from @RetryableTopic
        for (MessageListenerContainer c : listeners.getAllListenerContainers()) {
            String[] ts = c.getContainerProperties().getTopics();
            if (ts != null) {
                topics.addAll(List.of(ts));
            }
        }
        assertThat(topics).contains(Topics.LEAD_DELIVER, RETRY_0, RETRY_1, DLT);
        assertThat(topics).filteredOn(t -> t.startsWith(Topics.LEAD_DELIVER)).hasSize(4); // 2 retries for 3 attempts
    }

    @Test
    void flakyLeadReachesTheDltAfterThreeAttemptsAndTheOthersAreDelivered() throws Exception {
        // SW11 slot holders: 01 ok, 02 slow, 03 flaky, 05 ok
        String requestId = postRequest("seller-dlt-1", "SW11 1AA", List.of());
        String flakyLeadId = leadOf(requestId, FLAKY_AGENT).getId();

        try (KafkaTail dlt = KafkaTail.fromBeginning(broker, DLT);
             KafkaTail retries = KafkaTail.fromBeginning(broker, RETRY_0, RETRY_1);
             KafkaTail events = KafkaTail.fromBeginning(broker, Topics.LEAD_EVENTS)) {

            await().atMost(AWAIT).untilAsserted(() -> {
                AgentLead flaky = leadOf(requestId, FLAKY_AGENT);
                assertThat(flaky.getState()).isEqualTo(LeadState.DELIVERY_FAILED);
                assertThat(dlt.poll(r -> flakyLeadId.equals(r.key()))).hasSize(1);
                assertThat(events.poll(r -> hasLeadId(r, flakyLeadId) && r.value().contains(EventTypes.LEAD_DELIVERY_FAILED)))
                        .hasSize(1);
                assertThat(events.poll(r -> hasRequestId(r, requestId) && r.value().contains("\"" + EventTypes.LEAD_DELIVERED + "\"")))
                        .hasSize(3);
            });

            AgentLead flaky = leadOf(requestId, FLAKY_AGENT);
            assertThat(flaky.getDeliveryAttempts()).isEqualTo(3);
            assertThat(flaky.getDeliveredAt()).isNull();
            assertThat(crm.received()).doesNotContain(flakyLeadId);

            // one hop per retry topic: main -> retry-0 -> retry-1 -> dlt
            assertThat(retries.poll(r -> flakyLeadId.equals(r.key())))
                    .extracting(ConsumerRecord::topic).containsExactlyInAnyOrder(RETRY_0, RETRY_1);

            for (AgentLead other : leads.findByRequestIdOrderByCreatedAt(requestId)) {
                if (other.getId().equals(flakyLeadId)) {
                    continue;
                }
                assertThat(other.getState()).isEqualTo(LeadState.DELIVERED);
                assertThat(other.getDeliveryAttempts()).isEqualTo(1);
                assertThat(other.getReplyDueAt()).isEqualTo(other.getDeliveredAt().plus(java.time.Duration.ofHours(24)));
                assertThat(crm.received()).contains(other.getId());
            }

            List<ConsumerRecord<String, String>> mine = events.poll(r -> hasRequestId(r, requestId));
            assertThat(mine).hasSize(4);
            for (ConsumerRecord<String, String> r : mine) {
                assertThat(r.key()).isEqualTo(requestId);      // lead.events.v1 is keyed by requestId
                EventSchemas.assertValid(r.value());
            }
            var failed = mine.stream().map(r -> readTree(r.value()))
                    .filter(e -> e.get("eventType").asText().equals(EventTypes.LEAD_DELIVERY_FAILED)).findFirst().orElseThrow();
            assertThat(failed.at("/payload/attempts").asInt()).isEqualTo(3);
            assertThat(failed.at("/payload/adapter").asText()).isEqualTo("flaky");
            assertThat(failed.at("/payload/reason").asText()).contains("CrmUnavailable");
        }
    }
}
