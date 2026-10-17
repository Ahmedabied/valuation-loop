package uk.valuationloop.delivery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The simulated third-party CRM at the far end of every adapter: an in-memory record of the leads it has accepted.
 * It dedupes on leadId, as a real CRM does when the caller passes an idempotency key, so a resend is harmless.
 * It also counts every accepted call per leadId, which is how the duplicate-delivery test proves the worker
 * never even called the adapter a second time.
 *
 * ConcurrentHashMap: the listener threads and the test thread read it at the same time; this map is safe for that.
 */
@Component
public class FakeCrm {

    private final Set<String> received = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    /** Called by an adapter once the (simulated) CRM accepted the lead. */
    public void accept(String leadId) {
        calls.computeIfAbsent(leadId, id -> new AtomicInteger()).incrementAndGet();
        received.add(leadId);
    }

    /** Every leadId the CRM holds, once each. A copy: callers cannot change the CRM by editing it. */
    public Set<String> received() {
        return Set.copyOf(received);
    }

    /** How many times an adapter pushed this lead successfully (1 means no double delivery). */
    public int acceptedCalls(String leadId) {
        AtomicInteger n = calls.get(leadId);
        return n == null ? 0 : n.get();
    }
}
