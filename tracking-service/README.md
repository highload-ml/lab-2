# Tracking service — B1–B2

WebFlux + Spring Data R2DBC, PostgreSQL and Liquibase. Entities use Lombok; repositories
extend `ReactiveCrudRepository`. Queries are derived from method names; pagination and
sorting are passed via `Pageable`. No `@Query`, EntityTemplate or JPA.
Liquibase uses JDBC only during startup. PostgreSQL generates UUID primary keys; a new
entity has a null ID, so Spring Data inserts it rather than attempting an update.

## API

- `POST /api/v1/experiments/{experimentId}/runs` — create (`201`, `Location`).
- `GET /api/v1/experiments/{experimentId}/runs?size=20&after=...` — JSON array;
  `X-Has-Next` and optional `X-Next-Cursor` headers. Reads `size+1` rows without `COUNT`.
- `GET /api/v1/runs/{runId}` — get run.
- `POST /api/v1/runs/{runId}/{start|complete|fail}` — lifecycle commands.
- `POST /api/v1/runs/{runId}/metrics` — log metric.
- `POST /api/v1/runs/{runId}/metrics/batch` — atomically log 1–100 metrics.
- `GET /api/v1/runs/{runId}/metrics[/{metricId}]` — list/get.
- `POST /api/v1/runs/{runId}/artifacts` — register metadata (no file operations).
- `GET /api/v1/runs/{runId}/artifacts[/{artifactId}]` — list/get.
- `GET /internal/artifacts/{artifactId}` — `{id, experimentId, type, runStatus}` for registry.
  The gateway does not expose this namespace.

Metric/artifact lists use `page` and `size`, return JSON arrays and `X-Total-Count`.
Every read page has a hard limit of 50, even if MAX_PAGE_SIZE is larger. Batch writes
allow up to 100 entries; this is independent of the read-page limit.

Run scrolling uses bounded derived queries: equal timestamps with a smaller ID, then older
timestamps. The result is limited before returning to the controller.

Run scrolling intentionally changes the Lab 1 response from an envelope to an array;
clients must read navigation headers instead of `items/nextCursor` in the body.
The current demonstration uses the shared Swagger UI; no Python client is required.

Errors use ProblemDetail: invalid input 400, missing resource 404, duplicate 409,
invalid run state 422, unavailable project service 503. Unexpected error details stay
in server logs.

## Transactions and concurrency

Status checks and writes run in local transactions. Batch inserts roll back together.
Run reads use ordinary `findById`, without pessimistic locks: concurrent completion and
metric/artifact writes are not serialized, and concurrent transitions can overwrite each other.
This is a simplified implementation for the lab. Unique/check constraints and local foreign
keys still guard database integrity. References to projects,
experiments and authors are UUIDs without cross-service foreign keys.

`TransactionalOperator.execute(...).single()` withholds the result until commit.
Batch writes use sequential `concatMap`, collect results inside the transaction and
expose them as a Flux only after commit. An insert or commit failure emits no partial
success response. Keep this buffering inside the transaction.

## Project integration

A minimal Feign adapter calls the existing project internal API. Blocking calls run on
`boundedElastic`, before local database writes; it checks experiment `active` and author
membership, fails closed, and uses Eureka or optional `app.project-service.url`.
Boot HTTP converters are explicitly included for Feign JSON decoding in WebFlux.
Feign timeouts are 2s connect / 5s read. Circuit Breaker is enabled through Spring Cloud + Resilience4j; policy and explanations are in [B4](../README.md#b4-отказы-зависимостей).
The HTTP check and local insert are not a distributed transaction: a concurrent soft
removal may happen after the check. Historical experiment records remain available.

## Verification

```sh
./gradlew :tracking-service:test
python3 scripts/tracking-smoke.py  # after starting the stack
./gradlew :tracking-service:check :tracking-service:jacocoTestReport :tracking-service:bootJar
```

`check` includes PostgreSQL/Testcontainers tests and a 70% line-coverage gate.
Tests cover HTTP contracts, validation, keyset pagination, scoped reads, batch rollback,
sequential transitions, rejection of writes after completion and database constraints.
Config Server and Eureka are disabled in tests; project access is mocked in database tests.
No broker, file storage or model registry business logic is added in B1–B2.
