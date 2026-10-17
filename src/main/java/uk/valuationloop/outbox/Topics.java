package uk.valuationloop.outbox;

/**
 * The Kafka topic contract (ARCHITECTURE.md §4). Names are versioned; a breaking change is a new suffix.
 * Keys decide ordering: everything about one request is keyed by requestId so a consumer sees it in order.
 */
public final class Topics {

    /** ValuationRequested, AgentsMatched, RequestClosed. Key: requestId. */
    public static final String VALUATION_REQUESTED = "valuation.requested.v1";

    /**
     * Command: DeliverLead, one record per lead. Key: leadId, so with several partitions and listener threads one
     * request's leads spread out (6 partitions, 3 listener threads; see delivery/DeliveryTopics). Retries below.
     */
    public static final String LEAD_DELIVER = "lead.deliver.v1";

    /**
     * The retry chain @RetryableTopic builds for LEAD_DELIVER (delivery/LeadDeliveryListener): 3 attempts means
     * attempt 1 on lead.deliver.v1, attempt 2 on -retry-0, attempt 3 on -retry-1, then the dead letter topic -dlt.
     * The names come from the listener's suffix settings; these constants let tests and tools name them.
     */
    public static final String LEAD_DELIVER_RETRY_0 = LEAD_DELIVER + "-retry-0";
    public static final String LEAD_DELIVER_RETRY_1 = LEAD_DELIVER + "-retry-1";
    public static final String LEAD_DELIVER_DLT = LEAD_DELIVER + "-dlt";

    /** LeadDelivered, LeadDeliveryFailed, LeadReplied, EvidenceAttached, LeadExpired, SellerEngaged, SellerSelected, VisitBooked. Key: requestId. */
    public static final String LEAD_EVENTS = "lead.events.v1";

    /** InstructionRecorded, (v2) CompletionMatched. Key: requestId. */
    public static final String OUTCOME_RECORDED = "outcome.recorded.v1";

    private Topics() {}
}
