package uk.valuationloop.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.RequestTransitions.RequestEvent;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/**
 * At-least-once means the same DeliverLead can arrive twice (the relay resends after a crash). The second copy
 * must not deliver again: the CRM sees one call, one LeadDelivered is emitted, delivery_attempts stays 1.
 */
class DuplicateDeliveryTest extends DeliveryIntegrationTestBase {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired TransactionTemplate tx;
    @Autowired ValuationRequestRepository requests;

    @Test
    void theSameDeliverLeadTwiceDeliversOnce() throws Exception {
        // a request + lead written straight to the database, so the outbox holds no DeliverLead of its own:
        // the only copies on the topic are the two this test sends
        AgentLead lead = tx.execute(status -> {
            Instant now = clock.instant();
            ValuationRequest r = ValuationRequest.receive("seller-dup-1", "SW11 2BB", PropertyType.FLAT, 2, null, now);
            r.apply(new RequestEvent.AgentsMatched(), now);
            r.apply(new RequestEvent.LeadsDispatched(), now);
            requests.save(r);
            return leads.save(AgentLead.create(r.getId(), "ag_fictional_01", now));
        });
        EventEnvelope command = EventEnvelope.of(EventTypes.DELIVER_LEAD, lead.getRequestId(), lead.getId(),
                lead.getAgentId(), Map.of("adapter", "ok"));
        String wire = json.writeValueAsString(command);
        EventSchemas.assertValid(wire);

        try (KafkaTail events = KafkaTail.fromBeginning(broker, Topics.LEAD_EVENTS)) {
            kafka.send(Topics.LEAD_DELIVER, lead.getId(), wire).get(10, TimeUnit.SECONDS);
            kafka.send(Topics.LEAD_DELIVER, lead.getId(), wire).get(10, TimeUnit.SECONDS); // same eventId, same bytes

            await().atMost(AWAIT).until(() -> deliveryGroupHasConsumedEverything());
            await().atMost(AWAIT).until(() -> outbox.countBySentAtIsNull() == 0); // the relay has published LeadDelivered

            AgentLead after = leads.findById(lead.getId()).orElseThrow();
            assertThat(after.getState()).isEqualTo(LeadState.DELIVERED);
            assertThat(after.getDeliveryAttempts()).isEqualTo(1);
            assertThat(crm.received()).contains(lead.getId());
            assertThat(crm.acceptedCalls(lead.getId())).isEqualTo(1);   // the adapter was not even called again

            // give a late duplicate LeadDelivered every chance to show up before counting
            await().pollDelay(Duration.ofMillis(500)).atMost(AWAIT).untilAsserted(() ->
                    assertThat(events.poll(r -> hasLeadId(r, lead.getId()))).hasSize(1));
            var delivered = events.poll(r -> hasLeadId(r, lead.getId())).get(0).value();
            EventSchemas.assertValid(delivered);
            assertThat(readTree(delivered).get("eventType").asString()).isEqualTo(EventTypes.LEAD_DELIVERED);
        }
    }

    /**
     * The "delivery" consumer group's committed offset has reached the end of every lead.deliver.v1 partition that
     * holds records (the lead's key can hash to any of the 6; a partition with no records has nothing to commit).
     */
    private boolean deliveryGroupHasConsumedEverything() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        try (AdminClient admin = AdminClient.create(props)) {
            int partitions = admin.describeTopics(List.of(Topics.LEAD_DELIVER)).allTopicNames().get()
                    .get(Topics.LEAD_DELIVER).partitions().size();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (int i = 0; i < partitions; i++) {
                latest.put(new TopicPartition(Topics.LEAD_DELIVER, i), OffsetSpec.latest());
            }
            var ends = admin.listOffsets(latest).all().get();
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("delivery")
                    .partitionsToOffsetAndMetadata().get();
            for (var end : ends.entrySet()) {
                OffsetAndMetadata c = committed.get(end.getKey());
                long done = c == null ? 0 : c.offset();
                if (done != end.getValue().offset()) {
                    return false;
                }
            }
            return true;
        }
    }
}
