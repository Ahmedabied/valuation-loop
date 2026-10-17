# ADR 0005: Upgrade to Spring Boot 4.1

Status: accepted, 2026-10-17

## Context
Spring Boot 3.5 left open-source support on 2026-06-30. The bump to Boot 4.1 was planned as v2 day 1
(ARCHITECTURE §12), and v1 was written to make it cheap: no Jackson-2-only API beyond the imports, no Spring Retry
outside `@RetryableTopic`, one hand-built Elasticsearch client. The rule for the attempt: the full suite green on
4.1 without weakening a test or a behaviour, or roll back and record what blocked.

## Checklist (researched before any code, Boot 4.0 migration guide + 4.1 release notes)
| Area | What changes in Boot 4.1 | Outcome here |
|---|---|---|
| Version | 4.1.1 is the current GA (Maven Central `spring-boot` metadata, 2026-09-24) | pinned 4.1.1 |
| Gradle | 8.14+ or 9 | 8.14.3 works unchanged; `io.spring.dependency-management` 1.1.7 still used |
| Starters | modular: auto-configuration moved out of the main jar; `spring-kafka` and `flyway-core` alone no longer switch anything on; `-web` renamed `-webmvc`; test starters per technology | `spring-boot-starter-kafka`, `-flyway`, `-webmvc`; `-webmvc-test`, `-jackson-test`, `-kafka-test`, `-data-jpa-test` |
| Jackson 3 | `tools.jackson` packages, unchecked `JacksonException`, java.time built in, `JsonNode.asText()` -> `asString()`; Boot injects a `JsonMapper`. Annotations stay in `com.fasterxml.jackson.annotation` | imports moved; `spring.jackson.use-jackson2-defaults` and the `spring-boot-jackson2` stop-gap NOT needed: the wire JSON is byte-identical with Jackson 3 defaults (see below) |
| spring-kafka 4.1 | retry moved from Spring Retry to Spring Framework: `@RetryableTopic(backoff = @Backoff)` -> `backOff = @org.springframework.kafka.annotation.BackOff`; KRaft only, `@EmbeddedKafka(kraft = ...)` removed; `KafkaTestUtils.consumerProps(group, autoCommit, broker)` marked for removal | same 500 ms x2 schedule, same suffixes and DLT; `kraft = true` dropped; `consumerProps(broker, group, false)` |
| kafka-clients 4.2 | `ConsumerGroupDescription.state()` / `ConsumerGroupState` marked for removal | `groupState()` / `GroupState` |
| Spring Framework 7 | `HttpStatus.UNPROCESSABLE_ENTITY` deprecated for `UNPROCESSABLE_CONTENT` (same 422); `@Transactional`, `RestClient` unchanged for this code | renamed; the API still answers 422 |
| Test slices | moved packages: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `...data.jpa.test.autoconfigure.DataJpaTest`, `...jdbc.test.autoconfigure.{JdbcTest,AutoConfigureTestDatabase}`; `@SpringBootTest` no longer adds MockMvc on its own | imports moved (every MockMvc test already had `@AutoConfigureMockMvc`) |
| Elasticsearch | Boot 4.1 manages `elasticsearch-java` 9.4.5 and auto-configures only the new `Rest5Client`; management of `elasticsearch-rest-client` removed. The Java client is forward compatible only (elastic.co compatibility table: the 8.x branch for an 8.19 server), so a 9.x client against the 8.19.4 server is unsupported | stay on `elasticsearch-java` 8.18.8, pin `elasticsearch-rest-client` 8.18.8 (was Boot-managed), Jackson 2 kept on the classpath for the client's `JacksonJsonpMapper` only |
| ES health | Boot 3.5 added an `elasticsearch` health indicator for any low-level `RestClient` bean; Boot 4 only for `Rest5Client` | `EsConfig` declares the indicator itself (red: OUT_OF_SERVICE, unreachable: DOWN), so `/actuator/health` keeps reporting ES |
| Tracing | Micrometer Tracing 1.7 + OTel 1.62; auto-config in `spring-boot-micrometer-tracing-opentelemetry` | that module plus the bridge; no OTLP exporter added (the OTel starter would add one); `management.tracing.sampling.probability` unchanged |
| Logging | `logging.structured.format.console: ecs` unchanged | unchanged; the keys `scripts/demo.sh` reads (`process.thread.name`, `log.logger`, `message`) checked in a `bootRun` |
| Flyway | 12.4.0, PostgreSQL support in `flyway-database-postgresql` | unchanged; H2 runs the same migrations in tests |
| Hibernate | 7.4.5 | `@Version`, `for update skip locked`, `timestamp with time zone` all pass unchanged (OptimisticLockRetryTest, OutboxOrderingTest, ApiWorkerProfilesTest) |
| H2 | 2.4.240 | unchanged |
| Validation, actuator, virtual threads | `spring-boot-starter-validation`, `management.*`, `spring.threads.virtual.enabled` unchanged | no property renames needed |

## Decision
Upgrade to Spring Boot 4.1.1, Jackson 3 and spring-kafka 4.1.1. Keep the Elasticsearch client on 8.18.8 with its own
Jackson 2 mapper until the server moves to 9.x.

## How "no behaviour change" was checked
- The full suite green twice: the 146 existing tests (no assertion changed; only imports and the API renames above)
  plus `EventTracingTest`, added because no test covered the tracing wiring that moved into its own module (it passes
  on Boot 3.5.16 too). `-Pes=true` green with ES up.
- Wire JSON: a throwaway probe serialised fixed objects (two event envelopes with nested maps, nulls, an `Instant`
  with microseconds, a `LocalDate`, a `BigDecimal`; two `ComparableDocument`s, a `Comparable`, a `ComparisonView`
  with a parsed `JsonNode`; and a read-then-write round trip of an envelope) with the app's own mapper on Boot 3.5.16
  and on Boot 4.1.1. The two outputs are byte-identical (`cmp`). The probe was not committed.
- The ES mapper: the same `ComparableDocument`s serialised by the old (copy of Boot 3's `ObjectMapper`) and the new
  (own Jackson 2 `JsonMapper`) `JacksonJsonpMapper` are byte-identical, and unknown fields are still ignored on read.

## Consequences / what to watch
- Two Jackson lines on the classpath: Jackson 3 for the app, Jackson 2 (2.21.x, Boot's `jackson-2-bom`) for the ES
  client only. `EventSchemas` (test) also parses with Jackson 2 because the networknt validator is built on it.
  When the server moves to ES 9, switch to `elasticsearch-java` 9.x, `Rest5Client` and drop Jackson 2.
- Jackson 3 sorts properties alphabetically by default for plain classes. Every serialised type here is a record or
  a `Map`, which keep their order, so nothing changed; a future POJO on the wire should be checked.
- Found on the way, not caused by the upgrade: `DuplicateDeliveryTest` waited on partition 0 only, so it failed on
  its own (on Boot 3.5.16 too) whenever the lead hashed to another of the 6 partitions. It now waits on all of them;
  its assertions are unchanged.
- `JacksonException` is unchecked: the existing `catch` blocks still turn bad JSON into the same domain errors.
