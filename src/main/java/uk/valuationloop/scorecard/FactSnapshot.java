package uk.valuationloop.scorecard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One scorecard_fact row as plain values, for "is the rebuilt table identical?" checks. Records get equals() for
 * free, field by field, so comparing two snapshots is comparing two lists. updated_at is left out on purpose: it is
 * the wall-clock time of the write, the one column a replay is expected to change.
 */
public record FactSnapshot(
        String leadId,
        String requestId,
        String agentId,
        String deliveredAt,
        String repliedAt,
        Integer replyMinutes,
        Boolean withinDeadline,
        boolean evidenceAttached,
        Long rangeLow,
        Long rangeHigh,
        String rangeRank,
        boolean sellerEngaged,
        boolean sellerSelected,
        boolean visitBooked,
        boolean instructed,
        Long completedPrice,
        String completedAt,
        String priceGapPct,
        String lastEventAt) {

    public static FactSnapshot of(ScorecardFact f) {
        return new FactSnapshot(f.getLeadId(), f.getRequestId(), f.getAgentId(), text(f.getDeliveredAt()),
                text(f.getRepliedAt()), f.getReplyMinutes(), f.getWithinDeadline(), f.isEvidenceAttached(),
                whole(f.getRangeLow()), whole(f.getRangeHigh()),
                f.getRangeRank() == null ? null : f.getRangeRank().name(),
                f.isSellerEngaged(), f.isSellerSelected(), f.isVisitBooked(), f.isInstructed(), whole(f.getCompletedPrice()),
                f.getCompletedAt() == null ? null : f.getCompletedAt().toString(),
                f.getPriceGapPct() == null ? null : f.getPriceGapPct().toPlainString(),   // text: "2.12" == "2.12" whatever the scale bookkeeping
                text(f.getLastEventAt()));
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }

    private static Long whole(BigDecimal v) {
        return v == null ? null : v.longValueExact();   // numeric(12,0): whole pounds
    }
}
