package uk.valuationloop.request;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadTransitions;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;
import uk.valuationloop.request.RequestTransitions.RequestEvent;

/**
 * Closes a request once nothing can ever come back to the seller: every lead is terminal for delivery (REPLIED,
 * EVIDENCED, VISIT_BOOKED, EXPIRED or DELIVERY_FAILED) AND no lead has replied. It emits RequestClosed {reason} on
 * valuation.requested.v1 in the same transaction.
 *
 * If any agent replied, the request stays REPLIES_IN: the seller still has a reply to compare and choose from, and
 * closing on the seller's selection is the comparison module's job, not this class's.
 */
@Component
public class RequestCloser {

    public static final String ALL_LEADS_TERMINAL = "ALL_LEADS_TERMINAL";

    private final ValuationRequestRepository requests;
    private final AgentLeadRepository leads;
    private final OutboxWriter outbox;
    private final Clock clock;

    public RequestCloser(ValuationRequestRepository requests, AgentLeadRepository leads, OutboxWriter outbox, Clock clock) {
        this.requests = requests;
        this.leads = leads;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Returns true when this call closed the request; false when it was already closed, a lead is still open, or a lead replied. */
    @Transactional
    public boolean closeIfAllLeadsTerminal(String requestId, String reason) {
        ValuationRequest request = requests.findById(requestId).orElse(null);
        if (request == null) {
            return false;
        }
        RequestState state = request.getState();
        if (state != RequestState.AWAITING_REPLIES && state != RequestState.REPLIES_IN) {
            return false;   // CLOSED already (or not yet dispatched): nothing to do, and no second RequestClosed
        }
        List<AgentLead> all = leads.findByRequestIdOrderByCreatedAt(requestId);
        if (all.isEmpty() || !all.stream().allMatch(l -> LeadTransitions.isTerminalForDelivery(l.getState()))) {
            return false;
        }
        if (all.stream().anyMatch(AgentLead::hasReplied)) {
            return false;   // a reply is waiting for the seller; the seller's selection closes this request
        }
        request.apply(new RequestEvent.AllLeadsTerminal(), clock.instant());
        outbox.write(Topics.VALUATION_REQUESTED, requestId, EventEnvelope.of(EventTypes.REQUEST_CLOSED,
                requestId, null, null, Map.of("reason", reason)));
        return true;
    }
}
