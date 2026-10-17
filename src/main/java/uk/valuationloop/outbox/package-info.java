/**
 * Outbox: how an event leaves the database without ever being lost or sent without its state change. A service
 * writes its state change and its events ({@link uk.valuationloop.outbox.OutboxWriter}) in ONE transaction; the
 * {@link uk.valuationloop.outbox.OutboxRelay} sends committed rows to Kafka every 300 ms and marks them sent. A crash
 * between send and mark resends the row, so delivery is at least once and every consumer is idempotent.
 * Also here: the envelope every event wears, the topic and event-type names (the contract), and UUIDv7 ids.
 * Why hand-rolled and not Spring Modulith: docs/adr/0002-hand-rolled-outbox.md.
 */
package uk.valuationloop.outbox;
