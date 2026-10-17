# ADR 0004: Elasticsearch for comparables

Status: accepted, 2026-10-15

## Context
An evidence pack needs the most similar recent sales near a home: same property type, close by, recent, similar floor
area. Postgres could do the distance part for a demo (a bounding box, or PostGIS), and that would remove one service.
But the ranking is a weighted similarity across three dimensions, which is what Elasticsearch's `function_score` with
decay functions does natively, and Elasticsearch is in the team's stack. One pitfall was verified in the
Elasticsearch 8.18 docs: a decay function on a numeric field the document does not have returns 1, which reads as a
perfect match. About 10% of indexed sales have no EPC match and so no floor area.

## Decision
- Use Elasticsearch 8.19 with the official `elasticsearch-java` 8.18.8 client only (no Spring Data Elasticsearch).
- One index of sales (`comparables-v1`) and one of postcode centroids (`postcode-centroids-v1`), loaded by the
  `dataloader` straight from the CSV files, with no staging table.
- One `function_score` query (`evidence/ComparablesQuery`): filters on property type, a 1,500 m radius and a 24-month
  window; gauss decays on distance, sale date and floor area multiplied together; and a weight-only function,
  `must_not exists floorAreaM2` with weight 0.5, so an unknown area ranks below a match and above a poor match.
- Leave `floorAreaM2` out of a document when EPC has no match. Never write a sentinel value.

## Consequences
- The ranking is one query anyone can read, and the pack's provenance records its parameters.
- `ComparablesQueryTest` checks the query's JSON without Elasticsearch; `ComparablesRankingEsTest` checks the real
  ranking against a live node and only runs with `-Pes=true`.
- One more service to run (1 GB heap) and a second store that can be down: building a pack then returns 503, and the
  rest of the loop carries on.
- This is complexity kept partly for stack fidelity. The RFC says so rather than claiming Postgres could not do it.
