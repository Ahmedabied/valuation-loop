package uk.valuationloop.delivery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A CRM that fails the first N calls for each lead (valuationloop.delivery.flaky-failures, default 2), then accepts.
 * With the default and 3 attempts the demo lead lands on the third try; tests set N = 99 to drive a lead into the DLT.
 * Counting per leadId keeps leads independent: one lead's failures never use up another's.
 */
@Component
public class FlakyAdapter implements CrmAdapter {

    private final FakeCrm crm;
    private final int failuresPerLead;
    private final Map<String, AtomicInteger> failuresSoFar = new ConcurrentHashMap<>();

    public FlakyAdapter(FakeCrm crm, @Value("${valuationloop.delivery.flaky-failures:2}") int failuresPerLead) {
        this.crm = crm;
        this.failuresPerLead = failuresPerLead;
    }

    @Override
    public String name() {
        return "flaky";
    }

    @Override
    public void send(LeadPush push) {
        AtomicInteger failed = failuresSoFar.computeIfAbsent(push.leadId(), id -> new AtomicInteger());
        if (failed.get() < failuresPerLead) {
            int n = failed.incrementAndGet();
            throw new CrmUnavailable("flaky CRM refused lead " + push.leadId() + " (failure " + n + " of " + failuresPerLead + ")");
        }
        crm.accept(push.leadId());
    }
}
