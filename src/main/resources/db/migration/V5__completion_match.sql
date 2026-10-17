-- V5: the Land Registry late match (ARCHITECTURE.md §9, v2). Portable SQL: runs on Postgres and H2 in PostgreSQL mode.
-- (V4 is another change's number; Flyway does not need the versions to be contiguous.)

-- One row per instructed lead whose eventual sale was found in Land Registry Price Paid Data. Written by the
-- completion module in the same transaction as its CompletionMatched outbox row; the primary key on lead_id is what
-- makes a rescan a no-op. A private outcome fact: it feeds the agent's own scorecard and the portal's internal
-- product query, never the seller page. No address is stored: the PPD transaction id is the public reference.
create table completion_match (
    lead_id            varchar(40)   primary key references agent_lead (id),
    request_id         varchar(40)   not null,
    agent_id           varchar(40)   not null,
    ppd_transaction_id varchar(60)   not null,   -- Land Registry PPD transaction unique identifier
    completed_price    numeric(12,0) not null,   -- PPD price paid, whole pounds
    completed_at       date          not null,   -- PPD date of transfer
    matched_at         timestamp with time zone not null,
    match_rule         varchar(60)   not null    -- which rule matched, e.g. postcode+propertyType+dateOfTransfer>=instructedAt-30d
);

-- The scorecard read model carries the completion (from CompletionMatched) and the gap between the completed price
-- and the midpoint of the range this lead's reply quoted. One column per statement so H2 and Postgres agree.
alter table scorecard_fact add column completed_price numeric(12,0);
alter table scorecard_fact add column completed_at date;
-- (completed_price - range midpoint) / range midpoint * 100, two decimals; null without both a completion and a range
alter table scorecard_fact add column price_gap_pct numeric(6,2);
