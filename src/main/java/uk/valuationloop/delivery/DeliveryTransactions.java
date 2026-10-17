package uk.valuationloop.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.valuationloop.lead.AgentLead;
import uk.valuationloop.lead.AgentLeadRepository;
import uk.valuationloop.lead.LeadState;
import uk.valuationloop.lead.LeadTransitions;
import uk.valuationloop.outbox.EventEnvelope;
import uk.valuationloop.outbox.EventTypes;
import uk.valuationloop.outbox.OutboxWriter;
import uk.valuationloop.outbox.Topics;

/**
 * The database half of a delivery, as three short transactions. Each one loads the lead, checks its state, changes
 * it, and writes the matching event to the outbox, all in one commit.
 *
 * Why three and not one: the adapter call is a network call to a third party (seconds, maybe a timeout). Holding a
 * database transaction open across it would pin a connection and a row lock for that long, and a failed call would
 * roll back the attempt counter, so "3 attempts" could never be recorded. So: commit "attempt n started", call the
 * adapter with no transaction open, then commit "delivered" (or, from the DLT, "failed").
 *
 * No explicit save(): an entity loaded inside a @Transactional method is "managed", and JPA writes its changes at
 * commit (dirty checking), the way a Django model would if every field assignment queued an UPDATE.
 */
@Component
public class DeliveryTransactions {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTransactions.class);

    /** What beginAttempt decided. A sealed interface: the caller's switch must handle both cases. */
    public sealed interface Begin {
        record Attempt(int number) implements Begin {}
        record Skip(String why) implements Begin {}
    }

    private final AgentLeadRepository leads;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration replyWindow;

    public DeliveryTransactions(AgentLeadRepository leads, OutboxWriter outbox, Clock clock,
                                @Value("${valuationloop.reply-window-hours:24}") long replyWindowHours) {
        this.leads = leads;
        this.outbox = outbox;
        this.clock = clock;
        this.replyWindow = Duration.ofHours(replyWindowHours);
    }

    /**
     * The idempotency check plus "attempt n started". A lead that is DELIVERED or beyond is never delivered again,
     * whichever copy of the command this is: that is what makes a duplicate DeliverLead harmless.
     */
    @Transactional
    public Begin beginAttempt(DeliverLead cmd) {
        Optional<AgentLead> found = leads.findById(cmd.leadId());
        if (found.isEmpty()) {
            return new Begin.Skip("no such lead");   // the outbox only sends committed rows, so this never heals
        }
        AgentLead lead = found.get();
        LeadState state = lead.getState();
        if (state == LeadState.DELIVERED || LeadTransitions.isTerminalForDelivery(state)) {
            return new Begin.Skip("lead is already " + state);
        }
        Instant now = clock.instant();
        if (state == LeadState.CREATED) {
            lead.startDelivery(now);   // CREATED -> DELIVERING, attempts = 1
        } else {
            lead.retryDelivery(now);   // DELIVERING -> DELIVERING, attempts + 1 (a retry topic, or a redelivery after a crash)
        }
        return new Begin.Attempt(lead.getDeliveryAttempts());
    }

    /** The adapter accepted: DELIVERING -> DELIVERED, reply clock starts, LeadDelivered goes to the outbox. */
    @Transactional
    public void recordDelivered(DeliverLead cmd, int attempt) {
        AgentLead lead = leads.findById(cmd.leadId()).orElseThrow();
        if (lead.getState() != LeadState.DELIVERING) {
            log.info("lead {} is {} by the time its delivery returned; nothing to record", lead.getId(), lead.getState());
            return;
        }
        lead.delivered(clock.instant(), replyWindow);
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.LEAD_DELIVERED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), Map.of(
                        "adapter", cmd.adapter(),
                        "attempt", attempt)));
    }

    /**
     * Every attempt failed (called from the DLT handler): DELIVERING -> DELIVERY_FAILED and LeadDeliveryFailed.
     * That event is agent-private: it feeds the agent's own record and is never shown to the seller.
     */
    @Transactional
    public boolean recordFailed(DeliverLead cmd, String reason) {
        AgentLead lead = leads.findById(cmd.leadId()).orElse(null);
        if (lead == null || lead.getState() != LeadState.DELIVERING) {
            log.info("dead-lettered DeliverLead for lead {} ignored: lead is {}", cmd.leadId(),
                    lead == null ? "missing" : lead.getState());
            return false;
        }
        lead.failDelivery(clock.instant());
        outbox.write(Topics.LEAD_EVENTS, lead.getRequestId(), EventEnvelope.of(EventTypes.LEAD_DELIVERY_FAILED,
                lead.getRequestId(), lead.getId(), lead.getAgentId(), Map.of(
                        "adapter", cmd.adapter(),
                        "attempts", lead.getDeliveryAttempts(),
                        "reason", reason.length() > 500 ? reason.substring(0, 500) : reason)));
        return true;
    }
}
