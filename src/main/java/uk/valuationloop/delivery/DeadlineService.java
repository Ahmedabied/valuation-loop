package uk.valuationloop.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;

/**
 * The transactional half of the deadline scan. One small transaction per lead, so a reply racing the scanner on one
 * lead (an optimistic-lock conflict) never rolls back the expiry of the others.
 */
@Service
public class DeadlineService {

    private final AgentLeadRepository leads;
    private final OutboxWriter outbox;

    public DeadlineService(AgentLeadRepository leads, OutboxWriter outbox) {
        this.leads = leads;
        this.outbox = outbox;
    }

    /** Delivered leads whose reply window closed before {@code now}. */
    @Transactional(readOnly = true)
    public List<String> pastDueLeadIds(Instant now) {
        return leads.findByStateAndReplyDueAtBefore(LeadState.DELIVERED, now).stream().map(AgentLead::getId).toList();
    }

    /**
     * DELIVERED -> EXPIRED plus LeadExpired, if the lead is still unanswered and still past due when re-read inside
     * this transaction (the agent may have replied a millisecond ago). Returns the requestId when it expired the lead.
     * LeadExpired is an internal fact: nothing seller-facing ever reads it.
     */
    @Transactional
    public Optional<String> expire(String leadId, Instant now) {
        AgentLead lead = leads.findById(leadId).orElse(null);
        if (lead == null || lead.getState() != LeadState.DELIVERED || !lead.getReplyDueAt().isBefore(now)) {
            return Optional.empty();
        }
        lead.expire(now);
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.LEAD_EXPIRED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), Map.of(
                        "replyDueAt", lead.getReplyDueAt().toString())));
        return Optional.of(lead.getRequestId());
    }
}
