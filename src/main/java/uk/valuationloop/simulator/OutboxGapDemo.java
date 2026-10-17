package uk.valuationloop.simulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import uk.valuationloop.observability.EventTracing;
import uk.valuationloop.observability.LoopMetrics;
import uk.valuationloop.outbox.OutboxRelay;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequestService;

/**
 * The outbox story without killing the JVM (ARCHITECTURE.md §5). With the relay paused, a request commits: its rows
 * are in the outbox, unsent, and nothing is on Kafka; that is the state a crash between commit and send leaves
 * behind. Resume (= restart): the relay sends them, and the events on the topics carry the SAME eventIds as the
 * rows, each exactly once. The real kill-and-restart proof is the OutboxRelayTest; this makes it visible.
 */
@Component
@Profile("demo")
public class OutboxGapDemo {

    private static final Duration PAUSED_FOR = Duration.ofMillis(1500);   // five relay ticks with nothing sent
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final List<String> TOPICS = List.of(Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER);

    private final OutboxRelay relay;
    private final ValuationRequestService requestService;
    private final JdbcTemplate jdbc;
    private final ConsumerFactory<?, ?> consumerFactory;
    private final MeterRegistry registry;
    private final ObjectMapper json;
    private final String postcode;

    public OutboxGapDemo(OutboxRelay relay, ValuationRequestService requestService, JdbcTemplate jdbc,
                         ConsumerFactory<?, ?> consumerFactory, MeterRegistry registry, ObjectMapper json,
                         @Value("${valuationloop.simulator.postcode:SW11 1AA}") String postcode) {
        this.relay = relay;
        this.requestService = requestService;
        this.jdbc = jdbc;
        this.consumerFactory = consumerFactory;
        this.registry = registry;
        this.json = json;
        this.postcode = postcode;
    }

    public ScenarioReport.OutboxGapReport run() {
        return EventTracing.newTrace("simulator.outbox-gap", this::runTraced);
    }

    private ScenarioReport.OutboxGapReport runTraced() {
        long started = System.nanoTime();
        try (Consumer<?, ?> consumer = consumerFactory.createConsumer("simulator-outbox-gap", "sim")) {
            List<TopicPartition> partitions = partitions(consumer);
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);   // fix "the end" now, before anything new is sent

            relay.pause();
            String requestId;
            List<String> rowIds;
            int unsentWhilePaused;
            double gauge;
            try {
                requestId = requestService.create(new ValuationRequestService.CreateCommand("sim-outbox-gap-"
                        + Long.toString(System.currentTimeMillis(), 36), postcode, PropertyType.FLAT, 2,
                        new BigDecimal("68.5"), List.of(SimulatorScenarios.AGENT_OK))).request().getId();
                rowIds = rowIdsOf(requestId);
                sleep(PAUSED_FOR);
                unsentWhilePaused = unsentOf(rowIds);
                gauge = registry.get(LoopMetrics.OUTBOX_UNSENT).gauge().value();
            } finally {
                relay.resume();
            }

            awaitAllSent(rowIds);
            List<String> onTopics = eventIdsOnTopics(consumer, requestId, rowIds.size());
            boolean same = new HashSet<>(onTopics).equals(new HashSet<>(rowIds));
            boolean once = onTopics.size() == rowIds.size() && same;
            return new ScenarioReport.OutboxGapReport(requestId, EventTracing.currentTraceId(), rowIds.size(),
                    unsentWhilePaused, gauge, rowIds.size() - unsentOf(rowIds), rowIds, onTopics, same, once,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    /** The request's outbox rows on the two topics it writes first (requested + deliver commands), as eventIds. */
    private List<String> rowIdsOf(String requestId) {
        // ValuationRequested/AgentsMatched are keyed by requestId; DeliverLead by leadId, so join through agent_lead
        return jdbc.queryForList("""
                select cast(id as varchar) from outbox
                where partition_key = ? or partition_key in (select id from agent_lead where request_id = ?)
                order by created_at
                """, String.class, requestId, requestId);
    }

    private int unsentOf(List<String> rowIds) {
        int unsent = 0;
        for (String id : rowIds) {
            unsent += jdbc.queryForObject("select count(*) from outbox where cast(id as varchar) = ? and sent_at is null",
                    Integer.class, id);
        }
        return unsent;
    }

    private void awaitAllSent(List<String> rowIds) {
        Instant deadline = Instant.now().plus(WAIT);
        while (unsentOf(rowIds) > 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new SimulatorScenarios.ScenarioFailed("outbox rows still unsent " + WAIT.toSeconds() + " s after resume");
            }
            sleep(Duration.ofMillis(100));
        }
    }

    private List<TopicPartition> partitions(Consumer<?, ?> consumer) {
        List<TopicPartition> result = new ArrayList<>();
        for (String topic : TOPICS) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(10));
            if (infos != null) {
                infos.forEach(p -> result.add(new TopicPartition(topic, p.partition())));
            }
        }
        return result;
    }

    /**
     * Reads from the positions fixed before the pause and keeps the eventIds of this request's envelopes. Once it has
     * as many as there are rows it polls once more, so a duplicate, if the relay had sent one, would be counted too.
     */
    private List<String> eventIdsOnTopics(Consumer<?, ?> consumer, String requestId, int expected) {
        List<String> ids = new ArrayList<>();
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            boolean hadAll = ids.size() >= expected;
            for (ConsumerRecord<?, ?> r : consumer.poll(Duration.ofMillis(500))) {
                try {
                    JsonNode env = json.readTree(String.valueOf(r.value()));
                    if (requestId.equals(env.path("requestId").asString())) {
                        ids.add(env.path("eventId").asString());
                    }
                } catch (Exception e) {
                    // not an envelope, so not one of ours
                }
            }
            if (hadAll) {
                break;
            }
        }
        return ids;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatorScenarios.ScenarioFailed("interrupted");
        }
    }
}
