package uk.valuationloop.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.valuationloop.evidence.FakeEvidenceSources.sale;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.valuationloop.dataloader.EsIndexWriter;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.OutboxEvent;
import uk.valuationloop.outbox.OutboxRepository;
import uk.valuationloop.request.PropertyType;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;
import uk.valuationloop.support.EventSchemas;

/**
 * Claim 2 of ARCHITECTURE.md §0 against a REAL Elasticsearch (make es-up): a pack has provenance, and the comparables
 * query ranks a floor-area match above an otherwise identical sale with no floor area.
 *
 * Runs only when the test JVM has -Des=true (the build passes it for ./gradlew test -Pes=true); skipped otherwise.
 * Uses throwaway indices named *-test-&lt;nanos&gt; and deletes them afterwards; comparables-v1 is never touched.
 */
@EnabledIfSystemProperty(named = "es", matches = "true")
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:es_ranking_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)   // one instance for all tests, so @BeforeAll can use @Autowired beans
class ComparablesRankingEsTest {

    private static final long NANOS = System.nanoTime();
    private static final String INDEX = "comparables-test-" + NANOS;
    private static final String CENTROIDS = "postcode-centroids-test-" + NANOS;

    /** Points the app's evidence settings at the throwaway indices before the context starts. */
    @DynamicPropertySource
    static void indices(DynamicPropertyRegistry registry) {
        registry.add("valuationloop.evidence.index", () -> INDEX);
        registry.add("valuationloop.evidence.centroid-index", () -> CENTROIDS);
    }

    @Autowired EsIndexWriter writer;
    @Autowired ComparablesSearch search;
    @Autowired EvidenceService service;
    @Autowired EvidenceProperties settings;
    @Autowired ValuationRequestRepository requests;
    @Autowired AgentLeadRepository leads;
    @Autowired OutboxRepository outbox;
    @Autowired Clock clock;

    @BeforeAll
    void createIndices() throws IOException {
        writer.ensureIndex(INDEX, "/es/comparables-v1.json", true);
        writer.ensureIndex(CENTROIDS, "/es/postcode-centroids-v1.json", true);
        Map<String, ComparableDocument> docs = new LinkedHashMap<>();
        // the pair under test: same place, date, price and type; only the floor area differs
        docs.put("WITH-AREA", sale("WITH-AREA", 600_000, 68.0, 100));
        docs.put("NO-AREA", sale("NO-AREA", 600_000, null, 100));
        // two more matched sales further away, so the pack has a range
        docs.put("FAR-1", sale("FAR-1", 500_000, 68.0, 700));
        docs.put("FAR-2", sale("FAR-2", 700_000, 68.0, 900));
        writer.bulk(INDEX, docs);
        writer.bulk(CENTROIDS, Map.of("ZZ1 1ZZ", new PostcodeCentroids.Centroid("ZZ1 1ZZ", FakeEvidenceSources.ORIGIN)));
        writer.refresh(INDEX);
        writer.refresh(CENTROIDS);
    }

    @AfterAll
    void deleteIndices() throws IOException {
        writer.deleteIndex(INDEX);
        writer.deleteIndex(CENTROIDS);
    }

    @Test
    void floorAreaMatchRanksAboveMissingFloorArea() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Europe/London"));
        ComparablesQuery query = new ComparablesQuery(new ComparablesQuery.Params("FLAT", FakeEvidenceSources.ORIGIN,
                68.0, today, null), settings);
        List<String> ranked = search.search(query).stream().map(ComparableDocument::ppdTransactionId).toList();
        assertThat(ranked).containsExactly("WITH-AREA", "NO-AREA", "FAR-1", "FAR-2");
    }

    @Test
    void packFromLiveEsHasProvenanceAndAttachEmitsASchemaValidEvent() {
        Instant now = Instant.now();
        ValuationRequest r = requests.save(ValuationRequest.receive("seller-es-test", "ZZ1 1ZZ", PropertyType.FLAT, 2,
                new BigDecimal("68"), now));
        AgentLead lead = AgentLead.create(r.getId(), "ag_fictional_05", now);
        lead.startDelivery(now);
        lead.delivered(now, Duration.ofHours(24));
        lead.reply(new BigDecimal("580000"), new BigDecimal("640000"), null, now);
        leads.save(lead);

        EvidenceService.AgentPackView built = service.build("ag_fictional_05", lead.getId());
        Pack pack = built.pack();
        assertThat(pack.comparables()).extracting(Comparable::ppdTransactionId)
                .containsExactly("WITH-AREA", "NO-AREA", "FAR-1", "FAR-2");
        assertThat(pack.comparablesRange()).isEqualTo(new ComparablesRange(575_000, 600_000, 625_000));
        assertThat(pack.provenance().attributions()).containsExactly(Provenance.LAND_REGISTRY, Provenance.CODE_POINT, Provenance.EPC);
        assertThat(pack.provenance().index()).isEqualTo(INDEX);
        assertThat(pack.provenance().query()).containsEntry("missingFloorAreaWeight", 0.5);
        assertThat(pack.dataVintage()).isEqualTo("ppd-2024-2026+epc-2026-09");

        service.attach("ag_fictional_05", lead.getId(), built.packId());
        OutboxEvent event = outbox.findAll().stream()
                .filter(e -> e.getEventType().equals("EvidenceAttached") && e.getPayload().contains(lead.getId()))
                .findFirst().orElseThrow();
        EventSchemas.assertValid(event.getPayload());
    }
}
