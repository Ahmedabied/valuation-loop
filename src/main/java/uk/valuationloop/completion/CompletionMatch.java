package uk.valuationloop.completion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of completion_match (V5): the Land Registry sale matched to an instructed lead. lead_id is the primary key,
 * so a lead has at most one completion and a second insert for it fails instead of duplicating.
 */
@Entity
@Table(name = "completion_match")
public class CompletionMatch {

    @Id
    @Column(name = "lead_id")
    private String leadId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "ppd_transaction_id", nullable = false)
    private String ppdTransactionId;

    @Column(name = "completed_price", nullable = false)
    private BigDecimal completedPrice;

    @Column(name = "completed_at", nullable = false)
    private LocalDate completedAt;

    @Column(name = "matched_at", nullable = false)
    private Instant matchedAt;

    @Column(name = "match_rule", nullable = false)
    private String matchRule;

    protected CompletionMatch() {} // JPA

    public CompletionMatch(String leadId, String requestId, String agentId, String ppdTransactionId,
                           BigDecimal completedPrice, LocalDate completedAt, Instant matchedAt, String matchRule) {
        this.leadId = leadId;
        this.requestId = requestId;
        this.agentId = agentId;
        this.ppdTransactionId = ppdTransactionId;
        this.completedPrice = completedPrice;
        this.completedAt = completedAt;
        this.matchedAt = matchedAt;
        this.matchRule = matchRule;
    }

    public String getLeadId() { return leadId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public String getPpdTransactionId() { return ppdTransactionId; }
    public BigDecimal getCompletedPrice() { return completedPrice; }
    public LocalDate getCompletedAt() { return completedAt; }
    public Instant getMatchedAt() { return matchedAt; }
    public String getMatchRule() { return matchRule; }
}
