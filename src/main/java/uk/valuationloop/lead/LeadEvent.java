package uk.valuationloop.lead;

/**
 * The things that can happen to a lead. A sealed interface means the compiler knows the complete list,
 * so a switch over it has no forgotten cases (Java 21 pattern matching).
 */
public sealed interface LeadEvent {

    record Deliver() implements LeadEvent {}

    record DeliverySucceeded() implements LeadEvent {}

    /** attemptsExhausted decides between another DELIVERING round and DELIVERY_FAILED. */
    record DeliveryFailed(boolean attemptsExhausted) implements LeadEvent {}

    record Replied() implements LeadEvent {}

    record EvidenceAdded() implements LeadEvent {}

    record DeadlinePassed() implements LeadEvent {}

    record VisitBooked() implements LeadEvent {}
}
