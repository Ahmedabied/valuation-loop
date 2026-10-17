package uk.valuationloop.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RangeTest {

    @Test
    void quartilesInterpolateBetweenSortedPrices() {
        // sorted 100k 200k 300k 400k: positions 0.75, 1.5, 2.25 of (n - 1) = 3
        ComparablesRange r = ComparablesRange.of(List.of(400_000L, 100_000L, 300_000L, 200_000L));
        assertThat(r).isEqualTo(new ComparablesRange(175_000, 250_000, 325_000));
        assertThat(r.relativeWidth()).isEqualTo(0.6);
    }

    @Test
    void threeComparablesIsTheMinimum() {
        assertThat(ComparablesRange.of(List.of(500_000L, 600_000L, 700_000L)))
                .isEqualTo(new ComparablesRange(550_000, 600_000, 650_000));
        assertThat(ComparablesRange.of(List.of(500_000L, 600_000L))).isNull();
        assertThat(ComparablesRange.of(List.of())).isNull();
    }

    @Test
    void roundsToWholePounds() {
        assertThat(ComparablesRange.of(List.of(100_001L, 100_002L, 100_004L)))
                .isEqualTo(new ComparablesRange(100_002, 100_002, 100_003)); // 100001.5 -> 100002, 100003 -> 100003
    }
}
