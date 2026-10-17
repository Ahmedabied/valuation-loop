# ADR 0001: One modular monolith instead of four services

Status: accepted, 2026-10-15

## Context
The job is backend work on microservices that integrate with third-party systems, so a design with several services
looks closer to the real estate. Two designs were compared: four deployables talking only through Kafka, and one
Spring Boot app with modules as packages. A hostile engineering review found that three of the four services produced
events without an outbox, so a crash between the database write and the Kafka send would lose the event. Four JVMs next
to Elasticsearch, Kafka and Postgres do not fit a 14 GB laptop, and the four-service design was estimated at about
15 evenings against 9 to 10 for one app. The repo has to be explainable line by line by one engineer new to Java.

## Decision
Build one Spring Boot application. Modules are packages (`request`, `delivery`, `response`, `evidence`, `comparison`,
`outcome`, `scorecard`, `dataloader`, plus `lead`, `agent`, `outbox`, `api`). Postgres is the system of record.
Kafka is used only where asynchrony is real: the outbound CRM delivery (`lead.deliver.v1` and its retry and dead
letter topics) and the scorecard read model, which must be replayable. Everything else is a method call or a SQL join
inside one transaction. Every event still goes through the outbox and onto a topic, so the seams a split would use
already exist.

## Consequences
- One JVM, one log, one `make run`. A state change and its event share one transaction everywhere.
- The seller view is a SQL join, always current, with no consumer lag and no dedupe table.
- Module boundaries are package conventions in v1, not checked by a tool. The `lead` aggregate is shared by several
  modules. A boundary check (for example Spring Modulith's `ApplicationModules.verify()`) is a cheap v2 addition.
- It does not show cross-service deployment, service discovery or independent scaling. Splitting `delivery` into a
  second deployable is v2 day 2 and is small: its only inputs and outputs are already Kafka topics.
- An interviewer may ask "why not microservices?". The answer is the outbox hole, the memory budget, and that the
  split point is designed in rather than paid for up front.
