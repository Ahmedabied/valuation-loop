package uk.valuationloop.simulator;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uk.valuationloop.delivery.FlakyAdapter;
import uk.valuationloop.observability.LoopMetrics;
import uk.valuationloop.outbox.Topics;

/**
 * What the simulator needs from the delivery side, wherever delivery runs.
 *
 * One process (make demo): valuationloop.simulator.worker-url is empty and every call goes straight to the beans.
 * Split (make demo-split, profile api): the adapters and the DLT handler are in the worker process, so the flaky
 * override and the DLT count go over loopback HTTP to the worker's DeliveryDemoController.
 *
 * Kafka facts come from the broker itself (AdminClient), so they are true in either layout: whether consumer group
 * "delivery" has every lead.deliver.v1 partition assigned, and which partition a lead's DeliverLead goes to.
 */
@Component
@Profile("demo")
public class DeliveryControl {

    private final FlakyAdapter flaky;
    private final LoopMetrics metrics;
    private final KafkaAdmin kafkaAdmin;
    private final RestClient worker;   // null in one process

    public DeliveryControl(FlakyAdapter flaky, LoopMetrics metrics, KafkaAdmin kafkaAdmin,
                           @Value("${valuationloop.simulator.worker-url:}") String workerUrl) {
        this.flaky = flaky;
        this.metrics = metrics;
        this.kafkaAdmin = kafkaAdmin;
        this.worker = workerUrl.isBlank() ? null : RestClient.builder().baseUrl(workerUrl).build();
    }

    public boolean deliveryIsRemote() {
        return worker != null;
    }

    public void overrideFlakyFailures(int n) {
        if (worker == null) {
            flaky.overrideFailuresPerLead(n);
        } else {
            worker.put().uri("/internal/demo/delivery/flaky-failures?n={n}", n).retrieve().toBodilessEntity();
        }
    }

    public void clearFlakyOverride() {
        if (worker == null) {
            flaky.clearOverride();
        } else {
            worker.delete().uri("/internal/demo/delivery/flaky-failures").retrieve().toBodilessEntity();
        }
    }

    /** dlt_records_total of the process that runs the DLT handler. */
    @SuppressWarnings("unchecked")
    public double dltRecordsSoFar() {
        if (worker == null) {
            return metrics.dltRecordsSoFar();
        }
        Map<String, Number> body = worker.get().uri("/internal/demo/delivery/dlt-records").retrieve().body(Map.class);
        return body.get("dltRecords").doubleValue();
    }

    /** True once group "delivery" is stable and its members together hold every lead.deliver.v1 partition. */
    public boolean deliveryGroupHasAllPartitions() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            int partitions = partitionCount(admin);
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of("delivery")).all()
                    .get(5, TimeUnit.SECONDS).get("delivery");
            long assigned = group.members().stream().map(MemberDescription::assignment)
                    .flatMap(a -> a.topicPartitions().stream())
                    .filter(tp -> tp.topic().equals(Topics.LEAD_DELIVER)).count();
            return group.groupState() == GroupState.STABLE && assigned == partitions;
        } catch (Exception e) {
            return false;   // broker busy or group not there yet: the caller polls again
        }
    }

    /**
     * Which member of group "delivery" (one per listener thread, named by its client id) owns each lead.deliver.v1
     * partition right now, as the broker reports it. Empty if the broker cannot say; the slow check then treats every
     * ok lead as on another thread, the strict reading.
     */
    public Map<Integer, String> deliveryOwners() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of("delivery")).all()
                    .get(5, TimeUnit.SECONDS).get("delivery");
            Map<Integer, String> owners = new TreeMap<>();
            for (MemberDescription m : group.members()) {
                m.assignment().topicPartitions().stream().filter(tp -> tp.topic().equals(Topics.LEAD_DELIVER))
                        .forEach(tp -> owners.put(tp.partition(), m.clientId()));
            }
            return owners;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** lead.deliver.v1's partition count, as the broker has it. */
    public int deliverPartitions() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            return partitionCount(admin);
        } catch (Exception e) {
            throw new IllegalStateException("cannot describe " + Topics.LEAD_DELIVER, e);
        }
    }

    /** The partition the producer picks for this key: murmur2 of the UTF-8 bytes (BuiltInPartitioner.partitionForKey). */
    public static int partitionOf(String leadId, int partitions) {
        return Utils.toPositive(Utils.murmur2(leadId.getBytes(StandardCharsets.UTF_8))) % partitions;
    }

    private static int partitionCount(AdminClient admin) throws Exception {
        return admin.describeTopics(List.of(Topics.LEAD_DELIVER)).allTopicNames().get(5, TimeUnit.SECONDS)
                .get(Topics.LEAD_DELIVER).partitions().size();
    }
}
