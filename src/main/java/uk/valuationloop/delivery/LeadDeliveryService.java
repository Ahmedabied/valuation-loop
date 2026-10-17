package uk.valuationloop.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.valuationloop.delivery.CrmAdapter.LeadPush;
import uk.valuationloop.delivery.DeliveryTransactions.Begin;
import uk.valuationloop.request.RequestCloser;

/**
 * Delivers one lead: begin the attempt (commit), call the adapter (no transaction open), record the result (commit).
 *
 * This class is deliberately NOT @Transactional. It is the non-transactional wrapper around the transactional
 * steps in {@link DeliveryTransactions}, which is a separate bean so every call goes through Spring's proxy and
 * really opens a transaction (a @Transactional method called from inside its own class is not proxied). It is also
 * where optimistic-lock conflicts are retried in-process, so they never reach the retry topics or the DLT.
 *
 * Adapter failures are NOT caught: they propagate to the listener, and @RetryableTopic moves the record to the next
 * retry topic. That is the whole retry design: the exception is the signal.
 */
@Service
public class LeadDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(LeadDeliveryService.class);

    private final DeliveryTransactions tx;
    private final AdapterRegistry adapters;
    private final RequestCloser closer;

    public LeadDeliveryService(DeliveryTransactions tx, AdapterRegistry adapters, RequestCloser closer) {
        this.tx = tx;
        this.adapters = adapters;
        this.closer = closer;
    }

    public void deliver(DeliverLead cmd) {
        Begin begin = OptimisticLockRetry.run(() -> tx.beginAttempt(cmd));
        switch (begin) {
            case Begin.Skip skip -> log.info("DeliverLead {} for lead {} skipped: {}", cmd.eventId(), cmd.leadId(), skip.why());
            case Begin.Attempt attempt -> {
                CrmAdapter adapter = adapters.forName(cmd.adapter());
                // leadId is the idempotency key: if this call reached the CRM before a crash, the resend is a no-op there
                adapter.send(new LeadPush(cmd.leadId(), cmd.requestId(), cmd.agentId()));
                OptimisticLockRetry.runVoid(() -> tx.recordDelivered(cmd, attempt.number()));
                log.info("lead {} delivered via '{}' on attempt {}", cmd.leadId(), cmd.adapter(), attempt.number());
            }
        }
    }

    /**
     * Called once the record reached the DLT: the lead stops being retried and is marked DELIVERY_FAILED. Then the
     * same closure rule the deadline scanner applies: if that was the request's last open lead and nobody replied,
     * the request closes (ALL_LEADS_TERMINAL). Without this, a request whose every CRM failed would never close,
     * because the scanner only looks at requests that had a lead expire. A separate transaction, after the first.
     */
    public void giveUp(DeliverLead cmd, String reason) {
        boolean marked = OptimisticLockRetry.run(() -> tx.recordFailed(cmd, reason));
        if (marked) {
            log.warn("lead {} marked DELIVERY_FAILED: {}", cmd.leadId(), reason);
            if (OptimisticLockRetry.run(() -> closer.closeIfAllLeadsTerminal(cmd.requestId(), RequestCloser.ALL_LEADS_TERMINAL))) {
                log.info("request {} closed: every lead is terminal and none replied", cmd.requestId());
            }
        }
    }
}
