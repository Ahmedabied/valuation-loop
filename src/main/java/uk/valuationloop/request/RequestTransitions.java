package uk.valuationloop.request;

import static uk.valuationloop.request.RequestState.*;

/** (state, event) -> next state for a ValuationRequest. */
public final class RequestTransitions {

    private RequestTransitions() {}

    public sealed interface RequestEvent {
        record AgentsMatched() implements RequestEvent {}
        record LeadsDispatched() implements RequestEvent {}
        record FirstReply() implements RequestEvent {}
        record SellerSelected() implements RequestEvent {}
        record AllLeadsTerminal() implements RequestEvent {}
    }

    public static RequestState next(RequestState from, RequestEvent event) {
        RequestState to = switch (event) {
            case RequestEvent.AgentsMatched e -> from == RECEIVED ? MATCHED : null;
            case RequestEvent.LeadsDispatched e -> from == MATCHED ? AWAITING_REPLIES : null;
            case RequestEvent.FirstReply e -> switch (from) {
                case AWAITING_REPLIES -> REPLIES_IN;
                case REPLIES_IN -> REPLIES_IN;          // second reply: nothing changes, not an error
                default -> null;
            };
            case RequestEvent.SellerSelected e -> from == REPLIES_IN ? CLOSED : null;
            case RequestEvent.AllLeadsTerminal e -> switch (from) {
                case AWAITING_REPLIES, REPLIES_IN -> CLOSED;
                default -> null;
            };
        };
        if (to == null) {
            throw new IllegalStateException("request in state " + from + " cannot accept " + event.getClass().getSimpleName());
        }
        return to;
    }
}
