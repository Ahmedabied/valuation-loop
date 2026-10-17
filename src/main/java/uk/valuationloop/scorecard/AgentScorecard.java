package uk.valuationloop.scorecard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What GET /agents/{agentId}/scorecard returns: this agent's own numbers, computed from its own facts only, next to a
 * static, clearly labelled fixture benchmark. Never another agent's id or numbers, never a position in a list.
 * Shares are 0..1 and null when there is nothing to divide by.
 *
 * completed / medianPriceGapPct: of this agent's own leads, how many have a Land Registry completion (the v2 late
 * match), and the median gap between the completed price and the midpoint of the range the agent replied with, in
 * percent (negative: sold below the midpoint). Private to the agent; never on the seller page, never compared.
 */
public record AgentScorecard(
        String agentId,
        int leads,
        int replied,
        Double medianReplyMinutes,
        Double withinDeadlineShare,
        Double evidenceAttachedShare,
        int sellerEngaged,
        int sellerSelected,
        int visitsBooked,
        int instructed,
        int completed,
        Double medianPriceGapPct,
        Benchmark benchmark) {

    /** Fixture values, not computed from anyone's data. The label says so on every response. */
    public record Benchmark(String label, double medianReplyMinutes, double withinDeadlineShare, double evidenceAttachedShare) {}

    public static final Benchmark FIXTURE_BENCHMARK = new Benchmark("fixture benchmark", 240, 0.8, 0.35);

    /** Pure function over one agent's facts (the caller has already filtered by agentId). */
    public static AgentScorecard of(String agentId, List<ScorecardFact> own) {
        List<ScorecardFact> repliedFacts = own.stream().filter(f -> f.getRepliedAt() != null).toList();
        List<Integer> minutes = own.stream().map(ScorecardFact::getReplyMinutes).filter(Objects::nonNull).sorted().toList();
        List<Boolean> deadline = own.stream().map(ScorecardFact::getWithinDeadline).filter(Objects::nonNull).toList();
        List<Double> gaps = own.stream().map(ScorecardFact::getPriceGapPct).filter(Objects::nonNull)
                .map(BigDecimal::doubleValue).sorted().toList();
        return new AgentScorecard(
                agentId,
                own.size(),
                repliedFacts.size(),
                median(minutes),
                share(deadline.stream().filter(b -> b).count(), deadline.size()),
                share(repliedFacts.stream().filter(ScorecardFact::isEvidenceAttached).count(), repliedFacts.size()),
                count(own, ScorecardFact::isSellerEngaged),
                count(own, ScorecardFact::isSellerSelected),
                count(own, ScorecardFact::isVisitBooked),
                count(own, ScorecardFact::isInstructed),
                count(own, f -> f.getCompletedPrice() != null),
                medianOf(gaps),
                FIXTURE_BENCHMARK);
    }

    private static Double median(List<Integer> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Median of sorted values, rounded to two decimals (the gaps are percentages with two decimals). */
    private static Double medianOf(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int n = sorted.size();
        double m = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return Math.round(m * 100) / 100.0;
    }

    private static Double share(long part, long whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;   // three decimal places
    }

    private static int count(List<ScorecardFact> facts, java.util.function.Predicate<ScorecardFact> p) {
        return (int) facts.stream().filter(p).count();
    }
}
