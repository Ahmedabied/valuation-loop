package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.outbox.EventEnvelope;

/**
 * Claim 3, without Kafka: the facts are a function of the SET of events, not of their order. The scripted log
 * (ScorecardScript, the same one ScorecardReplayTest sends through Kafka) plus its duplicates is projected through the
 * real ScorecardProjector in 20 seeded random permutations, each into an empty table; every permutation must give the
 * same rows as the scripted order. Seeded, so a failure names a permutation that can be re-run exactly.
 *
 * @DataJpaTest starts only JPA, H2 and Flyway (no web, no Kafka). NOT_SUPPORTED turns off the test-managed
 * transaction, so each apply() commits on its own exactly as it does behind the listener.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // the H2 below, migrated by Flyway
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:random_order_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@Import({ScorecardProjector.class, RandomOrderProjectionTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RandomOrderProjectionTest {

    static final int PERMUTATIONS = 20;

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);   // only feeds updated_at
        }
    }

    @Autowired ScorecardProjector projector;
    @Autowired ScorecardFactRepository facts;

    List<FactSnapshot> project(List<EventEnvelope> events) {
        facts.deleteAllInBatch();
        events.forEach(projector::apply);
        return facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of).toList();
    }

    @Test
    void everyPermutationOfTheEventLogProjectsIdenticalFacts() {
        ScorecardScript script = new ScorecardScript();
        List<EventEnvelope> events = new ArrayList<>();
        script.log().forEach(r -> events.add(ScorecardScript.parse(r.json())));       // through JSON, as on the wire
        script.duplicates().forEach(r -> events.add(ScorecardScript.parse(r.json())));

        List<FactSnapshot> scripted = project(events);
        assertThat(scripted).hasSize(6);
        // the expected values are the replay test's; a few spot checks that the projection is not trivially empty
        FactSnapshot a1 = scripted.stream().filter(f -> f.leadId().equals(ScorecardScript.A1)).findFirst().orElseThrow();
        assertThat(a1.instructed()).isTrue();
        assertThat(a1.rangeRank()).isEqualTo("MIDDLE");
        assertThat(a1.priceGapPct()).isEqualTo("2.12");

        for (int seed = 1; seed <= PERMUTATIONS; seed++) {
            List<EventEnvelope> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, new Random(seed));
            List<FactSnapshot> projected = project(shuffled);
            assertThat(ScorecardRebuilder.diff(scripted, projected)).as("permutation with seed %d", seed).isEmpty();
            assertThat(projected).as("permutation with seed %d", seed).isEqualTo(scripted);
        }
    }
}
