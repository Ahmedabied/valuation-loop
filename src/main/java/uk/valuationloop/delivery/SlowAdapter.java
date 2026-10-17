package uk.valuationloop.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A CRM that accepts, but takes its time (valuationloop.delivery.slow-ms, default 2000 ms). The call blocks the
 * consumer thread that runs it, and only that one: lead.deliver.v1 has 6 partitions read by 3 listener threads and
 * DeliverLead is keyed by leadId, so the other agents' leads of the same request are normally on another thread and
 * are delivered without waiting (SlowCrmDoesNotBlockOthersTest; the demo's slow scenario prints each lead's
 * partition and delivery time). Two leads that hash onto the same thread still queue, as in any partitioned log.
 * A call slower than valuationloop.delivery.adapter-timeout-ms (5000) is cut off by AdapterCaller and retried.
 * A FAILED call is different again: it moves to a retry topic and the partition carries on (LeadDeliveryListener).
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
