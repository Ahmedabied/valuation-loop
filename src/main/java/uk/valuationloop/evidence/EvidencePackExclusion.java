package uk.valuationloop.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One comparable the agent removed while curating a pack (evidence_pack_exclusion, V2).
 * Agent-private: only the owning agent's API ever returns these, and nothing compares agents by them.
 *
 * The primary key is two columns, so JPA wants it as one embedded value ({@link Key}); a record works because
 * it already has the equals/hashCode a key needs.
 */
@Entity
@Table(name = "evidence_pack_exclusion")
public class EvidencePackExclusion {

    @Embeddable
    public record Key(@Column(name = "pack_id") String packId,
                      @Column(name = "ppd_transaction_id") String ppdTransactionId) {}

    @EmbeddedId
    private Key id;

    @Column(name = "excluded_at", nullable = false)
    private Instant excludedAt;

    protected EvidencePackExclusion() {} // JPA

    public EvidencePackExclusion(String packId, String ppdTransactionId, Instant excludedAt) {
        this.id = new Key(packId, ppdTransactionId);
        this.excludedAt = excludedAt;
    }

    public Key getId() { return id; }
    public Instant getExcludedAt() { return excludedAt; }
}
