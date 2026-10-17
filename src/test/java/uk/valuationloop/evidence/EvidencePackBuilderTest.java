package uk.valuationloop.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.valuationloop.evidence.FakeEvidenceSources.sale;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvidencePackBuilderTest {

    private final FakeEvidenceSources.Search search = new FakeEvidenceSources.Search();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC);
    private EvidencePackBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new EvidencePackBuilder(search, new FakeEvidenceSources.Centroids(), EvidenceProperties.defaults(), clock);
        search.results.add(sale("T-1", 500_000, 68.0, 100));
        search.results.add(sale("T-2", 600_000, null, 250));
        search.results.add(sale("T-3", 700_000, 70.0, 500));
        search.results.add(sale("T-4", 800_000, 66.0, 900));
    }

    @Test
    void buildsAPackWithComparablesRangeAndProvenance() {
        Pack pack = builder.build("ep_test-1", new EvidencePackBuilder.Subject("ZZ1 1ZZ", "FLAT", new BigDecimal("68.0")));

        assertThat(pack.packId()).isEqualTo("ep_test-1");
        assertThat(pack.comparables()).extracting(Comparable::ppdTransactionId).containsExactly("T-1", "T-2", "T-3", "T-4");
        assertThat(pack.comparables().get(0).distanceM()).isEqualTo(100);
        assertThat(pack.comparables().get(1).floorAreaM2()).isNull();
        assertThat(pack.comparablesRange()).isEqualTo(new ComparablesRange(575_000, 650_000, 725_000));
        assertThat(pack.rangeNote()).isNull();
        assertThat(pack.method()).isEqualTo("lower quartile / median / upper quartile of attached comparables");
        assertThat(pack.dataVintage()).isEqualTo("ppd-2024-2026+epc-2026-09");
        assertThat(pack.provenance().attributions()).containsExactly(Provenance.LAND_REGISTRY, Provenance.CODE_POINT, Provenance.EPC);
        assertThat(pack.provenance().index()).isEqualTo("comparables-v1");
        assertThat(pack.provenance().query()).containsEntry("radiusM", 1500).containsEntry("floorAreaM2", 68.0)
                .containsEntry("missingFloorAreaWeight", 0.5);
        assertThat(pack.provenance().builtAt()).isEqualTo(clock.instant());

        // the query the builder ran: the request's type, centroid, floor area, and "today" from the clock
        ComparablesQuery.Params asked = search.lastQuery.params();
        assertThat(asked.propertyType()).isEqualTo("FLAT");
        assertThat(asked.origin()).isEqualTo(FakeEvidenceSources.ORIGIN);
        assertThat(asked.floorAreaM2()).isEqualTo(68.0);
        assertThat(asked.asOf()).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void removingAComparableRecomputesTheRangeWithoutRequerying() {
        Pack pack = builder.build("ep_test-2", new EvidencePackBuilder.Subject("ZZ1 1ZZ", "FLAT", null));
        search.results.clear();   // a re-query would now find nothing

        Pack curated = builder.without(pack, "T-4");
        assertThat(curated.comparables()).extracting(Comparable::ppdTransactionId).containsExactly("T-1", "T-2", "T-3");
        assertThat(curated.comparablesRange()).isEqualTo(new ComparablesRange(550_000, 600_000, 650_000));
        assertThat(curated.provenance()).isEqualTo(pack.provenance());

        Pack tooFew = builder.without(curated, "T-3");
        assertThat(tooFew.comparablesRange()).isNull();
        assertThat(tooFew.rangeNote()).isEqualTo("fewer than 3 comparables: no range is shown");
    }

    @Test
    void unknownPostcodeIsAClearError() {
        assertThatThrownBy(() -> builder.build("ep_x", new EvidencePackBuilder.Subject("M1 1AA", "FLAT", null)))
                .isInstanceOf(EvidencePackBuilder.UnknownPostcode.class);
    }
}
