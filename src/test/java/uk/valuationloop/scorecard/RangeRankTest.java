package uk.valuationloop.scorecard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.valuationloop.scorecard.RangeRank.Ranged;

class RangeRankTest {

    static Ranged r(String id, long low, long high) {
        return new Ranged(id, BigDecimal.valueOf(low), BigDecimal.valueOf(high));
    }

    @Test
    void fewerThanTwoRepliesAreUnranked() {
        assertThat(RangeRank.of(List.of())).isEmpty();
        assertThat(RangeRank.of(List.of(r("a", 1, 2)))).isEmpty();
    }

    @Test
    void twoRepliesAreHighestAndLowest() {
        assertThat(RangeRank.of(List.of(r("a", 100, 200), r("b", 300, 400))))
                .containsEntry("a", RangeRank.LOWEST).containsEntry("b", RangeRank.HIGHEST);
    }

    @Test
    void midpointDecidesAndTiesShareARankWhateverTheInputOrder() {
        var forward = RangeRank.of(List.of(r("a", 100, 300), r("b", 150, 250), r("c", 500, 600), r("d", 0, 10)));
        var backward = RangeRank.of(List.of(r("d", 0, 10), r("c", 500, 600), r("b", 150, 250), r("a", 100, 300)));
        assertThat(forward).isEqualTo(backward)
                .containsEntry("a", RangeRank.MIDDLE).containsEntry("b", RangeRank.MIDDLE)   // same midpoint 200
                .containsEntry("c", RangeRank.HIGHEST).containsEntry("d", RangeRank.LOWEST);
        assertThat(RangeRank.of(List.of(r("a", 1, 3), r("b", 2, 2)))).containsEntry("a", RangeRank.HIGHEST)
                .containsEntry("b", RangeRank.HIGHEST);
    }
}
