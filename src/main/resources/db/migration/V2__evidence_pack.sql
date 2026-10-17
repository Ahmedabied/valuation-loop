-- V2: evidence packs (ARCHITECTURE.md §7). Portable SQL: runs on Postgres and H2 in PostgreSQL mode, like V1.
-- The evidence module owns these rows; the comparison module reads evidence_pack in a SQL join for the seller view.
-- Exclusions are private to the agent: they record which comparables the agent removed while curating a pack.
-- Nothing in these tables orders agents; a pack describes comparables, never the agent who attached it.
-- Rule: at most one ATTACHED pack per lead (a lead may have many built drafts). On Postgres that is a partial unique
-- index (unique (lead_id) where attached_at is not null), but H2, which the tests run these migrations on, cannot do
-- a partial unique index, so EvidenceService.attach enforces the rule instead, inside the attach transaction.

-- One pack per build. pack_json is the pack exactly as served to the seller:
-- {packId, comparables[], comparablesRange{low, mid, high}, dataVintage, method, provenance}.
-- The comparables_* columns copy the range out of the JSON so a SQL join can read it without parsing JSON.
create table evidence_pack (
    id                varchar(40)  primary key,       -- ep_<uuidv7>
    lead_id           varchar(40)  not null references agent_lead (id),
    request_id        varchar(40)  not null,
    agent_id          varchar(40)  not null,
    pack_json         varchar(100000) not null,
    comparables_count integer      not null,
    comparables_low   numeric(12,0),
    comparables_mid   numeric(12,0),
    comparables_high  numeric(12,0),
    data_vintage      varchar(40)  not null,          -- e.g. the latest PPD month in the index
    attached_at       timestamp with time zone,       -- null until the agent attaches the pack to the reply
    created_at        timestamp with time zone not null,
    updated_at        timestamp with time zone not null,
    version           bigint       not null default 0
);
create index evidence_pack_lead_idx on evidence_pack (lead_id);

-- The agent's private curation record: one row per comparable the agent removed from a pack.
-- Never shown to the seller; never used to compare agents.
create table evidence_pack_exclusion (
    pack_id            varchar(40) not null references evidence_pack (id),
    ppd_transaction_id varchar(60) not null,          -- Land Registry PPD transaction unique identifier
    excluded_at        timestamp with time zone not null,
    primary key (pack_id, ppd_transaction_id)
);
