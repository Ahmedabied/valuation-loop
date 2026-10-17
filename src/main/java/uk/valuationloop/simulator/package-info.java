/**
 * Simulator (demo profile only, internal: never seller- or agent-facing). POST /simulator/scenarios/{ok|slow|
 * flaky-crm|late-reply|all} plays every party at once (seller, agents, their CRMs) against the real loop and returns a
 * report; ?format=text is what `make demo` prints. It calls the application's own services directly rather than its
 * HTTP API: the services are exactly what the controllers call, the report can read the lead rows between steps, and
 * the test can run it without a web port. The Kafka hops stay real: the outbox relay, the delivery consumer (with its
 * retry topics and DLT) and the scorecard consumer all run, and every wait in a scenario is a wait for them.
 * POST /simulator/outbox-gap pauses the relay to show the gap between commit and send (the "kill" demo, no kill).
 */
package uk.valuationloop.simulator;
