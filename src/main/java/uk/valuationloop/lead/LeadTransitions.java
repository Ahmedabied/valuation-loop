package uk.valuationloop.lead;

import static uk.valuationloop.lead.LeadState.*;

/**
 * The lead state machine as a pure function: (state, event) -> next state, or an IllegalTransition.
 * No framework, no reflection: a switch the reader can check against the diagram in LeadState in one glance.
 */
public final class LeadTransitions {

    private LeadTransitions() {}

    public static LeadState next(LeadState from, LeadEvent event) {
        LeadState to = switch (event) {
            case LeadEvent.Deliver e -> from == CREATED ? DELIVERING : null;
            case LeadEvent.DeliverySucceeded e -> from == DELIVERING ? DELIVERED : null;
            case LeadEvent.DeliveryFailed e -> from == DELIVERING ? (e.attemptsExhausted() ? DELIVERY_FAILED : DELIVERING) : null;
            case LeadEvent.Replied e -> switch (from) {
                case DELIVERED, EXPIRED -> REPLIED;   // a late reply after EXPIRED is still welcome; the lead records late=true
                default -> null;
            };
            case LeadEvent.EvidenceAdded e -> from == REPLIED ? EVIDENCED : null;
            case LeadEvent.DeadlinePassed e -> from == DELIVERED ? EXPIRED : null;
            case LeadEvent.VisitBooked e -> switch (from) {
                case REPLIED, EVIDENCED -> VISIT_BOOKED;
                default -> null;
            };
        };
        if (to == null) {
            throw new IllegalTransition(from, event);
        }
        return to;
    }

    public static boolean isTerminalForDelivery(LeadState s) {
        return s == REPLIED || s == EVIDENCED || s == VISIT_BOOKED || s == EXPIRED || s == DELIVERY_FAILED;
    }

    public static final class IllegalTransition extends IllegalStateException {
        public IllegalTransition(LeadState from, LeadEvent event) {
            super("lead in state " + from + " cannot accept " + event.getClass().getSimpleName());
        }
    }
}
