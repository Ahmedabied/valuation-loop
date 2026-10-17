-- V4: drop the unused processed_event table from V1.
-- Every consumer is idempotent by natural key: the delivery consumer checks the lead's state before it acts, and the
-- scorecard upserts scorecard_fact by lead_id. A repeated event changes nothing, so the dedupe table was never needed.
drop table processed_event;
