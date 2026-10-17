# ADR 0002: Hand-rolled outbox instead of Spring Modulith events

Status: accepted, 2026-10-15

## Context
Every state change must publish an event without the dual-write problem: if the database commits and the Kafka send
fails, or the reverse, the two disagree. The standard answer is a transactional outbox. Spring Modulith's event
publication registry with its Kafka externalisation provides one. An engineering review read its 1.4.x source and
found two traps that matter here:
1. The Kafka module forces a `byte[]` value serializer and deserializer. `@RetryableTopic` and the dead letter
   publisher send through the same Boot `KafkaTemplate`, so a JSON value meeting a `byte[]` serializer fails on the
   retry publish, and "fixing" the serializer turns Modulith's bytes into a base64 string on the wire with no error.
2. An event published outside an active transaction is stored but not sent. The send is an after-commit listener,
   which Spring skips when there is no transaction, so the event leaves only when a resubmission timer fires (about
   30 seconds later) or on restart. A non-transactional listener or a self-invoked method produces late events and no
   error.
The owner must also be able to explain, in an interview, exactly what happens between commit and send.

## Decision
Write the outbox by hand. `OutboxWriter.write` inserts the envelope into an `outbox` table inside the caller's
transaction and throws if no transaction is active. `OutboxRelay` ticks every 300 ms, locks unsent rows with
`FOR UPDATE SKIP LOCKED`, sends each with Kafka's acknowledgement awaited, and sets `sent_at`. Plain
`StringSerializer` values carrying one JSON envelope. Consumers are idempotent by state or by natural-key upsert.

## Consequences
- The guarantee fits in one breath: the state change and the event commit together, the relay sends at least once,
  the consumer is idempotent.
- The silent-parking trap is impossible: writing outside a transaction fails loudly
  (`OutboxRelayTest.writingOutsideATransactionFailsLoudly`), and `OutboxLatencyTest` fails if an event takes more
  than 1 s to reach the topic.
- About 250 lines of code to own: the table, the entity, the writer, the relay, the envelope.
- A crash after the send and before `sent_at` commits sends the row again. That is by design; consumers absorb it.
- No ordering beyond the key and the creation order of rows (`created_at`, then the UUIDv7 `id`, so events written
  in the same instant keep their write order: `OutboxOrderingTest`); a failed send stops the batch so later rows wait.
- The relay holds one database transaction open across the Kafka sends of a batch, so the row locks and the
  connection are held while it waits on the broker. That keeps it simple and explainable, and it is bounded by the
  10 s send timeout and the batch size; a v2 relay would claim rows, then send outside the transaction, then mark
  them sent.
- No archive or clean-up of sent rows in v1. Production needs a deletion job or partitioning.
