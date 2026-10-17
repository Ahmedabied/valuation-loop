package uk.valuationloop.outbox;

/**
 * The event names that go in {@link EventEnvelope#eventType()} (ARCHITECTURE.md §4, docs/EVENTS.md).
 * Each constant has a JSON Schema of the same name in src/main/resources/events/, and tests validate every
 * emitted envelope against it, so the javadoc below and the schema files are the contract the modules share.
 *
 * Request-level events carry leadId = null and agentId = null in the envelope; every lead-level event carries both.
 * Like Python constants in a module: a final class with a private constructor, never instantiated.
 */
public final class EventTypes {

    // --- valuation.requested.v1, key requestId -------------------------------------------------------------

    /** Topic {@value Topics#VALUATION_REQUESTED}, key requestId. Payload: postcode, outcode, propertyType, bedrooms. */
    public static final String VALUATION_REQUESTED = "ValuationRequested";

    /** Topic {@value Topics#VALUATION_REQUESTED}, key requestId. Payload: agentIds[], eligibilityRule. */
    public static final String AGENTS_MATCHED = "AgentsMatched";

    /** Topic {@value Topics#VALUATION_REQUESTED}, key requestId. Payload: reason (SELLER_SELECTED | ALL_LEADS_TERMINAL). */
    public static final String REQUEST_CLOSED = "RequestClosed";

    // --- lead.deliver.v1, key leadId (a command, not a fact) ------------------------------------------------

    /** Topic {@value Topics#LEAD_DELIVER}, key leadId. Payload: adapter. */
    public static final String DELIVER_LEAD = "DeliverLead";

    // --- lead.events.v1, key requestId ----------------------------------------------------------------------

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: adapter, attempt. */
    public static final String LEAD_DELIVERED = "LeadDelivered";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: adapter, attempts, reason. Agent-private, never on the seller page. */
    public static final String LEAD_DELIVERY_FAILED = "LeadDeliveryFailed";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: low, high, message (nullable), late, deliveredAt (nullable). */
    public static final String LEAD_REPLIED = "LeadReplied";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: packId, comparablesCount, comparablesRange {low, mid, high}. */
    public static final String EVIDENCE_ATTACHED = "EvidenceAttached";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: replyDueAt. */
    public static final String LEAD_EXPIRED = "LeadExpired";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: action (VIEWED | CONTACTED | VISIT_REQUESTED). */
    public static final String SELLER_ENGAGED = "SellerEngaged";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: empty object; the envelope's leadId is the choice. */
    public static final String SELLER_SELECTED = "SellerSelected";

    /** Topic {@value Topics#LEAD_EVENTS}, key requestId. Payload: visitAt (nullable). */
    public static final String VISIT_BOOKED = "VisitBooked";

    // --- outcome.recorded.v1, key requestId -----------------------------------------------------------------

    /** Topic {@value Topics#OUTCOME_RECORDED}, key requestId. Payload: leadRef (the leadId the CRM echoes back), source, recordedAt. */
    public static final String INSTRUCTION_RECORDED = "InstructionRecorded";

    /**
     * Topic {@value Topics#OUTCOME_RECORDED}, key requestId. Payload: ppdTransactionId, completedPrice, completedAt
     * (date), matchRule. The v2 Land Registry late match (completion module); a private outcome fact, never on the
     * seller page.
     */
    public static final String COMPLETION_MATCHED = "CompletionMatched";

    private EventTypes() {}
}
