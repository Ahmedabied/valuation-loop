package uk.valuationloop.scorecard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per lead in the scorecard read model (V3__scorecard_fact.sql, plus the completion columns of V5). Built only from events, so it can be thrown
 * away and rebuilt from the topics at any time.
 *
 * Every method below is written so that the final row does not depend on the order events arrive in, and applying
 * the same event twice changes nothing (the replay test checks both):
 * <ul>
 *   <li>flags (evidence, engaged, selected, visit, instructed) only ever go from false to true;</li>
 *   <li>timestamps keep the earliest value seen, and last_event_at the latest;</li>
 *   <li>derived fields (reply_minutes, price_gap_pct) are recomputed from the stored inputs after every event.</li>
 * </ul>
 */
@Entity
@Table(name = "scorecard_fact")
public class ScorecardFact {

    @Id
    @Column(name = "lead_id")
    private String leadId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "reply_minutes")
    private Integer replyMinutes;

    @Column(name = "within_deadline")
    private Boolean withinDeadline;   // Boolean, not boolean: null means "not known yet" (no reply, not expired)

    @Column(name = "evidence_attached", nullable = false)
    private boolean evidenceAttached;

    @Column(name = "range_low")
    private BigDecimal rangeLow;

    @Column(name = "range_high")
    private BigDecimal rangeHigh;

    @Enumerated(EnumType.STRING)
    @Column(name = "range_rank")
    private RangeRank rangeRank;

    @Column(name = "seller_engaged", nullable = false)
    private boolean sellerEngaged;

    @Column(name = "seller_selected", nullable = false)
    private boolean sellerSelected;

    @Column(name = "visit_booked", nullable = false)
    private boolean visitBooked;

    @Column(nullable = false)
    private boolean instructed;

    @Column(name = "completed_price")
    private BigDecimal completedPrice;   // from CompletionMatched: the Land Registry price paid

    @Column(name = "completed_at")
    private LocalDate completedAt;       // the PPD date of transfer

    @Column(name = "price_gap_pct")
    private BigDecimal priceGapPct;      // derived: completed price vs the replied range's midpoint, in percent

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;   // wall clock of the last write; the only column a replay is allowed to change

    protected ScorecardFact() {} // JPA

    /** Any event may be the first one seen for a lead (out of order is normal), so any event can create the row. */
    public static ScorecardFact create(String leadId, String requestId, String agentId, Instant occurredAt) {
        ScorecardFact f = new ScorecardFact();
        f.leadId = leadId;
        f.requestId = requestId;
        f.agentId = agentId;
        f.lastEventAt = occurredAt;
        return f;
    }

    /** LeadDelivered: the reply clock started. A deliveredAt already taken from LeadReplied's payload wins. */
    void delivered(Instant occurredAt) {
        if (deliveredAt == null) {
            deliveredAt = occurredAt;
        }
    }

    /**
     * LeadReplied. The earliest reply wins (a duplicate is the same reply, so nothing changes). The payload's
     * deliveredAt is the lead's own recorded value, so it replaces the LeadDelivered timestamp when present.
     * within_deadline = !late: the lead decided "late" against its own reply_due_at when the reply came in.
     */
    void replied(Instant occurredAt, BigDecimal low, BigDecimal high, Boolean late, Instant payloadDeliveredAt) {
        if (repliedAt != null && !occurredAt.isBefore(repliedAt)) {
            return;
        }
        repliedAt = occurredAt;
        rangeLow = low;
        rangeHigh = high;
        if (late != null) {
            withinDeadline = !late;
        }
        if (payloadDeliveredAt != null) {
            deliveredAt = payloadDeliveredAt;
        }
    }

    /** LeadExpired: the window closed with no reply. A reply (even a late one) decides within_deadline instead. */
    void expired() {
        if (repliedAt == null) {
            withinDeadline = false;
        }
    }

    void evidenceAttached() { evidenceAttached = true; }
    void sellerEngaged() { sellerEngaged = true; }
    void sellerSelected() { sellerSelected = true; }
    void visitBooked() { visitBooked = true; }
    void instructed() { instructed = true; }

    void rank(RangeRank rank) { this.rangeRank = rank; }

    /**
     * CompletionMatched: the sale Land Registry recorded for this lead. A lead has one completion; should two ever
     * arrive, the earlier transfer wins (then the lower price), so the row is the same whichever came first.
     */
    void completed(BigDecimal price, LocalDate transferDate) {
        boolean replace = completedAt == null
                || transferDate.isBefore(completedAt)
                || (transferDate.isEqual(completedAt) && price.compareTo(completedPrice) < 0);
        if (replace) {
            completedPrice = price;
            completedAt = transferDate;
        }
    }

    /** Called after every event: derived fields and the bookkeeping columns. */
    void touched(Instant occurredAt, Instant now) {
        replyMinutes = (deliveredAt == null || repliedAt == null)
                ? null
                : (int) Math.max(0, Duration.between(deliveredAt, repliedAt).toMinutes());
        priceGapPct = priceGapPct(completedPrice, rangeLow, rangeHigh);
        if (occurredAt.isAfter(lastEventAt)) {
            lastEventAt = occurredAt;
        }
        updatedAt = now;
    }

    /**
     * (completed - midpoint) / midpoint * 100, rounded half-up to two decimals; null unless there is both a completion
     * and a replied range. Written with low + high (twice the midpoint) so no intermediate value is rounded:
     * (2 * completed - (low + high)) * 100 / (low + high). Negative: the sale completed below the range's midpoint.
     */
    static BigDecimal priceGapPct(BigDecimal completed, BigDecimal low, BigDecimal high) {
        if (completed == null || low == null || high == null) {
            return null;
        }
        BigDecimal twiceMid = low.add(high);
        if (twiceMid.signum() == 0) {
            return null;
        }
        return completed.multiply(BigDecimal.TWO).subtract(twiceMid).multiply(BigDecimal.valueOf(100))
                .divide(twiceMid, 2, RoundingMode.HALF_UP);
    }

    boolean hasRange() {
        return rangeLow != null && rangeHigh != null;
    }

    public String getLeadId() { return leadId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getRepliedAt() { return repliedAt; }
    public Integer getReplyMinutes() { return replyMinutes; }
    public Boolean getWithinDeadline() { return withinDeadline; }
    public boolean isEvidenceAttached() { return evidenceAttached; }
    public BigDecimal getRangeLow() { return rangeLow; }
    public BigDecimal getRangeHigh() { return rangeHigh; }
    public RangeRank getRangeRank() { return rangeRank; }
    public boolean isSellerEngaged() { return sellerEngaged; }
    public boolean isSellerSelected() { return sellerSelected; }
    public boolean isVisitBooked() { return visitBooked; }
    public boolean isInstructed() { return instructed; }
    public BigDecimal getCompletedPrice() { return completedPrice; }
    public LocalDate getCompletedAt() { return completedAt; }
    public BigDecimal getPriceGapPct() { return priceGapPct; }
    public Instant getLastEventAt() { return lastEventAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
