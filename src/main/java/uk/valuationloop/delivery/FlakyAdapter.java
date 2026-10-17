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
 *
 * {@link #overrideFailuresPerLead(int)} / {@link #clearOverride()} change N at runtime. Their only caller is the
 * demo profile's simulator (the flaky-crm scenario sets N very high so a lead reaches the DLT, then restores it);
 * without a call the configured value applies, as it always did.
 */
@Component
public class FlakyAdapter implements CrmAdapter {

    private final FakeCrm crm;
    private final int failuresPerLead;
    private final Map<String, AtomicInteger> failuresSoFar = new ConcurrentHashMap<>();
    private volatile Integer failuresOverride;   // null: use failuresPerLead. volatile: set by one thread, read by the listener's

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
        int limit = failuresOverride != null ? failuresOverride : failuresPerLead;
        AtomicInteger failed = failuresSoFar.computeIfAbsent(push.leadId(), id -> new AtomicInteger());
        if (failed.get() < limit) {
            int n = failed.incrementAndGet();
            throw new CrmUnavailable("flaky CRM refused lead " + push.leadId() + " (failure " + n + " of " + limit + ")");
        }
        crm.accept(push.leadId());
    }

    /** Demo only (simulator flaky-crm): fail the first {@code failures} calls per lead instead of the configured N. */
    public void overrideFailuresPerLead(int failures) {
        this.failuresOverride = failures;
    }

    /** Demo only: back to the configured valuationloop.delivery.flaky-failures. */
    public void clearOverride() {
        this.failuresOverride = null;
    }
}
