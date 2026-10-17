package uk.valuationloop.delivery;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import uk.valuationloop.outbox.Topics;

/**
 * The four delivery topics, declared here so a fresh broker gets them with the right partition count
 * (valuationloop.delivery.partitions, default 6). Spring Boot's KafkaAdmin reads every NewTopic bean at startup:
 * it creates the missing topics, and it ADDS partitions to an existing topic that has fewer (a broker that ran v1
 * with one partition is brought up to six on the next start; Kafka can add partitions, never remove them).
 *
 * Why six: DeliverLead is keyed by leadId, so the leads of one request hash to different partitions, and with
 * listener concurrency 3 (valuationloop.delivery.concurrency) each consumer thread owns two of them. A slow CRM then
 * holds up only the leads that share its thread, not every lead behind it (SlowCrmDoesNotBlockOthersTest).
 *
 * @RetryableTopic's own topic creation is switched off (autoCreateTopics = "false" in LeadDeliveryListener), so this
 * class is the one place that says how the delivery topics look. One replica: this is a single local broker;
 * production would use 3.
 */
@Configuration
public class DeliveryTopics {

    @Bean
    public KafkaAdmin.NewTopics deliveryTopicSet(@Value("${valuationloop.delivery.partitions:6}") int partitions) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.LEAD_DELIVER, partitions),
                topic(Topics.LEAD_DELIVER_RETRY_0, partitions),
                topic(Topics.LEAD_DELIVER_RETRY_1, partitions),
                topic(Topics.LEAD_DELIVER_DLT, partitions));
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
