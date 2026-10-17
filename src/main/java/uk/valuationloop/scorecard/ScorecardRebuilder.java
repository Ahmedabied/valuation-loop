package uk.valuationloop.scorecard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;
import uk.valuationloop.outbox.Topics;

/**
 * Rebuilds the scorecard from the topics (Claim 3, H2-review E2-4). The four steps of `make replay`, in code:
 * <pre>
 *   1. stop the scorecard listener          (its consumer leaves the group, so the group is inactive)
 *   2. truncate scorecard_fact              (no dedupe table to clear: the read model is upserted by lead_id)
 *   3. reset the group's offsets to earliest, with Kafka's AdminClient
 *   4. start the listener and wait until its lag is 0
 * </pre>
 * The same steps as `kafka-consumer-groups.sh --reset-offsets --to-earliest`, but in Java so a test can run them.
 */
@Component
public class ScorecardRebuilder {

    private static final Logger log = LoggerFactory.getLogger(ScorecardRebuilder.class);
    private static final List<String> TOPICS = List.of(Topics.LEAD_EVENTS, Topics.OUTCOME_RECORDED);
    private static final long ADMIN_TIMEOUT_S = 10;

    private final KafkaListenerEndpointRegistry registry;
    private final KafkaAdmin kafkaAdmin;
    private final JdbcTemplate jdbc;
    private final ScorecardFactRepository facts;

    public ScorecardRebuilder(KafkaListenerEndpointRegistry registry, KafkaAdmin kafkaAdmin, JdbcTemplate jdbc,
                              ScorecardFactRepository facts) {
        this.registry = registry;
        this.kafkaAdmin = kafkaAdmin;
        this.jdbc = jdbc;
        this.facts = facts;
    }

    /** Runs the four steps; returns how many facts the rebuilt table holds. */
    public long rebuild(Duration timeout) {
        MessageListenerContainer container = registry.getListenerContainer(ScorecardListener.LISTENER_ID);
        container.stop();   // waits for the consumer thread to finish its current record and leave the group
        jdbc.execute("truncate table scorecard_fact");
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> earliest = new HashMap<>();
            offsets(admin, OffsetSpec.earliest()).forEach((tp, offset) -> earliest.put(tp, new OffsetAndMetadata(offset)));
            resetGroup(admin, earliest);
            log.info("replay: scorecard_fact truncated, group '{}' reset to earliest on {} partition(s)",
                    ScorecardListener.GROUP_ID, earliest.size());
            container.start();
            awaitNoLag(admin, timeout);
        }
        return facts.count();
    }

    /** Waits until the scorecard group has committed every record on its topics (lag 0). */
    public void awaitCaughtUp(Duration timeout) {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            awaitNoLag(admin, timeout);
        }
    }

    /**
     * The group must have no active members for Kafka to accept new offsets. The consumer we just stopped may take a
     * moment to be removed, so this tries again a few times before giving up.
     */
    private void resetGroup(Admin admin, Map<TopicPartition, OffsetAndMetadata> offsets) {
        for (int attempt = 1; ; attempt++) {
            try {
                admin.alterConsumerGroupOffsets(ScorecardListener.GROUP_ID, offsets).all().get(ADMIN_TIMEOUT_S, TimeUnit.SECONDS);
                return;
            } catch (ExecutionException e) {
                if (attempt >= 20) {
                    throw new IllegalStateException("could not reset group " + ScorecardListener.GROUP_ID
                            + " (is another scorecard consumer still running?)", e.getCause());
                }
                log.debug("group not ready for an offset reset yet ({}), retrying", e.getCause().getMessage());
                sleep(Duration.ofMillis(500));
            } catch (Exception e) {
                throw new IllegalStateException("offset reset failed", e);
            }
        }
    }

    private void awaitNoLag(Admin admin, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        long lag;
        while ((lag = lag(admin)) > 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("scorecard still " + lag + " record(s) behind after " + timeout);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    /** Sum over partitions of (latest offset - offset the scorecard group has committed). */
    long lag(Admin admin) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(ScorecardListener.GROUP_ID)
                    .partitionsToOffsetAndMetadata().get(ADMIN_TIMEOUT_S, TimeUnit.SECONDS);
            long lag = 0;
            for (var end : offsets(admin, OffsetSpec.latest()).entrySet()) {
                OffsetAndMetadata c = committed.get(end.getKey());
                lag += end.getValue() - (c == null ? 0 : c.offset());
            }
            return lag;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read scorecard lag", e);
        }
    }

    /** Offset per partition of the scorecard's topics (topics that do not exist yet are skipped). */
    private Map<TopicPartition, Long> offsets(Admin admin, OffsetSpec spec) {
        try {
            Set<String> existing = admin.listTopics().names().get(ADMIN_TIMEOUT_S, TimeUnit.SECONDS);
            List<String> topics = TOPICS.stream().filter(existing::contains).toList();
            Map<String, TopicDescription> described = admin.describeTopics(topics).allTopicNames()
                    .get(ADMIN_TIMEOUT_S, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            for (TopicDescription d : described.values()) {
                d.partitions().forEach(p -> request.put(new TopicPartition(d.name(), p.partition()), spec));
            }
            Map<TopicPartition, Long> result = new HashMap<>();
            admin.listOffsets(request).all().get(ADMIN_TIMEOUT_S, TimeUnit.SECONDS)
                    .forEach((tp, info) -> result.put(tp, info.offset()));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("cannot list offsets for " + TOPICS, e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Lines describing how two snapshots differ; empty when they are identical. */
    public static List<String> diff(List<FactSnapshot> before, List<FactSnapshot> after) {
        Map<String, FactSnapshot> a = new HashMap<>();
        before.forEach(f -> a.put(f.leadId(), f));
        Map<String, FactSnapshot> b = new HashMap<>();
        after.forEach(f -> b.put(f.leadId(), f));
        List<String> lines = new ArrayList<>();
        for (FactSnapshot f : before) {
            FactSnapshot g = b.get(f.leadId());
            if (g == null) {
                lines.add("missing after replay: " + f);
            } else if (!g.equals(f)) {
                lines.add("changed: " + f + "\n     -> " + g);
            }
        }
        after.stream().filter(g -> !a.containsKey(g.leadId())).forEach(g -> lines.add("new after replay: " + g));
        return lines;
    }
}
