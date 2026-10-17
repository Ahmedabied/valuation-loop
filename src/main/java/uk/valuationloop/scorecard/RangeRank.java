package uk.valuationloop.scorecard;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where one reply's range sits among the replies to the same request, by midpoint. A fact about a range, used only
 * in an agent's own scorecard and in the product query; it never orders agents on any page.
 */
public enum RangeRank {
    HIGHEST, MIDDLE, LOWEST;

    /** A reply's range, keyed by its leadId. */
    public record Ranged(String leadId, BigDecimal low, BigDecimal high) {
        BigDecimal midpointTimesTwo() {
            return low.add(high);   // comparing low + high is comparing midpoints, without dividing
        }
    }

    /**
     * Pure function: the ranks for one request's replies. Fewer than two replies: nobody is ranked (empty map).
     * Otherwise the highest midpoint is HIGHEST, the lowest is LOWEST, everything between is MIDDLE; ties share a
     * rank (if every midpoint is equal, all are HIGHEST). With exactly two different ranges: HIGHEST and LOWEST.
     * The input order does not matter, which is what keeps the scorecard deterministic under replay.
     */
    public static Map<String, RangeRank> of(List<Ranged> replies) {
        Map<String, RangeRank> ranks = new HashMap<>();
        if (replies.size() < 2) {
            return ranks;
        }
        BigDecimal max = replies.stream().map(Ranged::midpointTimesTwo).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = replies.stream().map(Ranged::midpointTimesTwo).min(BigDecimal::compareTo).orElseThrow();
        for (Ranged r : replies) {
            BigDecimal m = r.midpointTimesTwo();
            ranks.put(r.leadId(), m.compareTo(max) == 0 ? HIGHEST : m.compareTo(min) == 0 ? LOWEST : MIDDLE);
        }
        return ranks;
    }
}
