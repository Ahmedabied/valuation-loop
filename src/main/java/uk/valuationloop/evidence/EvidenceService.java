package uk.valuationloop.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.outbox.UuidV7;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;

/**
 * The agent's evidence workflow: build a pack, curate it (remove comparables), attach it to the reply.
 *
 * Every method first checks the agent owns the lead (and the pack belongs to that lead); otherwise it is
 * {@link NotFound}, so one agent cannot even learn that another agent's pack exists.
 * Attach is the one step other modules hear about: the pack, the lead's state change and the EvidenceAttached
 * event commit in ONE transaction through the outbox, so "evidence attached" is never half-true.
 */
@Service
public class EvidenceService {

    /** What the owning agent sees: the pack as served, plus its own private curation record. */
    public record AgentPackView(String packId, String leadId, Instant attachedAt, Pack pack,
                                List<String> excludedPpdTransactionIds) {}

    private final AgentLeadRepository leads;
    private final ValuationRequestRepository requests;
    private final EvidencePackRepository packs;
    private final EvidencePackExclusionRepository exclusions;
    private final EvidencePackBuilder builder;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public EvidenceService(AgentLeadRepository leads, ValuationRequestRepository requests,
                           EvidencePackRepository packs, EvidencePackExclusionRepository exclusions,
                           EvidencePackBuilder builder, OutboxWriter outbox, ObjectMapper json, Clock clock) {
        this.leads = leads;
        this.requests = requests;
        this.packs = packs;
        this.exclusions = exclusions;
        this.builder = builder;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Builds a pack from live Elasticsearch. Deliberately NOT one big transaction: the search runs first, then a
     * single insert, so no database connection is held open while Elasticsearch answers.
     */
    public AgentPackView build(String agentId, String leadId) {
        AgentLead lead = ownedLead(agentId, leadId);
        ValuationRequest request = requests.findById(lead.getRequestId())
                .orElseThrow(() -> new NotFound("no request for lead " + leadId));
        Pack pack = builder.build(UuidV7.prefixed("ep"), new EvidencePackBuilder.Subject(
                request.getPostcode(), request.getPropertyType().name(), request.getFloorAreaM2()));
        EvidencePack row = packs.save(EvidencePack.create(lead.getId(), lead.getRequestId(), agentId, pack,
                toJson(pack), clock.instant()));
        return view(row, pack);
    }

    @Transactional(readOnly = true)
    public AgentPackView get(String agentId, String leadId, String packId) {
        EvidencePack row = ownedPack(agentId, leadId, packId);
        return view(row, fromJson(row.getPackJson()));
    }

    /** Removes one comparable, records the exclusion privately, recomputes the range. 409 once attached. */
    @Transactional
    public AgentPackView exclude(String agentId, String leadId, String packId, String ppdTransactionId) {
        EvidencePack row = ownedPack(agentId, leadId, packId);
        if (row.isAttached()) {
            throw new Conflict("pack " + packId + " is attached; it can no longer be curated");
        }
        Pack pack = fromJson(row.getPackJson());
        boolean present = pack.comparables().stream().anyMatch(c -> c.ppdTransactionId().equals(ppdTransactionId));
        if (!present) {
            throw new NotFound("comparable " + ppdTransactionId + " is not in pack " + packId);
        }
        Instant now = clock.instant();
        Pack curated = builder.without(pack, ppdTransactionId);
        exclusions.save(new EvidencePackExclusion(packId, ppdTransactionId, now));
        row.replaceContents(curated, toJson(curated), now);
        return view(row, curated);
    }

    /**
     * Attaches the pack to the agent's reply. Requires the lead to be REPLIED: a pack can only travel with a reply,
     * and a second attach finds the lead already EVIDENCED, so it is a 409 like any other wrong state.
     * A pack with no range (fewer than 3 comparables) cannot be attached: the EvidenceAttached contract requires a
     * comparablesRange, and the product never shows a range that the evidence does not support.
     */
    @Transactional
    public AgentPackView attach(String agentId, String leadId, String packId) {
        EvidencePack row = ownedPack(agentId, leadId, packId);
        AgentLead lead = ownedLead(agentId, leadId);
        if (row.isAttached()) {
            throw new Conflict("pack " + packId + " is already attached");
        }
        if (lead.getState() != LeadState.REPLIED) {
            throw new Conflict("lead " + leadId + " is " + lead.getState() + "; evidence attaches to a reply (REPLIED) once");
        }
        Pack pack = fromJson(row.getPackJson());
        ComparablesRange range = pack.comparablesRange();
        if (range == null) {
            throw new Conflict("pack " + packId + " has " + Pack.NO_RANGE_NOTE + "; build or curate a pack with at least "
                    + ComparablesRange.MINIMUM_COMPARABLES);
        }
        Instant now = clock.instant();
        row.attach(now);
        lead.attachEvidence(packId, now);  // REPLIED -> EVIDENCED, through the lead's own state table

        Map<String, Object> rangeJson = new LinkedHashMap<>();
        rangeJson.put("low", range.low());
        rangeJson.put("mid", range.mid());
        rangeJson.put("high", range.high());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packId", packId);
        payload.put("comparablesCount", pack.comparables().size());
        payload.put("comparablesRange", rangeJson);
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.EVIDENCE_ATTACHED,
                lead.getRequestId(), lead.getId(), agentId, payload));
        return view(row, pack);
    }

    private AgentLead ownedLead(String agentId, String leadId) {
        return leads.findById(leadId)
                .filter(l -> l.getAgentId().equals(agentId))
                .orElseThrow(() -> new NotFound("no lead " + leadId + " for agent " + agentId));
    }

    private EvidencePack ownedPack(String agentId, String leadId, String packId) {
        ownedLead(agentId, leadId);
        return packs.findById(packId)
                .filter(p -> p.getLeadId().equals(leadId) && p.getAgentId().equals(agentId))
                .orElseThrow(() -> new NotFound("no pack " + packId + " on lead " + leadId));
    }

    private AgentPackView view(EvidencePack row, Pack pack) {
        return new AgentPackView(row.getId(), row.getLeadId(), row.getAttachedAt(), pack,
                exclusions.excludedIds(row.getId()));
    }

    private String toJson(Pack pack) {
        try {
            return json.writeValueAsString(pack);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("pack is not serialisable", e);
        }
    }

    private Pack fromJson(String packJson) {
        try {
            return json.readValue(packJson, Pack.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored pack_json does not parse", e);
        }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
