package uk.valuationloop.response;

import java.math.BigDecimal;
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
import uk.valuationloop.request.RequestState;
import uk.valuationloop.request.RequestTransitions.RequestEvent;
import uk.valuationloop.request.ValuationRequest;
import uk.valuationloop.request.ValuationRequestRepository;

/**
 * The agent's side of a lead: reply with a range, see the seller's contact once replied, list own leads.
 * Every method looks the lead up by (agentId, leadId): a lead that belongs to someone else is "not found",
 * never "forbidden", so an agent cannot even learn that another agent's lead exists.
 */
@Service
public class ReplyService {

    /** The reply body. low/high in whole pounds. */
    public record Reply(BigDecimal low, BigDecimal high, String message) {}

    /** What the agent gets once it has replied. sellerRef is pseudonymous; the contact is simulated. */
    public record ContactRelease(String sellerRef, Contact contact) {}

    public record Contact(String email, String phone) {}

    private static final BigDecimal MAX_PRICE = new BigDecimal("999999999999"); // numeric(12,0) in agent_lead
    private static final int MAX_MESSAGE = 2000;

    private final AgentLeadRepository leads;
    private final ValuationRequestRepository requests;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ReplyService(AgentLeadRepository leads, ValuationRequestRepository requests, OutboxWriter outbox, Clock clock) {
        this.leads = leads;
        this.requests = requests;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * One transaction: the lead records the reply, LeadReplied goes to the outbox, and the request moves to
     * REPLIES_IN. A reply after EXPIRED is accepted and marked late (the agent's own record shows it; the seller
     * never sees "late"). A reply after the request already closed is still recorded on the lead; the request stays
     * closed.
     */
    @Transactional
    public AgentLead reply(String agentId, String leadId, Reply reply) {
        validate(reply);
        AgentLead lead = ownLead(agentId, leadId);
        if (lead.hasReplied()) {
            throw new AlreadyReplied(leadId);
        }
        if (lead.getState() != LeadState.DELIVERED && lead.getState() != LeadState.EXPIRED) {
            throw new NotReplyable(leadId, lead.getState());
        }
        Instant now = clock.instant();
        lead.reply(reply.low(), reply.high(), reply.message(), now);

        Map<String, Object> payload = new LinkedHashMap<>(); // Map.of() refuses null values; message and deliveredAt may be null
        payload.put("low", reply.low());
        payload.put("high", reply.high());
        payload.put("message", reply.message());
        payload.put("late", lead.isLate());
        payload.put("deliveredAt", lead.getDeliveredAt() == null ? null : lead.getDeliveredAt().toString());
        payload.put("repliedAt", lead.getRepliedAt().toString());   // the business time; occurredAt is when the event was written
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.LEAD_REPLIED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), payload));

        ValuationRequest request = requests.findById(lead.getRequestId()).orElseThrow();
        if (request.getState() == RequestState.AWAITING_REPLIES) {
            request.apply(new RequestEvent.FirstReply(), now);   // later replies leave REPLIES_IN as it is
        }
        return lead;
    }

    /**
     * The one public valuation-request rule (ARCHITECTURE.md §3): the seller's contact details are released only after the agent
     * has replied. This system holds no seller PII at all (sellerRef is pseudonymous), so the contact returned is a
     * simulated, non-routable address built from the sellerRef; phone is always null.
     */
    @Transactional(readOnly = true)
    public ContactRelease contact(String agentId, String leadId) {
        AgentLead lead = ownLead(agentId, leadId);
        if (!lead.hasReplied()) {
            throw new ContactLocked();
        }
        String sellerRef = requests.findById(lead.getRequestId()).orElseThrow().getSellerRef();
        return new ContactRelease(sellerRef, new Contact("seller+" + sellerRef + "@example.invalid", null));
    }

    /** The agent's own leads only. */
    @Transactional(readOnly = true)
    public List<AgentLead> leadsOf(String agentId) {
        return leads.findByAgentIdOrderByCreatedAt(agentId);
    }

    private AgentLead ownLead(String agentId, String leadId) {
        return leads.findById(leadId)
                .filter(l -> l.getAgentId().equals(agentId))
                .orElseThrow(() -> new LeadNotFound(leadId));
    }

    private static void validate(Reply r) {
        if (r == null || r.low() == null || r.high() == null) {
            throw new InvalidReply("low and high are required");
        }
        if (r.low().signum() <= 0 || r.high().signum() <= 0) {
            throw new InvalidReply("low and high must be greater than zero");
        }
        if (r.low().compareTo(r.high()) > 0) {
            throw new InvalidReply("low must not be greater than high");
        }
        if (r.low().stripTrailingZeros().scale() > 0 || r.high().stripTrailingZeros().scale() > 0) {
            throw new InvalidReply("low and high are whole pounds");
        }
        if (r.high().compareTo(MAX_PRICE) > 0) {
            throw new InvalidReply("high is too large");
        }
        if (r.message() != null && r.message().length() > MAX_MESSAGE) {
            throw new InvalidReply("message is longer than " + MAX_MESSAGE + " characters");
        }
    }

    // The controller maps each of these to one HTTP status. Plain exceptions, no framework types, so the service
    // reads the same whether it is called from HTTP, a test or a script.

    /** 404 */
    public static class LeadNotFound extends RuntimeException {
        public LeadNotFound(String leadId) { super("no lead " + leadId + " for this agent"); }
    }

    /** 409 */
    public static class AlreadyReplied extends RuntimeException {
        public AlreadyReplied(String leadId) { super("lead " + leadId + " already has a reply"); }
    }

    /** 409 */
    public static class NotReplyable extends RuntimeException {
        public NotReplyable(String leadId, LeadState state) { super("lead " + leadId + " is " + state + " and cannot take a reply"); }
    }

    /** 422 */
    public static class InvalidReply extends RuntimeException {
        public InvalidReply(String why) { super(why); }
    }

    /** 423 */
    public static class ContactLocked extends RuntimeException {
        public ContactLocked() { super("contact is released after you reply"); }
    }
}
