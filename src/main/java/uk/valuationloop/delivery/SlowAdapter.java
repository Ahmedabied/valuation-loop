package uk.valuationloop.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A CRM that accepts, but takes its time (valuationloop.delivery.slow-ms, default 2000 ms). Shows that one slow
 * agent never delays the others: DeliverLead is keyed by leadId, and each lead is its own record.
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
