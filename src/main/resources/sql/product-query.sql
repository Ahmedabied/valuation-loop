-- The product query (ARCHITECTURE.md §9): do evidenced answers win the seller, and at what price gap?
-- Over scorecard_fact, replies that have a range rank (requests with two or more replies), split by whether the reply
-- carried an evidence pack: of the seller engagement and the instructions those replies got, what share went to the
-- reply with the HIGHEST range. Internal product analytics; aggregated, names no agent.
-- At what price gap: for the replies whose lead later completed (the v2 Land Registry late match), the median of
-- price_gap_pct = (completed price - replied range midpoint) / midpoint * 100, separately for the HIGHEST-range reply
-- and for the others, so each (evidence, highest-range) group has its median. Negative: sold below the midpoint.
-- Portable SQL: runs unchanged on Postgres and on H2 in PostgreSQL mode (the test). The median is the SQL-standard
-- ordered-set aggregate percentile_cont(0.5) WITHIN GROUP (ORDER BY ...), which both databases implement; both ignore
-- the NULLs the CASE produces, which is what restricts each median to its group. Cast to decimal(8, 2) rather than
-- round(), because Postgres's percentile_cont returns double precision and round(double, 2) does not exist there.
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
          / nullif(sum(case when instructed then 1 else 0 end), 0), 4)               as instructed_highest_share,
    sum(case when completed_price is not null then 1 else 0 end)                     as completed,
    cast(percentile_cont(0.5) within group (
             order by case when range_rank = 'HIGHEST' then price_gap_pct end) as decimal(8, 2))
                                                                                     as median_price_gap_pct_highest,
    cast(percentile_cont(0.5) within group (
             order by case when range_rank <> 'HIGHEST' then price_gap_pct end) as decimal(8, 2))
                                                                                     as median_price_gap_pct_other
from scorecard_fact
where range_rank is not null
group by evidence_attached
order by evidence_attached;
