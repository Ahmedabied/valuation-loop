-- V3: the scorecard read model (ARCHITECTURE.md §9). Portable SQL: runs on Postgres and H2 in PostgreSQL mode.
-- This is the one replayable read model: the scorecard consumer builds it from lead.events.v1 + outcome.recorded.v1.
-- Rows are upserted by lead_id, so replaying the topics rebuilds the same facts with no dedupe table.
-- Private to each agent: an agent reads only its own rows. It is never a league table and never orders agents.
create table scorecard_fact (
    lead_id           varchar(40)  primary key,
    request_id        varchar(40)  not null,
    agent_id          varchar(40)  not null,
    delivered_at      timestamp with time zone,
    replied_at        timestamp with time zone,
    reply_minutes     integer,                        -- replied_at - delivered_at, whole minutes
    within_deadline   boolean,                        -- null until the lead replies or expires
    evidence_attached boolean      not null default false,
    range_low         numeric(12,0),
    range_high        numeric(12,0),
    range_rank        varchar(10),                    -- HIGHEST | MIDDLE | LOWEST by midpoint among that request's
                                                      -- replies; null until two or more replies exist
    seller_engaged    boolean      not null default false,
    seller_selected   boolean      not null default false,
    visit_booked      boolean      not null default false,
    instructed        boolean      not null default false,
    last_event_at     timestamp with time zone not null, -- occurredAt of the newest event applied to this row
    updated_at        timestamp with time zone not null
);
create index scorecard_fact_agent_idx on scorecard_fact (agent_id);
