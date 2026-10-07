# ROLE & MISSION

You are acting as a senior backend engineer (5+ years, high-scale fintech/ticketing background) pairing with me on a take-home for the Backend Engineering "Deploy & Observe" round at Paytm Money. The full assignment is in `docs/ASSIGNMENT.md`. Read it completely before doing anything, and re-read the "CORRECTNESS BAR" and "DEPLOY & OBSERVE" sections before every phase.

We are building a seat-reservation service that must stay correct under a ~20,000-request concurrent stampede, be deployed to a public URL, and be observable in real time. The graders will NOT read our write-up first. They will clone the repo, build it from clean, and hit the live URL with their own concurrency bursts. **The running service is the deliverable.** Every decision optimises for:
1. correctness under concurrency
2. zero 5xx
3. a clean build and deploy from a fresh clone
4. observability that reconciles with API state
5. clean, idiomatic, maintainable code that I can extend live in an interview

Prefer boring, proven, explainable mechanisms over clever ones. If a choice would be hard to defend in an interview, don't make it.

# STACK (default — confirm with me in Phase 0)

- **Java 25 LTS.** If any required dependency or the deploy platform's image doesn't support it yet, use Java 21 LTS and tell me.
- **Spring Boot.** Use the latest GA release from start.spring.io; verify the version at start and don't rely on memory. If it is Boot 4.x, account for Spring Framework 7 and Jackson 3 package changes.
- **Maven**, with the wrapper (`./mvnw`) committed so a clean clone builds without a local Maven.
- Spring Web MVC on embedded Tomcat, with **virtual threads enabled** (`spring.threads.virtual.enabled=true`).
- **PostgreSQL 16**, the single system of record. Data access uses **Spring JDBC (`JdbcClient`) with explicit SQL. No JPA/Hibernate.**
  - Rationale for DECISIONS.md: the correctness argument rests on exact SQL semantics (`ON CONFLICT`, `FOR UPDATE` in a deterministic order, conditional `UPDATE` row counts). An ORM hides these, and its persistence context and dirty checking make accidental read-then-write easy.
- Connection pool: HikariCP. Migrations: **Flyway**, run on startup.
- Security: Spring Security as a stateless OAuth2 resource server with JWT HS256 (`NimbusJwtDecoder.withSecretKey`); tokens are issued with `NimbusJwtEncoder`.
- Observability: Spring Boot Actuator + Micrometer Prometheus registry. Structured JSON logging uses Spring Boot's built-in structured logging (logstash/ecs format) or logstash-logback-encoder.
- Tests: JUnit 5, AssertJ, **Testcontainers PostgreSQL** with `@ServiceConnection`, and ArchUnit to enforce layering.
- No Redis, no queues, no distributed locks. Postgres row locks and constraints ARE the concurrency control.
- No frontend. A JSON API only.

# JAVA / SPRING SPECIFICS (overrides any Go-specific wording elsewhere in this prompt)

Wherever this prompt mentions Go tooling, use the Java equivalent:
- pgx/pgxpool → JdbcClient/HikariCP
- slog → structured Logback
- `cmd/burst` → the `burst` Maven module
- `-race` / `make test-race` → `./mvnw verify` with stress-repeated concurrency tests
- golangci-lint → Spotless + Checkstyle
- `go vet` → compiler warnings as errors (`-Xlint:all -Werror`)

**Transactions & retry**
- Use `TransactionTemplate` with explicit `ISOLATION_READ_COMMITTED` for reserve and cancel.
- Don't put `@Transactional` on a method called from the same class, because the proxy self-invocation pitfall silently skips the transaction. Document this choice.
- The deadlock/serialization retry loop wraps the WHOLE transaction from outside it, so each retry is a fresh transaction. Decide retry by inspecting SQLSTATE (`40P01`, `40001`) on the root `SQLException`, not by guessing Spring's exception subclasses.
- Pass seat lists as `text[]` (`String[]` via `setObject`) and use `= ANY(?)`. Read `ON CONFLICT ... RETURNING` results as a query, and check `update()` row counts explicitly.

**JSON strictness (money safety)**
- Disable Jackson `ACCEPT_FLOAT_AS_INT`. It is enabled by default, which silently truncates `250.5` to `250`. Also enable `FAIL_ON_NULL_FOR_PRIMITIVES`.
- Keep `FAIL_ON_UNKNOWN_PROPERTIES=false` so a spoofed `user_id` body field is ignored, never bound, and never read.
- DTOs are Java `record`s with Jakarta Validation annotations. `price_paise` is a `long`. Use `Math.multiplyExact` for amounts.
- No Lombok.

**Error handling**
- Domain errors form a sealed exception hierarchy (`SeatTakenException`, `PerUserLimitException`, `IdempotencyKeyReusedException`, ...).
- ONE `@RestControllerAdvice` maps every exception to the standard error envelope, including framework exceptions:
  - unreadable JSON → 400
  - validation failure → 422
  - unknown route → 404
  - wrong method → 405
- Spring Security 401/403 must go through custom `AuthenticationEntryPoint` and `AccessDeniedHandler` that emit the same envelope.
- A catch-all handler logs the stack trace at ERROR and returns 500. It must never fire in the burst.

**Health, readiness, metrics paths**
- Expose the Actuator endpoints at the paths the assignment expects: `/healthz` (liveness), `/readyz` (readiness), and `/metrics` (Prometheus format). Use path mappings or thin controllers; keep the `/actuator/*` equivalents working too.
- Liveness must NOT include the DB check. Readiness must include it and return 503 when the DB is down.
- **Critical:** the readiness DB check and the DB-derived seat gauges must NOT borrow from the main Hikari pool. During a burst that pool is saturated, readiness would wait on it and time out, the platform would mark the app unhealthy and restart it, and that restart would produce exactly the 5xx we must avoid.
  - Give them a separate tiny DataSource (1–2 connections, ~1s timeouts).
  - Cache the seat-count query result for ≤1s so one scrape doesn't run it repeatedly.
  - Explain this in DECISIONS.md.
- On shutdown, readiness must flip to REFUSING_TRAFFIC before draining. Configure `server.shutdown=graceful` and a shutdown-phase timeout.

**Request ID & logs**
- A `OncePerRequestFilter` reads or generates `X-Request-ID`, puts it in MDC along with `user_id` and `show_id` once known, echoes it in the response header and error body, and clears MDC in `finally`.
- Log one access line per request with `status`, `latency_ms`, `outcome`, and decline reason.

**Throughput settings for a 20k burst** (tune and record the numbers)
- Hikari `maximum-pool-size`: the DB connection limit, minus the observability pool, minus headroom (typically 15–25 on hosted free tiers). Set `connection-timeout` to ~15–20s (pool-acquire wait) so excess requests queue instead of failing, and keep it below the platform proxy timeout.
- Tomcat `max-connections` and `accept-count` must be raised high enough that a burst queues rather than being refused.
- Set a statement timeout on the main pool. No aggressive `lock_timeout`.
- JVM: container-aware memory (`-XX:MaxRAMPercentage=75`), sized for the free-tier RAM limit. Measure startup time. Consider Spring Boot CDS/AOT cache for faster cold starts if startup is slow.
- Virtual threads: if running on Java 21 (not 25), confirm pgjdbc is ≥ 42.6 so driver locks don't pin carrier threads.

**Concurrency tests**
- Use a `@SpringBootTest(webEnvironment = RANDOM_PORT)` server, Java `HttpClient`, and a virtual-thread executor.
- Start all requests simultaneously with a `CountDownLatch` start gate.
- Mark the invariant tests `@RepeatedTest` (e.g. 5×) to flush out flaky races. Java has no race detector, so repetition under real contention is our proof.

**Containers**
- Dockerfile: multi-stage. A JDK image builds with `./mvnw -B package` (Maven deps cached in a layer); a JRE (or distroless java) nonroot image runs the extracted Spring Boot layered jar.
- The Docker HEALTHCHECK and the platform health check both target `/readyz`.

# WORKING AGREEMENT (follow strictly)

1. **Phased delivery with stop points.** Work in the phases listed at the end. At the end of each phase, stop, summarise what was done, show test results, and wait for my go-ahead. In Phase 0, produce the design and do NOT write application code until I approve it.
2. **Incremental, meaningful git commits.** Graders read the history. Make one logical change per commit using Conventional Commits (`feat(reserve): ...`, `test(concurrency): ...`, `chore(deploy): ...`). Never squash everything into one commit. Never fabricate or backdate history.
3. **Decision log.** Maintain `docs/DECISIONS.md` as an ADR-lite log. For every non-trivial choice, record the context, the decision, the alternatives rejected, and why. I will be questioned on these live, so explain each in plain words, including the failure mode it prevents.
4. **AI-usage log.** Maintain `docs/AI_LOG.md` as a running factual record of what I asked you to do, what you proposed, and where I overrode or changed your proposal. Do not write the final "AI usage" section of WRITEUP.md in my voice. Leave a clearly marked TODO for me to write it honestly from this log.
5. **No silent scope changes.** If the assignment is ambiguous, pick the safest interpretation, document it in DECISIONS.md, and tell me.
6. **Verify, don't assume.** After each phase, run the build, linters, and tests yourself, and show me the output. "It should work" is not acceptable.

# NON-NEGOTIABLE CORRECTNESS INVARIANTS

These must hold during AND after a 20k-request burst. Each one needs an automated test that proves it under real concurrency against real Postgres.

- **I1 – No double-sell.** A seat is never confirmed to two users. A storm of N users on one seat produces exactly one 201 and N-1 409s.
- **I2 – Zero 5xx.** Every decline is a 4xx domain outcome. Contention, lock waits, retries, and pool saturation must never surface as 500, 502, 503, or 504.
- **I3 – Reconciliation.** `available + held + confirmed == total_seats` holds to the unit at every instant.
- **I4 – Idempotency.**
  - The same `(user, idempotency_key)` produces exactly one reservation.
  - A replay returns the original.
  - The same key with a different body returns 409.
  - Concurrent duplicates with the same key also produce exactly one reservation.
- **I5 – Per-user limit.** A user firing 10 parallel reserves on a `per_user_limit=4` show ends with at most 4 seats, ever.
- **I6 – Token-derived identity.**
  - `user_id` comes only from the verified token. Any `user_id` field in a body is ignored and never read.
  - Only the owner can cancel a reservation.
  - A cancel can never release a seat that belongs to a different reservation.

# DOMAIN & API DESIGN

## Behavioural decisions (record each in DECISIONS.md)

- **Reservation model:** `POST /reserve` moves seats straight to `confirmed`. Release happens via an explicit `POST /reservations/{id}/cancel` that only the owner can call. The `held` status exists in the schema and in every count/metric, but it is always 0 in v1.
  - Design the code so that a "hold with TTL → confirm" flow can be added later: a state machine of `available → held → confirmed`, with expiry done as a conditional UPDATE on `status='held' AND hold_expires_at < now()`. Expect to be asked to add this live in the interview.
  - Write a short section on exactly how you would add it.
- **Multi-seat requests are all-or-nothing.** If any requested seat is unavailable, nothing is taken. Return 409 `seat_taken` with the list of unavailable seats.
- **Idempotency key:**
  - Accepted via the `Idempotency-Key` header or the `idempotency_key` body field. If both are present and they differ, return 422.
  - The key is required; if missing, return 400.
  - Keys are scoped per user, so two users can use the same key string.
  - The request fingerprint is `sha256(show_id + sorted unique seats)`, so `["A13","A12"]` and `["A12","A13"]` are the same request.
- **Replay response:** return **200** (not 201) with the original reservation body and the header `Idempotent-Replayed: true`. Count it as `declined{reason="idempotent_replay"}`.
  - Rationale for DECISIONS.md: the graders assert "exactly one 201 per hot seat", and the assignment itself classifies replay as a decline reason. A replay must not look like a second confirmation.
- **Declined requests are not memoised.** Only successful reservations are stored against the key. A retried request that was declined is re-evaluated, which is safe because a decline moves nothing. Document this.
- **Cancel:**
  - The owner gets 200 with the reservation now `cancelled`.
  - Cancelling an already-cancelled reservation returns 200 with its current state (cancel is idempotent).
  - A non-owner or unknown id gets **404**, so reservation existence doesn't leak.
  - After a cancel, the seats are re-bookable. A re-booking needs a new idempotency key.
- **Money:**
  - `price_paise` is int64.
  - `amount_paise = price_paise × seat_count` is computed server-side with an overflow check.
  - Reject any JSON number that has a fractional part or is ≤ 0, using strict decoding.

## Endpoints

| Method | Path | Auth | Success | Domain errors |
|---|---|---|---|---|
| POST | /auth/token | none (dev issuer, enabled by `ALLOW_DEV_TOKENS=true`) | 200 `{token}` for a given `user_id` | 400 |
| POST | /shows | admin (`role=admin` claim; admin tokens require `ADMIN_API_KEY`) | 201 show with every seat available | 400/401/403/422 |
| GET | /shows/{id} | public | 200 per-seat status + counts `{total, available, held, confirmed}` | 404 |
| POST | /shows/{id}/reserve | user | 201 reservation | 400, 401, 404, 409 `seat_taken`, 409 `per_user_limit`, 409 `idempotency_key_reused`, 422 |
| POST | /reservations/{id}/cancel | owner | 200 | 401, 404 |
| GET | /reservations/{id} | owner | 200 | 401, 404 |
| GET | /shows/{id}/reconcile | public | 200 `{ok, checks...}` | 404 |
| GET | /healthz | none | 200 liveness: process only, no dependency checks | — |
| GET | /readyz | none | 200 if DB ping (with ~1s timeout) succeeds and migrations are done, else **503** (fail closed) | — |
| GET | /metrics | none | Prometheus text format | — |

- **Error envelope, used for every error:** `{"error":{"code":"seat_taken","message":"...","details":{...}},"request_id":"..."}`.
- **Error mapping:** typed domain errors (`ErrSeatTaken`, `ErrPerUserLimit`, ...) are mapped to HTTP in exactly ONE place.
- **Validation of POST /shows:** seats must be non-empty, unique, and match `^[A-Z]{1,3}[0-9]{1,4}$` (document the pattern). Seat count is capped at a sane maximum such as 10,000. `per_user_limit` is optional (default 4, must be ≥1).
- **Validation of reserve:** seats must be non-empty and contain no duplicates (422). The count must be ≤ `per_user_limit` (409 `per_user_limit`). Every seat must exist in the show (422 `unknown_seat`).

# DATA MODEL (Postgres)

- `shows(id uuid pk, name text, price_paise bigint check > 0, per_user_limit int check >= 1, total_seats int, created_at)`
- `seats(show_id, seat_label, status text check in ('available','held','confirmed'), reservation_id uuid null, user_id text null, hold_expires_at timestamptz null, updated_at, PRIMARY KEY(show_id, seat_label))`
  - Constraint: `CHECK ((status = 'available') = (reservation_id IS NULL))`. This is defence in depth: the DB itself refuses an inconsistent seat.
- `reservations(id uuid pk, show_id, user_id, idempotency_key, request_hash, seats text[], amount_paise bigint, status text check in ('confirmed','cancelled'), created_at, updated_at, UNIQUE(user_id, idempotency_key))`
- `user_show_counts(show_id, user_id, seat_count int check >= 0, PRIMARY KEY(show_id, user_id))`
- Add indexes as needed, e.g. `seats(show_id, status)` and `reservations(show_id)`. Bulk-insert seats on show creation with `COPY` or `unnest`.

# THE ATOMIC DECISION (core of the grade — implement exactly, explain in WRITEUP)

Isolation level: **READ COMMITTED**, with explicit row locks and conditional writes. Do NOT use SERIALIZABLE: hot-seat storms would produce 40001 retry avalanches.

**Global lock order, used by every transaction:**
1. reservation/idempotency row
2. `user_show_counts` row
3. seat rows, sorted by `seat_label`

Reserve and cancel MUST both follow this order. Consistent ordering is how we avoid deadlocks.

Reserve runs in a single transaction:

```sql
-- 1. Claim the idempotency key. A concurrent same-key request blocks on the
--    unique index until this tx ends:
--      - if this tx commits, the other request hits the conflict and becomes a replay
--      - if this tx rolls back, the other request proceeds normally
INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
VALUES ($id, $show, $user, $key, $hash, $sorted_seats, $amount, 'confirmed')
ON CONFLICT (user_id, idempotency_key) DO NOTHING
RETURNING id;
-- If no row returned: ROLLBACK, then SELECT the existing row.
--   Same hash      -> 200 replay.
--   Different hash -> 409 idempotency_key_reused.

-- 2. Per-user limit as an atomic conditional upsert (no read-then-write)
INSERT INTO user_show_counts (show_id, user_id, seat_count) VALUES ($show, $user, $n)
ON CONFLICT (show_id, user_id) DO UPDATE
  SET seat_count = user_show_counts.seat_count + EXCLUDED.seat_count
  WHERE user_show_counts.seat_count + EXCLUDED.seat_count <= $limit
RETURNING seat_count;
-- If no row returned: ROLLBACK -> 409 per_user_limit.
-- ($n <= $limit is validated beforehand, so the first insert is always within the limit.)

-- 3. Lock seats in deterministic order. Losers queue on the row lock, then see
--    the committed 'confirmed' status and decline.
SELECT seat_label, status FROM seats
WHERE show_id = $show AND seat_label = ANY($sorted_seats)
ORDER BY seat_label
FOR UPDATE;
-- If any seat is not 'available': ROLLBACK -> 409 seat_taken {seats:[...]}.

UPDATE seats SET status = 'confirmed', reservation_id = $id, user_id = $user, updated_at = now()
WHERE show_id = $show AND seat_label = ANY($sorted_seats) AND status = 'available';
-- Assert rows_affected == n, otherwise ROLLBACK. This is defence in depth.
COMMIT;
```

- Do NOT use `NOWAIT` or `SKIP LOCKED` on seat locks. If the current lock holder rolls back (say, because another seat in its multi-seat request was taken), the hot seat could end with zero winners. Waiting on the lock guarantees exactly one winner. Explain this in DECISIONS.md.
- **Cancel:**
  1. `SELECT ... FROM reservations WHERE id=$id AND user_id=$token_user FOR UPDATE`. If no row, return 404.
  2. If the reservation is already cancelled, return 200.
  3. Decrement `user_show_counts` by the reservation's seat count.
  4. Lock the seats in sorted order, then run `UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL WHERE show_id=$s AND reservation_id=$id AND status='confirmed'`. Guarding on `reservation_id` makes it impossible to resurrect someone else's seat.
  5. Set the reservation's status to `cancelled`.
- **Deadlock/serialization safety net:** retry the whole transaction (bounded to 3 attempts, with jittered backoff) on SQLSTATE `40P01`/`40001`, and expose a `db_tx_retries_total` metric. If the retries are exhausted, the request must still not become a 5xx unless the DB is genuinely broken. Document how this case is handled.
- **Optional hot-path optimisation (only if load tests need it, and documented):** a cheap non-locking pre-read that immediately declines seats already `confirmed`. This is safe because the atomic transaction still makes the real decision; a stale read can only produce a decline, which is a legitimate race outcome. It never produces a double-sell.

# AVOIDING 5xx UNDER A 20K BURST (treat as a correctness requirement)

- **Pool sizing:** size `pgxpool` (`DB_MAX_CONNS`, default ~20–30) to fit the hosted Postgres connection limit. Excess requests queue for a connection rather than erroring. Pool acquire timeout and request deadline should be generous, about 15–25s, and must sit below the platform proxy timeout. Transactions must be short: no network calls and no logging I/O while locks are held.
- **Timeouts:** set HTTP server read/write/idle timeouts, and set Postgres `statement_timeout` on the pool. Do NOT set an aggressive `lock_timeout` that would turn contention into errors.
- **Middleware chain:** request-id → panic recovery (log the stack; this should never fire) → structured access log → metrics → auth → handler.
- **Graceful shutdown on SIGTERM:** readiness flips to 503 first, then in-flight requests drain, then the pool closes.
- **Startup:** retry the DB connection with backoff and run migrations under an advisory lock. Readiness stays 503 until both are done, so the service survives cold starts and slow DB wake-ups.
- **Load test ourselves:** run the burst tool against local compose and against the deployed URL. Identify and fix the bottleneck (pool, proxy, CPU) before calling the work done. Record the numbers in the WRITEUP.

# OBSERVABILITY

## Metrics (`/metrics`, Prometheus)

- `seatres_reservations_confirmed_total` (counter)
- `seatres_reservations_declined_total{reason}` (counter). `reason` is one of `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `validation`, `unauthorized`, `not_found`.
- `seatres_reservations_cancelled_total` (counter)
- `seatres_seats{show_id,status}` and `seatres_seats_available{show_id}` (gauges). Compute them **from the DB at scrape time** with a custom collector, so they always reconcile with API state.
- `http_requests_total{route,method,code}` and `http_request_duration_seconds{route}` (histogram). Use the route pattern as the label, never the raw path, to keep cardinality bounded.
- `db_pool_*` (acquired, idle, total, wait count, wait duration) and `db_tx_retries_total`.
- Run a **single app instance** so in-process counters reconcile with the DB. Document why, and what changes with N replicas: aggregate with `sum()` and accept that counters reset on restart while DB-derived gauges stay authoritative.

## Reconciliation endpoint

`GET /shows/{id}/reconcile` checks all of the following and returns pass/fail per check plus the raw numbers:
- `available + held + confirmed == total_seats`
- confirmed seats == sum of seat counts over confirmed reservations
- sum of `user_show_counts` == confirmed seats
- no user exceeds the limit
- no seat is referenced by two active reservations

## Logs

- JSON via slog, one line per request.
- Fields: `request_id`, `method`, `route`, `status`, `latency_ms`, `user_id`, `show_id`, `outcome` or decline reason, `reservation_id`.
- Accept an incoming `X-Request-ID` or generate one; echo it in the response header and in the error body.
- Never log tokens or secrets.
- Log levels: domain declines at INFO, unexpected errors at ERROR.

## Deliverables

- `docs/OBSERVABILITY.md`: how to watch a burst live (curl/watch on `/metrics`, the platform log viewer link), example PromQL queries, and the 2am paging list. The paging list should cover at least: any 5xx; a reconcile check failing; `db_tx_retries` spiking; readiness flapping; p99 latency over SLO; DB pool wait saturation.
- Optional: a Grafana dashboard JSON in `deploy/grafana/`.

# CODE QUALITY & STRUCTURE

Multi-module Maven build:

```
pom.xml                          parent: Java version, dependency management, plugins
seat-service/
  src/main/java/<base.package>/
    SeatReservationApplication.java
    config/          typed @ConfigurationProperties (validated, fail fast), Jackson, Security, Hikari/obs DataSource beans
    api/             @RestControllers, request/response records, @RestControllerAdvice, request-id filter
    domain/          entities/records, sealed domain exceptions, validation rules, request fingerprinting (pure, no Spring, no I/O)
    service/         use cases: CreateShow, ReserveSeats, CancelReservation, GetShow, Reconcile — own the TransactionTemplate + retry
    persistence/     repositories using JdbcClient with explicit SQL; tx retry helper (SQLSTATE-based)
    security/        JWT encode/decode, role mapping, CurrentUser accessor (identity ONLY from SecurityContext)
    observability/   metric names/constants, reservation outcome recorder, DB-backed seat gauges (separate pool)
  src/main/resources/
    application.yml, db/migration/ (Flyway V1__..., V2__...)
  src/test/java/...  unit, integration, concurrency (Invariant I1–I6 tests), ArchUnit rules
burst/                            standalone load + correctness tool (Java HttpClient + virtual threads), runnable fat jar
burst.sh, Makefile, Dockerfile, docker-compose.yml, .env.example, deploy config, docs/
```

- **Dependency direction:** `api → service → persistence`. `domain` depends on nothing; enforce this with an ArchUnit test.
- **Controllers are thin:** parse, authenticate, and delegate. Business rules live in `service` and `domain`. SQL lives only in `persistence`.
- **Code style:**
  - constructor injection only (no field `@Autowired`)
  - final fields, immutable records
  - no static mutable state
  - no magic numbers (use `@ConfigurationProperties` or named constants)
  - small focused methods
  - comments explain the *why*, especially on every lock, conditional write, and the lock order
- **Configuration:** all of it comes from env vars mapped to typed properties: `PORT`, `DATABASE_URL` (or `SPRING_DATASOURCE_*`), `JWT_SECRET`, `ADMIN_API_KEY`, `ALLOW_DEV_TOKENS`, `DB_MAX_CONNS`, `LOG_LEVEL`. Validate them at startup and fail fast with a clear message.
  - Note: platform `DATABASE_URL` values are usually in `postgres://user:pass@host/db` form. Convert them to a JDBC URL in config code rather than requiring manual edits.
- **Tooling:** Spotless (google-java-format or palantir) and Checkstyle must pass, with `-Xlint:all -Werror`. Makefile targets: `run`, `test`, `lint`, `up`, `down`, `burst URL=...`; each wraps `./mvnw` or docker compose.
- **CI** (GitHub Actions, JDK via setup-java with Maven cache): `./mvnw -B verify` (Testcontainers runs on the hosted runner's Docker), Spotless check, `docker build`. CI must be green.

- **Dependency direction:** `httpapi → service → store`, and `domain` depends on nothing. Constructor injection only; no globals except the metrics registry. Define interfaces where they are consumed. Propagate `context.Context` everywhere.
- **Small focused functions** with no magic numbers (config or named constants). Keep comments for the *why*, especially on every lock and conditional write.
- **Tooling:** `gofmt`, `go vet`, and `golangci-lint` (with a sensible config) all clean. `Makefile` targets: `run`, `test`, `test-race`, `lint`, `up`, `down`, `burst`.
- **CI** (GitHub Actions): lint, test with `-race` against a Postgres service, `docker build`. CI must be green.

# TESTING STRATEGY (proof, not decoration)

- **Unit tests:** validation, fingerprinting (order-insensitive), money math and overflow, error mapping, JWT verification (bad signature, expired, wrong role).
- **Integration tests** (testcontainers, real Postgres) for every endpoint's happy and decline paths.
- **Concurrency tests** with goroutines and a barrier, so requests start simultaneously, run under `-race`:
  - 500 users on one seat: exactly one success, 499 `seat_taken`, zero errors.
  - Overlapping multi-seat requests in opposite orders (e.g. `[A1,A2]` vs `[A2,A1]`): no deadlock surfaced, all-or-nothing preserved.
  - 50 concurrent identical same-key requests: one reservation, the rest are replays.
  - Same key with different seats concurrently: one success; the others are 409 `idempotency_key_reused` or replays, consistently.
  - One user firing 10 parallel single-seat reserves with limit 4: exactly ≤4 seats, and `user_show_counts` matches.
  - Cancel racing reserve on the same seat: seats are never resurrected or double-assigned.
  - Spoofing: a body with another `user_id` acts only as the token user; a cancel by a non-owner gets 404 and the seat stays confirmed.
  - After every test, assert all reconcile checks pass.
- **Test name convention:** name each test after the invariant it proves (`TestI1_HotSeatStorm_ExactlyOneWinner`).

# BURST TOOL (`./burst.sh <BASE_URL>` → runs the burst module, also `make burst URL=...`)

- Must work from a clean clone with only Docker or Go installed. Document both ways to run it.
- Flags: `--concurrency`, `--total`, `--seats`, `--hot-seats`, `--storm-size`, `--users`.
- Sensible defaults: around 1000 seats, 5 hot seats × 500 users each, ~20k total requests. Use HTTP keep-alive with a tuned transport.
- Scenarios, run concurrently where realistic:
  1. Create a fresh show with an admin token; mint user tokens via `/auth/token`.
  2. Hot-seat storm: many users per hot seat.
  3. General stampede: random seats and random 1–4 seat requests.
  4. Idempotent retries: same key fired N times; same key with different seats.
  5. Per-user limit: one user fires 10 parallel reserves.
  6. Spoof attempt: a body `user_id` mismatched with the token.
  7. Cancel + re-book cycle.
  8. Poll `GET /shows/{id}` throughout to sample the invariant **during** the burst.
- **Output:** a clear table of the outcome distribution (201 / 200-replay / 409-by-reason / other 4xx / **5xx** / transport errors), latency p50/p95/p99, per-hot-seat winner counts, final show counts, reconcile result, and the `/metrics` deltas compared with observed outcomes.
- **Exit non-zero** if any of the following happens: any 5xx; any hot seat with ≠1 winner; the invariant is violated at any sample; metrics don't reconcile; a user exceeds the limit; a replay creates a second reservation.

# CONTAINERS & DEPLOY

- **Dockerfile:** multi-stage build into a distroless/nonroot final image, small and pinned. Configure entirely via env vars: `PORT`, `DATABASE_URL`, `JWT_SECRET`, `ADMIN_API_KEY`, `ALLOW_DEV_TOKENS`, `DB_MAX_CONNS`, `LOG_LEVEL`.
- **docker-compose.yml:** app + Postgres 16 with healthchecks, app `depends_on: condition: service_healthy`, and a `.env.example`. `docker compose up --build` from a fresh clone must work with zero manual steps. Verify this by actually cloning into a temp dir and running it.
- **Platform:** choose one that does NOT sleep on idle, or configure it so (Fly.io with `min_machines_running=1`, or Railway). Put the app and Postgres in the same region on a private network. Avoid Render free web services, whose spin-down produces 502s exactly when graders hit the URL. Add the deploy config (`fly.toml` / `railway.json`) to the repo and wire the platform health check to `/readyz`. If the platform lacks a needed capability, tell me before choosing.
- **Deploy early (end of Phase 2),** then redeploy on each phase. Run the burst tool against the live URL and record the results. Keep the burst sized so it doesn't exhaust free-tier DB connections; tune the pool to the real limit.
- **Secrets** stay in platform env vars only, never committed.

# DOCS

- **README.md:**
  - one-paragraph overview and live URL
  - quickstart (compose)
  - curl examples for every endpoint, including how to get user and admin tokens
  - burst-script usage and sample output
  - metrics and logs access links
  - a design summary that links to WRITEUP.md
- **WRITEUP.md** covers every topic the assignment lists:
  - the atomic decision: name the exact mechanism, explain why it's race-free, and explain deadlock avoidance via lock ordering
  - idempotency: storage, exactly-once, same-key-different-body, concurrent duplicates
  - holds & expiry: the chosen model, plus a concrete design for TTL holds
  - consistency vs availability under a partition: we choose consistency; if the DB is unreachable we fail closed (readiness 503, no reservations) rather than risk a double-sell
  - observability and the 2am paging list
  - measured load-test results
  - a TODO placeholder for MY "AI usage" section
  - what's next: TTL holds plus a payment step, per-show sharding, a read replica for GET, a queue/waiting room for mega on-sales, multi-instance metrics

# PHASES (stop after each)

- **Phase 0 – Design (no app code).** Restate the requirements in your own words and list every ambiguity together with the resolution you chose. Present the schema, the transaction pseudocode, the lock order, the status-code table, and the metrics list, and seed DECISIONS.md. Wait for my approval.
- **Phase 1 – Skeleton.** Repo layout, config, logging, healthz/readyz, migrations, Dockerfile, compose, Makefile, and CI. Commit.
- **Phase 2 – Shows + auth + reserve (single seat) + idempotency.** Include integration and concurrency tests for I1, I2, I4, I6, then deploy. Commit in steps.
- **Phase 3 – Multi-seat all-or-nothing, per-user limit, cancel, reconcile endpoint.** Include concurrency tests for I3 and I5, plus the cancel races.
- **Phase 4 – Metrics, the DB collector, and structured access logs.** Verify that the metrics reconcile.
- **Phase 5 – Burst tool.** Run it locally, then against the live URL; fix bottlenecks until the burst finishes with zero 5xx and every check green. Record the numbers.
- **Phase 6 – Hardening & docs.** Clean-clone verification, cold-start test (restart the service, hit it immediately), README, WRITEUP, OBSERVABILITY. Do a final self-review against the checklist below.

# DEFINITION OF DONE (verify every item and show evidence)

- [ ] `git clone` → `docker compose up --build` works with no manual steps; `./mvnw` verify passes; CI is green.
- [ ] The live URL is up, `/readyz` returns 200, and readiness returns 503 when the DB is unreachable (tested locally by stopping Postgres).
- [ ] The burst against the live URL shows zero 5xx, exactly one winner per hot seat, the invariant held at every sample, metrics reconciled, and the limit held.
- [ ] Idempotency: replay → 200 with the same `reservation_id`; same key with different body → 409.
- [ ] Spoofed body identity has no effect; non-owner cancel → 404.
- [ ] Logs are JSON with a request_id, and the log access method is documented.
- [ ] All money is int64 paise, with no float anywhere in the codebase (grep to prove it).
- [ ] README, WRITEUP, DECISIONS, OBSERVABILITY, and AI_LOG are complete. The AI-usage section is left for me.
- [ ] The commit history is incremental and readable.

Begin with Phase 0. Read `docs/ASSIGNMENT.md` fully first.