# ADR 0003: No ranking or verdicts on agents

Status: accepted, 2026-10-15

## Context
A product that shows homeowners several valuations invites an obvious feature: score the agents, flag the ones whose
prices look high, sort the list. Agents are the platform's paying customers, and the agent-facing products depend on
their trust. A public judgement of a named agent by the platform is a commercial risk and, built on simulated data, a
claim nothing here could back. Platform ranking also brings transparency duties about the main ranking parameters;
the simplest way to meet them is to have no ranking beyond what the seller chose and who is eligible. There is a
technical reason too: the comparables for one home disagree a lot. Over 400 real sales in eight South London outcodes,
the median width of the comparables range (upper quartile minus lower quartile, over the median, top 10 comparables)
is 0.280. A 5 to 10% difference between two agents' prices sits well inside that spread, so no single number could
say which agent is right.

## Decision
- Eligibility is a lookup, not a ranking: the agents holding a slot for the outcode, or the agents the seller picked
  (`agent/AgentDirectory`).
- The seller view lists replies in reply order with the evidence each agent attached, plus a disclaimer that nothing
  on the page rates an agent. No scores and no reordering.
- The pack's range is named `comparablesRange`: the range implied by the comparables the agent chose to attach. It
  describes the evidence, not the agent.
- Delivery failures, expiries and late replies are agent-private facts. They never reach the seller page.
- An agent's scorecard shows its own numbers next to a labelled fixture benchmark, never another agent.
- Range rank among a request's replies (highest, middle, lowest) exists only inside the scorecard facts, for the
  aggregated product query, which names no agent.

## Consequences
- The homeowner gets more to compare with, and the platform judges no one.
- The product question ("do evidenced answers win the seller?") is still answerable, in aggregate, by the product
  query over the scorecard.
- Any future request for a public score has to come back through this ADR.
