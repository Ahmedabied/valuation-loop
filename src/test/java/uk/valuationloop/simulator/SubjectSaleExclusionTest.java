package uk.valuationloop.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.valuationloop.evidence.Comparable;
import uk.valuationloop.evidence.FakeEvidenceSources;

/**
 * The simulated agent's curation step: the subject's own sale (a demo artefact, because the ok request is taken from a
 * real sale) is the one removed when the pack holds it; otherwise the lowest-ranked comparable is.
 */
class SubjectSaleExclusionTest {

    private static List<Comparable> pack(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> Comparable.from(
                FakeEvidenceSources.sale(id, 600_000, 70.0, 10.0), FakeEvidenceSources.ORIGIN)).toList();
    }

    @Test
    void theSubjectsOwnSaleIsRemovedWhenThePackHoldsIt() {
        assertThat(SimulatorScenarios.comparableToExclude(pack("ppd-a", "ppd-subject", "ppd-b", "ppd-c"), "ppd-subject"))
                .isEqualTo("ppd-subject");
    }

    @Test
    void otherwiseTheLowestRankedComparableIsRemoved() {
        assertThat(SimulatorScenarios.comparableToExclude(pack("ppd-a", "ppd-b", "ppd-c"), "ppd-subject")).isEqualTo("ppd-c");
        assertThat(SimulatorScenarios.comparableToExclude(pack("ppd-a", "ppd-b", "ppd-c"), null)).isEqualTo("ppd-c");
    }
}
