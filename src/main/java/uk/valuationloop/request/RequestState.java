package uk.valuationloop.request;

/**
 * Where one seller's request is. Simpler than the lead: the request is a container for leads.
 * RECEIVED -> MATCHED -> AWAITING_REPLIES -> REPLIES_IN -> CLOSED (seller selected, or every lead is terminal).
 */
public enum RequestState {
    RECEIVED,
    MATCHED,
    AWAITING_REPLIES,
    REPLIES_IN,
    CLOSED
}
