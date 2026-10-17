package uk.valuationloop.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A CRM that accepts, but takes its time (valuationloop.delivery.slow-ms, default 2000 ms). It shows the limit of v1:
 * the call blocks the listener thread, and locally lead.deliver.v1 has one partition read by one thread, so a lead
 * queued behind the slow one waits too (the demo's slow scenario prints it). Keying DeliverLead by leadId is what lets
 * more partitions plus listener concurrency spread the leads out in production, with a per-CRM timeout bounding the
 * wait. A FAILED call is different: it moves to a retry topic and the partition carries on (LeadDeliveryListener).
 */
@Component
public class SlowAdapter implements CrmAdapter {

    private final FakeCrm crm;
    private final long delayMs;

    public SlowAdapter(FakeCrm crm, @Value("${valuationloop.delivery.slow-ms:2000}") long delayMs) {
        this.crm = crm;
        this.delayMs = delayMs;
    }

    @Override
    public String name() {
        return "slow";
    }

    @Override
    public void send(LeadPush push) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Java convention: keep the "please stop" flag for whoever owns the thread
            throw new CrmUnavailable("interrupted while waiting for the slow CRM");
        }
        crm.accept(push.leadId());
    }
}
