package uk.valuationloop.scorecard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * One row per lead in the scorecard read model (V3__scorecard_fact.sql). Built only from events, so it can be thrown
 * away and rebuilt from the topics at any time.
 *
 * Every method below is written so that the final row does not depend on the order events arrive in, and applying
 * the same event twice changes nothing (the replay test checks both):
 * <ul>
 *   <li>flags (evidence, engaged, selected, visit, instructed) only ever go from false to true;</li>
 *   <li>timestamps keep the earliest value seen, and last_event_at the latest;</li>
 *   <li>derived fields (reply_minutes) are recomputed from the stored inputs after every event.</li>
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

    /** Called after every event: derived fields and the bookkeeping columns. */
    void touched(Instant occurredAt, Instant now) {
        replyMinutes = (deliveredAt == null || repliedAt == null)
                ? null
                : (int) Math.max(0, Duration.between(deliveredAt, repliedAt).toMinutes());
        if (occurredAt.isAfter(lastEventAt)) {
            lastEventAt = occurredAt;
        }
        updatedAt = now;
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
    public Instant getLastEventAt() { return lastEventAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
