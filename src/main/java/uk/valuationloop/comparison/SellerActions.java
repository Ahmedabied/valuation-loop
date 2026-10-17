package uk.valuationloop.comparison;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.RequestState;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;

/**
 * What the seller does on the comparison page: engage with a reply (viewed, contacted, asked for a visit) or select
 * an agent. Each is one transaction: the state change (if any) and its events go to the outbox together.
 *
 * Both only work on leads that replied: the seller can only act on a reply they were shown.
 */
@Service
public class SellerActions {

    public static final String SELLER_SELECTED = "SELLER_SELECTED";

    public enum EngagementAction { VIEWED, CONTACTED, VISIT_REQUESTED }

    private final ValuationRequestRepository requests;
    private final AgentLeadRepository leads;
    private final OutboxWriter outbox;
    private final Clock clock;

    public SellerActions(ValuationRequestRepository requests, AgentLeadRepository leads, OutboxWriter outbox, Clock clock) {
        this.requests = requests;
        this.leads = leads;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** SellerEngaged on lead.events.v1. No state changes: engagement is a fact for the scorecard, not a step. */
    @Transactional
    public void engage(String requestId, String leadId, EngagementAction action) {
        requests.findById(requestId).orElseThrow(() -> new NotFound("no request " + requestId));
        AgentLead lead = repliedLeadOf(requestId, leadId);
        outbox.write(Topics.LEAD_EVENTS, requestId, EventEnvelope.of(EventTypes.SELLER_ENGAGED,
                requestId, lead.getId(), lead.getAgentId(), Map.of("action", action.name())));
    }

    /**
     * The seller chooses: REPLIES_IN -> CLOSED with the chosen lead, then SellerSelected and RequestClosed
     * {SELLER_SELECTED}, all in one commit. A second selection finds the request CLOSED and is a 409.
     */
    @Transactional
    public ValuationRequest select(String requestId, String leadId) {
        ValuationRequest request = requests.findById(requestId).orElseThrow(() -> new NotFound("no request " + requestId));
        AgentLead lead = repliedLeadOf(requestId, leadId);
        if (request.getState() != RequestState.REPLIES_IN) {
            throw new Conflict("request " + requestId + " is " + request.getState() + "; a selection needs REPLIES_IN");
        }
        Instant now = clock.instant();
        // loaded inside this transaction, so the entity is managed: JPA writes the change at commit (no save needed)
        request.select(lead.getId(), now);
        outbox.write(Topics.LEAD_EVENTS, requestId, EventEnvelope.of(EventTypes.SELLER_SELECTED,
                requestId, lead.getId(), lead.getAgentId(), Map.of()));
        outbox.write(Topics.VALUATION_REQUESTED, requestId, EventEnvelope.of(EventTypes.REQUEST_CLOSED,
                requestId, null, null, Map.of("reason", SELLER_SELECTED)));
        return request;
    }

    /** The lead must belong to this request (else 404) and must have replied (else 409). */
    private AgentLead repliedLeadOf(String requestId, String leadId) {
        AgentLead lead = leads.findById(leadId)
                .filter(l -> l.getRequestId().equals(requestId))
                .orElseThrow(() -> new NotFound("no lead " + leadId + " in request " + requestId));
        if (!lead.hasReplied()) {
            throw new Conflict("lead " + leadId + " has no reply to act on");
        }
        return lead;
    }

    /** 404 */
    public static class NotFound extends RuntimeException {
        public NotFound(String why) { super(why); }
    }

    /** 409 */
    public static class Conflict extends RuntimeException {
        public Conflict(String why) { super(why); }
    }
}
