package uk.valuationloop.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.valuationloop.outbox.OutboxRelay;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.scorecard.ScorecardListener;

/**
 * The split run's wiring, from the profile files alone (application-api.yml, application-worker.yml): one jar, two
 * contexts. Under "worker" the DeliverLead containers (lead.deliver.v1 and its retry/DLT containers) run and the outbox
 * relay sends; the scorecard container exists but never starts. Under "api" it is the other way round. The test yml
 * turns every consumer off, and the profile file must turn the right ones back on; nothing here overrides them.
 */
class ApiWorkerProfilesTest {

    static final String DELIVERY_ID = "lead-delivery";

    static List<MessageListenerContainer> deliveryContainers(KafkaListenerEndpointRegistry registry) {
        List<MessageListenerContainer> found = registry.getListenerContainers().stream()
                .filter(c -> c.getListenerId() != null && c.getListenerId().startsWith(DELIVERY_ID)).toList();
        assertThat(found).as("DeliverLead containers registered").isNotEmpty();
        return found;
    }

    static MessageListenerContainer scorecardContainer(KafkaListenerEndpointRegistry registry) {
        MessageListenerContainer c = registry.getListenerContainer(ScorecardListener.LISTENER_ID);
        assertThat(c).as("scorecard container registered").isNotNull();
        return c;
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "worker"})
    @EmbeddedKafka(kraft = true, partitions = 1, topics = {Topics.LEAD_DELIVER, Topics.LEAD_EVENTS})
    @TestPropertySource(properties =
            "spring.datasource.url=jdbc:h2:mem:profile_worker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
    class Worker {

        @Autowired KafkaListenerEndpointRegistry registry;
        @Autowired OutboxRelay relay;

        @Test
        void runsDeliveryAndTheRelayButNotTheScorecard() {
            assertThat(deliveryContainers(registry)).allSatisfy(c -> {
                assertThat(c.isAutoStartup()).as(c.getListenerId() + " autoStartup").isTrue();
                assertThat(c.isRunning()).as(c.getListenerId() + " running").isTrue();
            });
            assertThat(relay.isEnabled()).isTrue();
            MessageListenerContainer scorecard = scorecardContainer(registry);
            assertThat(scorecard.isAutoStartup()).isFalse();
            assertThat(scorecard.isRunning()).isFalse();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "api"})
    @EmbeddedKafka(kraft = true, partitions = 1, topics = {Topics.LEAD_DELIVER, Topics.LEAD_EVENTS})
    @TestPropertySource(properties =
            "spring.datasource.url=jdbc:h2:mem:profile_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
    class Api {

        @Autowired KafkaListenerEndpointRegistry registry;
        @Autowired OutboxRelay relay;

        @Test
        void runsTheScorecardButNotDeliveryOrTheRelay() {
            assertThat(deliveryContainers(registry)).allSatisfy(c -> {
                assertThat(c.isAutoStartup()).as(c.getListenerId() + " autoStartup").isFalse();
                assertThat(c.isRunning()).as(c.getListenerId() + " running").isFalse();
            });
            assertThat(relay.isEnabled()).isFalse();
            MessageListenerContainer scorecard = scorecardContainer(registry);
            assertThat(scorecard.isAutoStartup()).isTrue();
            assertThat(scorecard.isRunning()).isTrue();
        }
    }
}
