# Tracking service — B1–B2

WebFlux + Spring Data R2DBC, PostgreSQL and Liquibase. Entities use Lombok; repositories
extend `ReactiveCrudRepository`, SQL is declared with `@Query`. No EntityTemplate or JPA.
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

Run scrolling intentionally changes the Lab 1 response from an envelope to an array;
clients must read navigation headers instead of `items/nextCursor` in the body (B5).

Errors use ProblemDetail: invalid input 400, missing resource 404, duplicate 409,
invalid run state 422, unavailable project service 503. Unexpected error details stay
in server logs.

## Transactions and concurrency

All writes that depend on run status first `SELECT ... FOR UPDATE` the same run row.
The lock is held until the metric batch, artifact insert or state change commits.
This serializes completion with metric/artifact registration. Unique/check constraints
and local foreign keys provide the final database guard. References to projects,
experiments and authors are UUIDs without cross-service foreign keys.

`TransactionalOperator.execute(...).single()` withholds the result until commit.
Batch writes use sequential `concatMap`, collect results inside the transaction and
expose them as a Flux only after commit. An insert or commit failure emits no partial
success response. Do not remove this buffering or move the lock outside the transaction.

## Project integration

A minimal Feign adapter calls the existing project internal API. Blocking calls run on
`boundedElastic`, before local database writes; it checks experiment `active` and author
membership, fails closed, and uses Eureka or optional `app.project-service.url`.
Boot HTTP converters are explicitly included for Feign JSON decoding in WebFlux.
Feign timeouts are 2s connect / 5s read. Circuit Breaker remains B4.
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
concurrent transitions, completion versus batch logging and database constraints.
Config Server and Eureka are disabled in tests; project access is mocked in database tests.
No broker, file storage or model registry business logic is added in B1–B2.
