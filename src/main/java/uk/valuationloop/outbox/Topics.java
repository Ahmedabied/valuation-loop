package uk.valuationloop.outbox;

/**
 * The Kafka topic contract (ARCHITECTURE.md §4). Names are versioned; a breaking change is a new suffix.
 * Keys decide ordering: everything about one request is keyed by requestId so a consumer sees it in order.
 */
public final class Topics {

    /** ValuationRequested, AgentsMatched, RequestClosed. Key: requestId. */
    public static final String VALUATION_REQUESTED = "valuation.requested.v1";

    /** Command: DeliverLead, one record per lead. Key: leadId so one flaky agent never blocks the others. */
    public static final String LEAD_DELIVER = "lead.deliver.v1";

    /** LeadDelivered, LeadDeliveryFailed, LeadReplied, EvidenceAttached, LeadExpired, SellerEngaged, SellerSelected, VisitBooked. Key: requestId. */
    public static final String LEAD_EVENTS = "lead.events.v1";

    /** InstructionRecorded, (v2) CompletionMatched. Key: requestId. */
    public static final String OUTCOME_RECORDED = "outcome.recorded.v1";

    private Topics() {}
}
