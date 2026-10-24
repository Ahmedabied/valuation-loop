/**
 * Observability (ARCHITECTURE.md §11): one traceId per request across threads and Kafka hops, and three metrics.
 * {@link uk.valuationloop.observability.EventTracing} writes the current W3C traceparent into every event envelope
 * at outbox-write time, and the relay and both consumers reopen a span from it, so the API thread, the relay and
 * the consumers log the same traceId (JSON logs under the local profile, application-local.yml).
 * {@link uk.valuationloop.observability.LoopMetrics} owns outbox_unsent (gauge, sampled every relay tick),
 * dlt_records_total (counter, the DLT handler) and reply_minutes (summary, ReplyService), all on
 * 127.0.0.1:8081/actuator/prometheus. In one sentence: "the trace travels inside the event, not beside it, because
 * the outbox relay runs on another thread and later than the request".
 */
package uk.valuationloop.observability;
