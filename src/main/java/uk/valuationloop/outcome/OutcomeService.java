package uk.valuationloop.outcome;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;

/**
 * What happens after the reply: the agent books a valuation visit, and the agent's CRM later reports that the seller
 * instructed them. Both are facts on the lead, each written with its event in one transaction.
 */
@Service
public class OutcomeService {

    /** How the webhook call went: the first receipt records it, a repeat changes nothing. */
    public enum Receipt { RECORDED, ALREADY_RECORDED }

    private final AgentLeadRepository leads;
    private final OutboxWriter outbox;
    private final Clock clock;

    public OutcomeService(AgentLeadRepository leads, OutboxWriter outbox, Clock clock) {
        this.leads = leads;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * REPLIED or EVIDENCED -> VISIT_BOOKED, plus VisitBooked {visitAt}. Any other state (no reply yet, or already
     * booked) makes the state machine throw IllegalTransition, which the controller maps to 409.
     */
    @Transactional
    public AgentLead bookVisit(String agentId, String leadId, Instant visitAt) {
        AgentLead lead = leads.findById(leadId)
                .filter(l -> l.getAgentId().equals(agentId))
                .orElseThrow(() -> new LeadNotFound(leadId));   // someone else's lead is "not found", never "forbidden"
        lead.visitBooked(clock.instant());
        Map<String, Object> payload = new LinkedHashMap<>();   // Map.of() refuses a null value; visitAt is optional
        payload.put("visitAt", visitAt == null ? null : visitAt.toString());
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.VISIT_BOOKED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), payload));
        return lead;
    }

    /**
     * The simulated CRM webhook. Real CRM pushes echo the portal's lead reference back, so attribution is a primary-key
     * lookup on leadRef, not an address match (H2-review E2-6).
     *
     * Idempotent by the lead's own record: if instructed_at is already set, a repeat (CRMs retry webhooks) writes
     * nothing. Instruction is an outcome fact, not a state, so it is recorded whatever state the lead is in, even
     * before a reply (the seller may have phoned the agent directly); the scorecard decides what that means.
     */
    @Transactional
    public Receipt recordInstruction(String leadRef, String source, Instant recordedAt) {
        AgentLead lead = leads.findById(leadRef).orElseThrow(() -> new LeadNotFound(leadRef));
        if (lead.getInstructedAt() != null) {
            return Receipt.ALREADY_RECORDED;
        }
        lead.instructed(clock.instant());
        outbox.write(Topics.OUTCOME_RECORDED, lead.getRequestId(), EventEnvelope.of(EventTypes.INSTRUCTION_RECORDED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), Map.of(
                        "leadRef", leadRef,
                        "source", source,
                        "recordedAt", recordedAt.toString())));
        return Receipt.RECORDED;
    }

    /** 404 */
    public static class LeadNotFound extends RuntimeException {
        public LeadNotFound(String leadId) { super("no lead " + leadId); }
    }
}
