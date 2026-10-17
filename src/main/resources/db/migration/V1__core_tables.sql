-- V1: the core of the valuation loop. Portable SQL: runs on Postgres (local/prod) and H2 in PostgreSQL mode (tests).
-- Agents are fixtures (ag_fictional_*). No real agency ever appears in this schema or its data.

-- The transactional outbox: every domain event is written here in the SAME transaction as the state change.
-- The relay publishes rows with sent_at IS NULL to Kafka and marks them. At-least-once; consumers are idempotent.
create table outbox (
    id            uuid primary key,
    topic         varchar(100)  not null,
    partition_key varchar(60)   not null,
    event_type    varchar(60)   not null,
    payload       varchar(100000) not null,        -- the JSON envelope exactly as it goes on the wire
    created_at    timestamp with time zone not null,
    sent_at       timestamp with time zone,
    attempts      integer       not null default 0,
    last_error    varchar(500)
);
-- (a partial index "where sent_at is null" would be better on Postgres; kept plain so H2 runs the same file)
create index outbox_unsent_idx on outbox (sent_at, created_at);

-- One row per seller request.
create table valuation_request (
    id               varchar(40)  primary key,      -- vr_<uuidv7>
    state            varchar(30)  not null,
    seller_ref       varchar(60)  not null,         -- pseudonymous; no seller PII is stored
    postcode         varchar(10)  not null,
    outcode          varchar(5)   not null,
    property_type    varchar(20)  not null,         -- FLAT | HOUSE | MAISONETTE | BUNGALOW
    bedrooms         integer      not null,
    floor_area_m2    numeric(8,1),
    selected_lead_id varchar(40),
    created_at       timestamp with time zone not null,
    updated_at       timestamp with time zone not null,
    version          bigint       not null default 0
);

-- One row per agent per request: the lead. The interesting state machine lives here.
create table agent_lead (
    id               varchar(40)  primary key,      -- ld_<uuidv7>
    request_id       varchar(40)  not null references valuation_request (id),
    agent_id         varchar(40)  not null,
    state            varchar(30)  not null,
    delivery_attempts integer     not null default 0,
    delivered_at     timestamp with time zone,
    reply_due_at     timestamp with time zone,
    replied_at       timestamp with time zone,
    late             boolean      not null default false,
    reply_low        numeric(12,0),
    reply_high       numeric(12,0),
    reply_message    varchar(2000),
    evidence_pack_id varchar(40),
    visit_booked_at  timestamp with time zone,
    instructed_at    timestamp with time zone,
    created_at       timestamp with time zone not null,
    updated_at       timestamp with time zone not null,
    version          bigint       not null default 0,
    constraint agent_lead_one_per_agent unique (request_id, agent_id)
);
create index agent_lead_due_idx on agent_lead (state, reply_due_at);

-- Idempotency guard for consumers whose side effect is not a natural-key upsert.
create table processed_event (
    consumer_group varchar(60) not null,
    event_id       uuid        not null,
    processed_at   timestamp with time zone not null,
    primary key (consumer_group, event_id)
);
