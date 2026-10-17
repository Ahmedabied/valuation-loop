package uk.valuationloop.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.valuationloop.completion.CompletedSales;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.FakeEvidenceSources;
import uk.valuationloop.evidence.GeoPoint;
import uk.valuationloop.evidence.PostcodeCentroids;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.simulator.ScenarioReport.LeadLine;

/**
 * The "ok" scenario end to end, with every asynchronous part running for real: the outbox relay sends to an embedded
 * Kafka broker, the delivery consumer calls the adapters (the flaky one lands on its third attempt through the retry
 * topics) and the scorecard consumer builds the facts. Only Elasticsearch is faked (in-memory comparables), so the
 * test needs no services. The test profile's yml turns the background parts off; this class turns them on.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@EmbeddedKafka(kraft = true, partitions = 1,
        topics = {Topics.VALUATION_REQUESTED, Topics.LEAD_DELIVER, Topics.LEAD_EVENTS, Topics.OUTCOME_RECORDED})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:simulator_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "valuationloop.outbox.enabled=true",
        "valuationloop.delivery.listener.auto-startup=true",
        "valuationloop.scorecard.listener.auto-startup=true",
        "valuationloop.delivery.deadline-scanner.enabled=false",
        "valuationloop.delivery.flaky-failures=2",
        "valuationloop.delivery.slow-ms=50"})
class SimulatorScenarioTest {

    @TestConfiguration
    static class FakeEs {
        @Bean @Primary FakeEvidenceSources.Search fakeSearch() {
            FakeEvidenceSources.Search search = new FakeEvidenceSources.Search();
            for (int i = 0; i < 8; i++) {   // eight fictional flats, 50 m apart, 560k..630k, all with a floor area
                search.results.add(FakeEvidenceSources.sale("ppd-sim-" + i, 560_000 + 10_000L * i, 66.0 + i, 50.0 * i));
            }
            return search;
        }

        /**
         * Land Registry, faked: one later sale at whatever postcode is asked, 45 days after the earliest date the rule
         * allows (so 15 days after the instruction), so the late match finds it.
         */
        @Bean @Primary CompletedSales fakeCompletedSales() {
            return (postcode, type, onOrAfter) -> List.of(new ComparableDocument("ppd-sim-completion", 612_000,
                    onOrAfter.plusDays(45).toString(), postcode, postcode.split(" ")[0], FakeEvidenceSources.ORIGIN,
                    type, null, false, "L", null, null, null, null, "WANDSWORTH", "ppd-test"));
        }

        /** Every postcode resolves (the real centroid index is in Elasticsearch). */
        @Bean @Primary PostcodeCentroids anyPostcode() {
            return postcode -> Optional.of(new GeoPoint(FakeEvidenceSources.ORIGIN.lat(), FakeEvidenceSources.ORIGIN.lon()));
        }
    }

    @Autowired SimulatorScenarios scenarios;

    @Test
    void okScenarioRunsTheWholeLoopAndTheScorecardShowsTheInstruction() {
        ScenarioReport report = scenarios.run("ok");

        assertThat(report.leads()).hasSize(3);
        assertThat(report.leads()).allSatisfy(l -> assertThat(l.repliedAt()).isNotNull());
        LeadLine flaky = report.leads().stream().filter(l -> l.adapter().equals("flaky")).findFirst().orElseThrow();
        assertThat(flaky.attempts()).isEqualTo(3);   // failed twice, delivered through lead.deliver.v1-retry-1

        // the evidenced lead is the one the seller selected, and the CRM's instruction joined back to it
        LeadLine selected = report.leads().stream().filter(l -> l.leadId().equals(report.sellerSelectedLeadId()))
                .findFirst().orElseThrow();
        assertThat(selected.agentId()).isEqualTo(SimulatorScenarios.AGENT_OK_2);
        assertThat(selected.evidenceAttached()).isTrue();
        assertThat(selected.comparablesCount()).isEqualTo(7);   // 8 built, 1 excluded by the agent
        assertThat(selected.state()).isEqualTo("VISIT_BOOKED");
        assertThat(report.instructedLeadId()).isEqualTo(report.sellerSelectedLeadId());
        assertThat(report.requestState()).isEqualTo("CLOSED");

        @SuppressWarnings("unchecked")
        Map<String, Object> fact = (Map<String, Object>) report.checks().get("scorecard fact of the selected lead");
        assertThat(fact).containsEntry("instructed", true).containsEntry("sellerSelected", true)
                .containsEntry("visitBooked", true).containsEntry("evidenceAttached", true);
        assertThat(report.selectedAgentScorecard().instructed()).isEqualTo(1);

        // the v2 late match: the sale joined to the selected lead, with the gap to its replied range's midpoint
        assertThat(fact).containsEntry("completed", true);
        assertThat(fact.get("priceGapPct")).isNotNull();
        assertThat(report.checks().get("Land Registry late match").toString())
                .startsWith("completed=true, priceGapPct=" + fact.get("priceGapPct"))
                .contains("ppd-sim-completion").contains("demo: self-match possible");
        assertThat(report.selectedAgentScorecard().completed()).isEqualTo(1);
        assertThat(report.selectedAgentScorecard().medianPriceGapPct()).isNotNull();

        assertThat(SimulatorReportPrinter.print(report)).contains("== ok").contains(report.requestId())
                .contains("completed=true").contains("completed 1, median price gap");
    }
}
