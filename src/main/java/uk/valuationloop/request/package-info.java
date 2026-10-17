/**
 * Request: the seller's valuation request. POST /valuation-requests checks the postcode, looks up the eligible
 * agents (a lookup, never a ranking: agent/AgentDirectory), creates one lead per agent and writes ValuationRequested,
 * AgentsMatched and one DeliverLead per lead in the same transaction. The request's own state machine lives in
 * {@link uk.valuationloop.request.RequestTransitions}. A request closes on the seller's selection
 * (comparison/SellerActions) or, by {@link uk.valuationloop.request.RequestCloser}, when every lead is terminal and
 * none replied (RFC §5).
 */
package uk.valuationloop.request;
