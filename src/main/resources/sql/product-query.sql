-- The product query (ARCHITECTURE.md §9): do evidenced answers win the seller?
-- Over scorecard_fact, replies that have a range rank (requests with two or more replies), split by whether the reply
-- carried an evidence pack: of the seller engagement and the instructions those replies got, what share went to the
-- reply with the HIGHEST range. Rightmove-internal product analytics; aggregated, names no agent.
-- Portable SQL: runs unchanged on Postgres and on H2 in PostgreSQL mode (the test).
select
    evidence_attached,
    count(*)                                                                         as ranked_replies,
    sum(case when seller_engaged then 1 else 0 end)                                  as engaged,
    sum(case when seller_engaged and range_rank = 'HIGHEST' then 1 else 0 end)       as engaged_highest,
    round(cast(sum(case when seller_engaged and range_rank = 'HIGHEST' then 1 else 0 end) as decimal(12, 4))
          / nullif(sum(case when seller_engaged then 1 else 0 end), 0), 4)           as engaged_highest_share,
    sum(case when instructed then 1 else 0 end)                                      as instructed,
    sum(case when instructed and range_rank = 'HIGHEST' then 1 else 0 end)           as instructed_highest,
    round(cast(sum(case when instructed and range_rank = 'HIGHEST' then 1 else 0 end) as decimal(12, 4))
          / nullif(sum(case when instructed then 1 else 0 end), 0), 4)               as instructed_highest_share
from scorecard_fact
where range_rank is not null
group by evidence_attached
order by evidence_attached;
