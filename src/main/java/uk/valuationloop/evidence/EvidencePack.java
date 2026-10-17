package uk.valuationloop.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of evidence_pack (V2). pack_json is the pack exactly as served; the comparables_* columns copy the range
 * out of it so the comparison module's SQL join can read numbers without parsing JSON.
 * attached_at stays null until the agent attaches the pack to a reply; after that the row never changes.
 */
@Entity
@Table(name = "evidence_pack")
public class EvidencePack {

    @Id
    private String id;

    @Column(name = "lead_id", nullable = false)
    private String leadId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "pack_json", nullable = false, length = 100000)
    private String packJson;

    @Column(name = "comparables_count", nullable = false)
    private int comparablesCount;

    @Column(name = "comparables_low")
    private BigDecimal comparablesLow;

    @Column(name = "comparables_mid")
    private BigDecimal comparablesMid;

    @Column(name = "comparables_high")
    private BigDecimal comparablesHigh;

    @Column(name = "data_vintage", nullable = false)
    private String dataVintage;

    @Column(name = "attached_at")
    private Instant attachedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected EvidencePack() {} // JPA

    public static EvidencePack create(String leadId, String requestId, String agentId, Pack pack, String packJson,
                                      Instant now) {
        EvidencePack p = new EvidencePack();
        p.id = pack.packId();
        p.leadId = leadId;
        p.requestId = requestId;
        p.agentId = agentId;
        p.createdAt = now;
        p.replaceContents(pack, packJson, now);
        return p;
    }

    /** After a curation step: the new served JSON and the numbers copied out of it. */
    public void replaceContents(Pack pack, String packJson, Instant now) {
        if (attachedAt != null) {
            throw new IllegalStateException("pack " + id + " is attached and can no longer change");
        }
        this.packJson = packJson;
        this.comparablesCount = pack.comparables().size();
        ComparablesRange r = pack.comparablesRange();
        this.comparablesLow = r == null ? null : BigDecimal.valueOf(r.low());
        this.comparablesMid = r == null ? null : BigDecimal.valueOf(r.mid());
        this.comparablesHigh = r == null ? null : BigDecimal.valueOf(r.high());
        this.dataVintage = pack.dataVintage();
        this.updatedAt = now;
    }

    public void attach(Instant now) {
        if (attachedAt != null) {
            throw new IllegalStateException("pack " + id + " is already attached");
        }
        this.attachedAt = now;
        this.updatedAt = now;
    }

    public boolean isAttached() { return attachedAt != null; }

    public String getId() { return id; }
    public String getLeadId() { return leadId; }
    public String getRequestId() { return requestId; }
    public String getAgentId() { return agentId; }
    public String getPackJson() { return packJson; }
    public int getComparablesCount() { return comparablesCount; }
    public BigDecimal getComparablesLow() { return comparablesLow; }
    public BigDecimal getComparablesMid() { return comparablesMid; }
    public BigDecimal getComparablesHigh() { return comparablesHigh; }
    public String getDataVintage() { return dataVintage; }
    public Instant getAttachedAt() { return attachedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
