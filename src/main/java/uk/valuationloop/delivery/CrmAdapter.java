package uk.valuationloop.delivery;

/**
 * One way of handing a lead to an agent's CRM (or inbox). In production each would be an HTTP client for a
 * third-party system; here three simulated ones show the behaviours that matter: ok, slow, flaky.
 *
 * A Java interface is a Python Protocol/ABC: any class that implements these methods can be used as an adapter,
 * and Spring collects every implementing @Component into the {@link AdapterRegistry}.
 */
public interface CrmAdapter {

    /** The name used in the DeliverLead payload ("ok", "slow", "flaky"). */
    String name();

    /**
     * Pushes the lead. Must be idempotent on {@code push.leadId()}: the same lead sent twice is one lead in the CRM.
     * Throws {@link CrmUnavailable} when the third party fails; the listener lets it propagate so the record is retried.
     */
    void send(LeadPush push);

    /** What goes over the wire to the CRM. leadId doubles as the idempotency key. No seller PII exists in this system. */
    record LeadPush(String leadId, String requestId, String agentId) {}

    /** The third party said no (timeout, 5xx, refused). Retryable. */
    class CrmUnavailable extends RuntimeException {
        public CrmUnavailable(String message) {
            super(message);
        }
    }
}
