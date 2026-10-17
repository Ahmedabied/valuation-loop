package uk.valuationloop.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import uk.valuationloop.outbox.UuidV7;

/**
 * One agent's lead for one request. Every change goes through {@link #apply}, so the row can never be in a state
 * the diagram in {@link LeadState} does not allow. @Version makes two workers touching the same lead a conflict
 * instead of a silent overwrite.
 */
@Entity
@Table(name = "agent_lead")
public class AgentLead {

    @Id
    private String id;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadState state;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "reply_due_at")
    private Instant replyDueAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(nullable = false)
    private boolean late;

    @Column(name = "reply_low")
    private BigDecimal replyLow;

    @Column(name = "reply_high")
    private BigDecimal replyHigh;

    @Column(name = "reply_message")
    private String replyMessage;

    @Column(name = "evidence_pack_id")
    private String evidencePackId;

    @Column(name = "visit_booked_at")
    private Instant visitBookedAt;

    @Column(name = "instructed_at")
    private Instant instructedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AgentLead() {} // JPA

    public static AgentLead create(String requestId, String agentId, Instant now) {
        AgentLead l = new AgentLead();
        l.id = UuidV7.prefixed("ld");
        l.requestId = requestId;
        l.agentId = agentId;
        l.state = LeadState.CREATED;
        l.createdAt = now;
        l.updatedAt = now;
        return l;
    }

    private void apply(LeadEvent event, Instant now) {
        this.state = LeadTransitions.next(this.state, event);
        this.updatedAt = now;
    }

    public void startDelivery(Instant now) {
        apply(new LeadEvent.Deliver(), now);
        this.deliveryAttempts++;
    }

    public void retryDelivery(Instant now) {
        apply(new LeadEvent.DeliveryFailed(false), now);
        this.deliveryAttempts++;
    }

    public void failDelivery(Instant now) {
        apply(new LeadEvent.DeliveryFailed(true), now);
    }

    public void delivered(Instant now, Duration replyWindow) {
        apply(new LeadEvent.DeliverySucceeded(), now);
        this.deliveredAt = now;
        this.replyDueAt = now.plus(replyWindow);
    }

    public void reply(BigDecimal low, BigDecimal high, String message, Instant now) {
        boolean wasExpired = this.state == LeadState.EXPIRED;
        apply(new LeadEvent.Replied(), now);
        this.repliedAt = now;
        this.late = wasExpired || (replyDueAt != null && now.isAfter(replyDueAt));
        this.replyLow = low;
        this.replyHigh = high;
        this.replyMessage = message;
    }

    public void attachEvidence(String packId, Instant now) {
        apply(new LeadEvent.EvidenceAdded(), now);
        this.evidencePackId = packId;
    }

    public void expire(Instant now) {
        apply(new LeadEvent.DeadlinePassed(), now);
    }

    public void visitBooked(Instant now) {
        apply(new LeadEvent.VisitBooked(), now);
        this.visitBookedAt = now;
    }

    /** Instruction is an outcome fact, not a state: it can arrive in any post-reply state. */
    public void instructed(Instant now) {
        this.instructedAt = now;
        this.updatedAt = now;
    }

    public boolean hasReplied() {
        return repliedAt != null;
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public LeadState getState() { return state; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getReplyDueAt() { return replyDueAt; }
    public Instant getRepliedAt() { return repliedAt; }
    public boolean isLate() { return late; }
    public BigDecimal getReplyLow() { return replyLow; }
    public BigDecimal getReplyHigh() { return replyHigh; }
    public String getReplyMessage() { return replyMessage; }
    public String getEvidencePackId() { return evidencePackId; }
    public Instant getVisitBookedAt() { return visitBookedAt; }
    public Instant getInstructedAt() { return instructedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
