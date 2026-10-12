# Decision log (ADR-lite)

Each entry: **Context** → **Decision** → **Alternatives rejected** → **Failure mode prevented**.
Status values: *accepted* (in force), *proposed* (pending approval), *superseded*. ADR-001…026 were approved at the end of Phase 0 (2026-10-07).

---

## ADR-001 — Java 21 LTS + Spring Boot 4.1.1
*Status: accepted*

- **Context:** Brief prefers Java 25 if the toolchain supports it. The development machine has JDK 21.0.4; start.spring.io's latest GA on 2026-10-07 is Boot 4.1.1, which supports 17/21/25.
- **Decision:** Java 21 LTS, Spring Boot 4.1.1 (Framework 7, Jackson 3). Docker build/run images pinned to Temurin 21. pgjdbc ≥ 42.7.
- **Alternatives:** Java 25 (needs a local JDK install; no feature we need); Boot 3.5 (older line, would avoid Jackson 3 package moves but the brief asks for latest GA).
- **Failure mode prevented:** a build that passes in CI/Docker but not locally (or vice versa) because of mismatched JDKs. pgjdbc ≥ 42.6 replaced `synchronized` with `ReentrantLock`, so driver I/O doesn't pin virtual-thread carriers on Java 21.

## ADR-002 — Railway as the deploy platform
*Status: accepted*

- **Context:** Need an always-on public URL with Postgres on a private network, without free-tier spin-down (spin-down produces 502s exactly when graders arrive). Checked 2026-10-07: Fly.io trial is 2 h of machine time/7 days, managed Postgres from $38/month; Railway trial is $5 credit / 30 days with no card, then Hobby $5/month.
- **Decision:** Railway: one app service (Dockerfile) + one Postgres service in the same project/region, connected over the private network; config in `railway.json`; health check path `/readyz`; restart policy `ON_FAILURE`.
- **Alternatives:** Fly.io (trial too short, Postgres too expensive or self-managed); Render free (sleeps → 502s).
- **Known platform facts we design around:** Railway health checks run **only during deploy** (not continuously); edge proxy limit 10,000 concurrent connections and 5 min per request; Postgres `max_connections = 100`; trial plan 1 GB RAM / 2 vCPU per service.
- **Failure mode prevented:** graders hitting a sleeping service and seeing 502/504; a DB across the public internet adding latency and connection fragility.
- **Risk:** the trial lasts 30 days; if grading is later, upgrade to Hobby ($5/month).

## ADR-003 — Postgres is the only concurrency control
*Status: accepted*

- **Context:** Correctness must hold under a 20k stampede and be explainable.
- **Decision:** No Redis, queues, distributed locks or in-process locks. Row locks, unique indexes, CHECK constraints and conditional writes in one Postgres are the entire mechanism.
- **Alternatives:** Redis `SETNX` seat locks (two systems of record → split-brain between Redis and DB on crash); in-JVM locks (break with >1 instance and on restart).
- **Failure mode prevented:** a seat "locked" in one system but sold in another; any correctness argument that depends on two stores agreeing.

## ADR-004 — Spring `JdbcClient` with explicit SQL, no JPA
*Status: accepted*

- **Context:** The correctness argument rests on exact SQL semantics: `ON CONFLICT ... RETURNING`, `FOR UPDATE` in a deterministic order, conditional `UPDATE` row counts.
- **Decision:** `JdbcClient` + hand-written SQL in the `persistence` package only. Seat lists bound as `text[]` (`String[]` via `setObject`) and matched with `= ANY(?)`.
- **Alternatives:** JPA/Hibernate (persistence context and dirty checking make an accidental read-then-write easy; flush order and lock SQL are not what you read in the code); jOOQ (fine, but another dependency and DSL to defend).
- **Failure mode prevented:** a "load entity, check status in Java, save" race that double-sells under concurrency.

## ADR-005 — READ COMMITTED + explicit row locks, not SERIALIZABLE
*Status: accepted*

- **Context:** Hot-seat storms put hundreds of transactions on one row.
- **Decision:** `ISOLATION_READ_COMMITTED` with `SELECT ... FOR UPDATE` and conditional writes. Contended transactions **wait** for the lock and then re-read the committed row.
- **Alternatives:** SERIALIZABLE (every loser of a hot seat aborts with 40001 → retry avalanche, more DB load exactly when we have least headroom, and retries can exhaust → errors); optimistic version columns (same avalanche at the application level).
- **Failure mode prevented:** 40001 storms turning into latency explosions or 5xx.

## ADR-006 — One global lock order
*Status: accepted*

- **Context:** Reserve and cancel touch the same three kinds of rows; inconsistent ordering between two transactions is the classic deadlock.
- **Decision:** Every transaction locks (1) the reservation/idempotency row, (2) the `user_show_counts` row, (3) seat rows `ORDER BY seat_label` — always in that order.
- **Alternatives:** lock seats first (cancel would need the reservation first to know which seats → inverted order vs reserve); rely on deadlock detection + retry (works, but costs a 1 s `deadlock_timeout` stall each time and makes retries normal rather than exceptional).
- **Failure mode prevented:** deadlocks between `[A1,A2]` and `[A2,A1]`, and between a cancel and a reserve of the same seats.

## ADR-007 — Wait on seat locks; never `NOWAIT` / `SKIP LOCKED` for buyers
*Status: accepted*

- **Context:** It's tempting to fail fast on a locked seat.
- **Decision:** Plain `FOR UPDATE`.
- **Alternatives:** `NOWAIT` (immediate 409); `SKIP LOCKED`.
- **Failure mode prevented:** the *zero-winner* hot seat. If the transaction holding seat A1 is a multi-seat request that rolls back because A2 was taken, every `NOWAIT` competitor for A1 has already declined, so A1 stays unsold despite 500 buyers. Waiting means the next queued transaction sees A1 still `available` and wins. (`SKIP LOCKED` is fine for a background expiry sweeper, where a skipped row is simply retried next sweep.)

## ADR-008 — Idempotency claimed first, via the unique index
*Status: accepted*

- **Context:** I4 requires exactly one reservation per `(user, key)`, including for concurrent duplicates.
- **Decision:** The first statement of reserve is `INSERT INTO reservations ... ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id`. A concurrent same-key insert blocks on the unique-index entry until the first transaction ends; on commit it conflicts (→ replay or 409 reused), on rollback it proceeds. Fingerprint = `sha256(show_id + "\n" + sorted unique seats joined by ",")`, so seat order doesn't matter.
- **Alternatives:** a separate `idempotency_keys` table written after the work (window where two requests both see "no key"); check-then-insert (race); an in-memory cache (lost on restart, wrong with N instances).
- **Failure mode prevented:** two reservations for one retried client request; different behaviour depending on which duplicate arrived first.

## ADR-009 — Replay returns 200, not 201
*Status: accepted*

- **Decision:** Replay → **200** with the original reservation body (current state) and `Idempotent-Replayed: true`; counted as `declined{reason="idempotent_replay"}`.
- **Why:** Graders assert "exactly one 201 per hot seat", and the brief classifies a replay as a decline reason. A replay must not look like a second confirmation.
- **Alternative:** 201 for replays (Stripe-style "identical response") — rejected because it breaks the one-201-per-seat assertion.

## ADR-010 — Declined requests are not memoised
*Status: accepted*

- **Decision:** Only a committed successful reservation is bound to the key. A declined attempt rolls back, including its key claim; a retry with the same key is evaluated afresh.
- **Why safe:** a decline moved nothing, so re-evaluating cannot duplicate anything. A retry might succeed later (e.g. after a cancel freed the seat) — that is the correct current answer.
- **Alternative:** store declines too — needs a second write path outside the rolled-back transaction, and would freeze a stale "seat_taken" forever.

## ADR-011 — Reserve confirms directly; `held` exists but is 0 in v1
*Status: accepted*

- **Decision:** `POST /reserve` moves seats `available → confirmed`; release is an explicit owner-only cancel. `held` is in the schema, the counts, the gauges and the reconcile check, always 0 in v1. The state machine and conditional-UPDATE expiry design for TTL holds is in DESIGN.md §6.4.
- **Why:** there is no payment step to wait for; holds would add an expiry path that can only add risk to the graded invariants. The schema is ready so holds are an additive change.

## ADR-012 — Per-user limit as an atomic conditional upsert
*Status: accepted*

- **Decision:** `INSERT ... ON CONFLICT DO UPDATE SET seat_count = seat_count + n WHERE seat_count + n <= limit RETURNING seat_count`; no row → 409 `per_user_limit`. Cancel decrements. `CHECK (seat_count >= 0)`.
- **Alternatives:** `SELECT count(*) FROM seats WHERE user_id=...` then insert (read-then-write race: 10 parallel requests all read 0); advisory lock per user (works but is another lock type outside the documented order).
- **Failure mode prevented:** a user firing 10 parallel requests ending with >4 seats.

## ADR-013 — SQLSTATE-based retry, outside the transaction
*Status: accepted*

- **Decision:** `TxRetry` wraps the whole `TransactionTemplate.execute(...)`, so each attempt is a fresh transaction. It walks the cause chain to the root `SQLException` and retries only `40P01` (deadlock) and `40001` (serialization), max 3 attempts, jittered exponential backoff (10–50 ms × 2ⁿ). Each retry increments `db_tx_retries_total{sqlstate,operation}`.
- **If retries are exhausted:** **409** `contention` with `Retry-After: 1`, logged at ERROR and paged on. The DB is healthy and nothing was written, so a 5xx would be a lie; with ADR-006 this path should never execute.
- **Alternatives:** retry inside the transaction (an aborted Postgres transaction can't continue); match on Spring exception subclasses (they've shifted between versions; SQLSTATE is the stable contract).

## ADR-014 — Overload and DB-down mapping
*Status: accepted*

- **Decision:**
  - Pool acquire timeout or `statement_timeout` (`57014`) → **429** `server_busy` + `Retry-After`. Designed never to occur in the burst (pool queueing, ADR-024); if it does it is paged on.
  - Connectivity failure (SQLSTATE class `08`, connection refused) → **503** `service_unavailable`, and `/readyz` is 503. This is "the DB is genuinely broken": we choose consistency and fail closed rather than guess.
- **Alternatives:** 503 for everything DB-related (violates I2 under mere saturation); serve reservations from a cache when the DB is down (risks double-sell).

## ADR-015 — Separate observability DataSource
*Status: accepted*

- **Decision:** `/readyz`'s DB ping and the scrape-time seat gauges use a second Hikari pool (2 connections, 1 s connection/validation timeout). The seat-count query result is cached ≤ 1 s.
- **Why:** during a burst the main pool is saturated by design (requests queue for up to 20 s). A readiness check queued behind them would time out, the orchestrator would mark the instance unhealthy and restart it, and that restart would produce exactly the 5xx we must avoid. Railway only health-checks at deploy time, but Docker/compose HEALTHCHECK polls continuously, and a scrape that blocks behind the burst blinds us exactly when we need to watch.
- **Alternatives:** share the main pool (above failure); no DB check in readiness (would route traffic to an instance that can't reach the DB).

## ADR-016 — Cancel semantics
*Status: accepted*

- **Decision:** owner → 200 `cancelled`; re-cancel → 200 current state; non-owner or unknown → **404**. The seat release is `UPDATE ... WHERE reservation_id=$id AND status='confirmed'` with row-count assertion. Re-booking needs a new idempotency key.
- **Failure mode prevented:** leaking reservation existence to other users (403 would confirm it exists); a cancel releasing a seat that has since been bought by someone else.

## ADR-017 — Seat labels: pattern and `COLLATE "C"`
*Status: accepted*

- **Decision:** labels match `^[A-Z]{1,3}[0-9]{1,4}$` (row letters + seat number: `A1`, `AB12`, `ZZZ9999`); column is `text COLLATE "C"`.
- **Why:** the lock order is "sorted by seat_label". With `COLLATE "C"` Postgres `ORDER BY` is byte order, identical to Java `String.compareTo` for ASCII, and independent of the server's locale. One global order, no locale surprises between environments.
- **Note:** sort is lexical (`A10` < `A2`); that's fine — it only needs to be *consistent*, not human-natural.

## ADR-018 — Fixed validation order on reserve
*Status: accepted*

- **Decision:** 401 → 400 unreadable JSON → 400 key missing → 422 key mismatch/malformed → 422 shape (empty, duplicates, pattern) → 404 show → 409 `per_user_limit` (n > limit) → 422 `unknown_seat` → transaction.
- **Why:** deterministic answers when several errors apply, and the cheap/no-DB checks run first. A replay of a valid original passes every pre-check identically, so ordering never turns a legitimate replay into an error.

## ADR-019 — Single app instance
*Status: accepted*

- **Decision:** run one replica.
- **Why:** in-process counters (`confirmed_total`, …) then reconcile exactly with the DB. Correctness doesn't depend on it (all coordination is in Postgres).
- **With N replicas:** aggregate counters with `sum()` across instances; counters reset on restart, so reconcile against DB-derived gauges, which stay authoritative. Pool size per instance must shrink so `N × DB_MAX_CONNS + obs` fits `max_connections`.

## ADR-020 — `TransactionTemplate`, not `@Transactional`
*Status: accepted*

- **Decision:** services own an explicit `TransactionTemplate` (READ COMMITTED), wrapped by `TxRetry`.
- **Why:** `@Transactional` on a method called from the same class is silently skipped (proxy self-invocation), and the retry loop must be *outside* the transaction, which is awkward with annotations. Explicit code shows exactly where the transaction begins and ends.

## ADR-021 — Strict JSON for money
*Status: accepted*

- **Decision:** Jackson `ACCEPT_FLOAT_AS_INT` disabled (`250.5` → 400, not silently `250`); `FAIL_ON_NULL_FOR_PRIMITIVES` enabled; `FAIL_ON_UNKNOWN_PROPERTIES` **disabled** so a spoofed `user_id` body field is ignored and never bound. Set explicitly even where Jackson 3 defaults already match. `price_paise` is `long`; `amount = Math.multiplyExact(price, n)`; no `float`/`double`/`BigDecimal` for money anywhere (CI greps for it).
- **Failure mode prevented:** silent truncation of money; overflow wrap-around; identity spoofing via body fields.

## ADR-022 — Dev token issuer and admin key
*Status: accepted*

- **Decision:** `POST /auth/token` exists only when `ALLOW_DEV_TOKENS=true` (else 404). User tokens: any valid `user_id`. Admin tokens (`role=admin`) additionally require `X-Admin-Key` matching `ADMIN_API_KEY` (constant-time compare) → 401 `invalid_admin_key` otherwise. HS256 with `JWT_SECRET` (≥ 32 bytes, validated at startup). Tokens carry `sub`, `role`, `iat`, `exp` (1 h).
- **Why:** graders need to mint tokens without a real IdP, but a public URL must not hand out admin rights.

## ADR-023 — Base package `com.paytmmoney.seatreservation`
*Status: accepted*

- **Decision:** groupId `com.paytmmoney`, modules `seat-service` and `burst`, base package `com.paytmmoney.seatreservation`.
- **Alternative:** `io.github.viddy09.seatreservation` (strict reverse-domain-you-own convention). Chosen name reads better for the assignment context; trivial to rename before Phase 1.

## ADR-024 — Pool, timeouts and server sizing (initial)
*Status: accepted — numbers re-recorded after Phase 5 load tests*

- Main pool 20 (`DB_MAX_CONNS`), obs pool 2; Postgres `max_connections` 100 leaves room for Flyway, psql and a redeploy overlap.
- Hikari `connection-timeout` 20 s (queue, don't fail); `statement_timeout` 15 s on the main pool; no `lock_timeout`.
- Tomcat `max-connections` 20000, `accept-count` 10000; virtual threads on.
- Graceful shutdown, 30 s phase timeout.
- `-XX:MaxRAMPercentage=75` for a 1 GB container.
- **Why 20 and not 90:** Postgres on 2 vCPU peaks at a few × cores active connections; hot-row transactions serialise anyway, so more connections add lock-queue length and memory, not throughput.

## ADR-025 — Bounded gauge cardinality
*Status: accepted*

- **Decision:** `seatres_seats{show_id,status}` emitted for the 50 most recently created shows (configurable).
- **Why:** every burst run creates a show; unbounded `show_id` labels grow the scrape and Prometheus TSDB forever. Reconcile endpoint and `GET /shows/{id}` cover any show.

## ADR-026 — Show and seat set are immutable; pre-transaction reads are safe
*Status: accepted*

- **Decision:** there is no endpoint or SQL that updates a show's price/limit or adds/removes seats. Reserve reads price, limit and the seat-label set before the transaction (404 / 409 `per_user_limit` / 422 `unknown_seat`) without locks.
- **Why safe:** immutable data can't change between the read and the transaction. Seat *status* is never trusted from a pre-read; it's decided under `FOR UPDATE`.
- **If shows become mutable later:** move these reads inside the transaction and lock the show row first (new step 0 in the lock order).

## ADR-027 — Runtime image: Temurin 21 JRE on Alpine, non-root
*Status: accepted (Phase 1)*

- **Context:** The brief asks for a JRE or distroless non-root runtime and a Docker `HEALTHCHECK` on `/readyz`.
- **Decision:** `eclipse-temurin:21.0.12_8-jre-alpine`, user uid 10001, Spring Boot layered jar extracted with `jarmode=tools`. The health check uses busybox `wget`. Image tags are pinned to an exact JDK build.
- **Alternatives:** distroless java21 (no shell or HTTP client, so a `HEALTHCHECK` would need a second JVM started every 10 s, costing ~100 MB and CPU on a 1 GB box); full JDK image (larger attack surface, slower pulls).
- **Failure mode prevented:** a health check that can't run, or one that steals memory from the app mid-burst.

## ADR-028 — Startup ordering: migrations before the port opens
*Status: accepted (Phase 1)*

- **Decision:** Flyway runs during context refresh, before Tomcat binds the port. Until migrations finish, `/readyz` is unreachable (connection refused), which every orchestrator treats as not ready. Flyway retries the DB connection 30 × 2 s (plus Hikari's 20 s acquire wait per attempt) and serialises concurrent migrators with a Postgres advisory lock. Railway's deploy health check waits up to 300 s.
- **Why:** "ready" can never be reported against a half-migrated schema, and a slow DB wake-up delays startup rather than crash-looping it.
- **Measured locally (compose):** process start → ready in ~13 s, of which Flyway V1 is ~0.5 s.

## ADR-029 — Docker build packages only; CI runs the tests
*Status: accepted (Phase 1)*

- **Decision:** The Dockerfile runs `package -DskipTests` (and skips Spotless/Checkstyle). CI runs `./mvnw -B verify` (all tests against Testcontainers, lint) and then `docker build`.
- **Why:** Testcontainers needs a Docker daemon, which isn't available inside a Docker build (or on Railway's builder). Running tests in CI before the image build keeps the guarantee without Docker-in-Docker.

## ADR-030 — Errors outside Spring MVC also use the envelope
*Status: accepted (Phase 1)*

- **Decision:** `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`, so every Spring MVC exception keeps its correct status (404/405/415/…) but returns our envelope. A custom `ErrorController` replaces Boot's `/error` for errors raised in filters or via `sendError`. Static resource mapping is off, so unknown paths are 404 from our handler. The request-id filter also runs on error dispatches so the id survives.
- **Failure mode prevented:** clients (and the burst tool) seeing two different error shapes depending on where an error originated.

## ADR-031 — Protect identity routes explicitly; everything else is public
*Status: accepted (Phase 2)*

- **Decision:** the filter chain requires `ROLE_ADMIN` for `POST /shows`, authentication for `POST /shows/*/reserve`, `POST /reservations/*/cancel` and `GET /reservations/*`, and permits everything else.
- **Why:** with `anyRequest().authenticated()`, an unknown path would answer 401 instead of 404, and wrong-method probes would answer 401 instead of 405, which misreports what happened. The protected set is small and named in one place.
- **Trade-off:** a new endpoint is public until added to the list; reviewers must check `SecurityConfig` when adding routes. Tests assert 401/403 for every protected route.

## ADR-032 — Token validation beyond the signature
*Status: accepted (Phase 2)*

- **Decision:** besides HS256 signature, `exp` and `nbf`, the decoder requires `sub` to match `^[A-Za-z0-9_.@-]{1,64}$` and `role` ∈ {`user`, `admin`}. Otherwise 401 before any application code runs. Identity is read only by `CurrentUser` (ArchUnit-enforced).
- **Secure defaults:** `JWT_SECRET` has no default and must be ≥ 32 bytes (startup fails otherwise); `ALLOW_DEV_TOKENS` defaults to `false` (the issuer route then doesn't exist → 404). Compose and the graded deployment set it to `true`.

## ADR-033 — Validation lives in the domain, in a fixed order
*Status: accepted (Phase 2)*

- **Decision:** request records are raw (boxed fields, no Bean Validation annotations). `NewShow.of`, `SeatSelection` and `IdempotencyKey.resolve` validate in the order of ADR-018 and throw sealed domain exceptions.
- **Why:** `@Valid` runs before the controller, which would make a body-shape 422 win over a missing-key 400 and break the documented precedence. Domain validation is also unit-testable without Spring.
- **Number strictness:** `250.5`, `"250"` and `1e3` for `price_paise` are 400 `invalid_json` (Jackson `ACCEPT_FLOAT_AS_INT` and `ALLOW_COERCION_OF_SCALARS` off); `0`/negative/missing are 422 `validation`.

## ADR-035 — Reconcile is one SQL statement
*Status: accepted (Phase 3)*

- **Decision:** `GET /shows/{id}/reconcile` computes every number (seat states, seats in confirmed reservations, sum of user counters, users over the limit, seats listed by two reservations, taken seats without a matching reservation) in a single CTE query, then evaluates the checks in the domain.
- **Why:** Postgres gives one statement one snapshot, even at READ COMMITTED. Separate queries polled mid-burst could each see a different commit and report a false mismatch. A REPEATABLE READ transaction would also work but adds nothing over one statement.
- **Proven:** a test polls reconcile 60 times while 240 reserves are in flight; every sample is `ok`. Another test corrupts a counter directly and confirms reconcile reports `ok: false`.

## ADR-036 — Cancel follows the reserve lock order and is exactly-once
*Status: accepted (Phase 3)*

- **Decision:** cancel locks (1) the reservation row `FOR UPDATE` filtered by owner, (2) decrements `user_show_counts`, (3) locks the seats in label order, then releases `WHERE reservation_id = $id AND status = 'confirmed'` and asserts the row count. An already-cancelled reservation returns 200 with no writes.
- **Proven:** 20 concurrent cancels of one reservation → all 200, quota decremented once; a cancel racing 50 buyers of the same seat → at most one new owner, never resurrected, never shared.

## ADR-037 — One recorded outcome drives metrics and the access log
*Status: accepted (Phase 4)*

- **Decision:** whoever produces a response records its outcome on the request (controllers: `confirmed`, `idempotent_replay`, `cancelled`; every error path: its envelope `error.code`, via `ErrorResponses`). One filter, placed just inside the request-id filter and *outside* Spring Security, reads it after the response is complete and emits `http_requests_total`, `http_request_duration_seconds`, the reservation counters and the access line.
- **Why:** metrics and logs are derived from the same fact, so they can't disagree; sitting outside Security means 401s are counted (`declined{reason="unauthorized"}`) even though they never reach Spring MVC. Declines are no longer logged separately, so there is exactly one line per request.
- **Proven:** a test fires one request per outcome and asserts every counter delta; a 200-user storm asserts `confirmed` delta == 201s and `declined{seat_taken}` delta == 409s exactly.

## ADR-038 — Route labels from templates, resolved independently of Spring MVC
*Status: accepted (Phase 4)*

- **Decision:** a fixed list of route templates (`/shows/{showId}/reserve`, …) matched with `PathPattern`; anything else is `unmatched`.
- **Why:** label cardinality must stay bounded (never raw ids), and Spring's matched-pattern attribute is absent for requests rejected before MVC (401/403). Probe/scrape routes (`/healthz`, `/readyz`, `/metrics`, `/actuator/**`) log at DEBUG so a 10 s health check doesn't drown the access log; they are still counted in metrics.

## ADR-039 — Seat gauges as a scrape-time Prometheus collector; pool metrics renamed, not duplicated
*Status: accepted (Phase 4)*

- **Decision:** `seatres_seats{show_id,status}` and `seatres_seats_available{show_id}` come from a `MultiCollector` registered on the Prometheus registry, which runs one grouped query on the observability pool *when scraped*, cached ≤ 1 s, limited to the 50 newest shows. If the query fails it emits no seat samples rather than stale ones. HikariCP's own meters are renamed by a `MeterFilter` to `db_pool_acquired|idle|connections|max|pending`, `db_pool_wait_seconds_{count,sum,max}` (wait count and duration) and `db_pool_timeouts_total`, for both pools (`pool="main|obs"`).
- **Why:** gauges computed at scrape time from the database always agree with the API, unlike in-process gauges updated on writes. Renaming keeps one source of truth instead of a second set of gauges. The total-connections gauge is `db_pool_connections` because Prometheus reserves the `_total` suffix for counters (the exporter silently stripped it).

## ADR-040 — One non-locking pre-read, including the hot-path decline
*Status: accepted (Phase 5, after load testing)*

- **Context:** the first local burst showed pool waits of up to 11.5 s (avg 0.75 s). Every reserve used three pool connections, and losers of a hot seat queued on its row lock *while holding a connection*, so 20 connections spent most of their time waiting for requests that were always going to decline.
- **Decision:** before the transaction, one read returns the show, unknown seats, seats already taken, and whether this (user, key) already has a reservation. If seats are taken and the key is new, decline `seat_taken` immediately (no transaction, no lock). If the key exists, always go to the transaction, so a retry of a successful request still returns its 200 replay.
- **Why it's safe:** the transaction still makes the real decision under row locks; the pre-read can only produce a decline. A seat that is freed just after the read gives a stale `seat_taken`, a legitimate race outcome, never a double-sell. Shows are immutable, so the rest of the read can't go stale (ADR-026).
- **Trade-off:** a user already at the limit who asks for a taken seat now gets `seat_taken` rather than `per_user_limit` (both are correct declines; precedence among simultaneous declines is not part of the contract).
- **Measured:** 20k burst (local, in-network) p99 1.76 s, max 3.9 s, 0 errors. See docs/LOADTEST.md.

## ADR-041 — Clients retry refused connects only
*Status: accepted (Phase 5)*

- **Decision:** the burst tool and the integration-test HTTP client retry a request (≤ 5 attempts, short backoff) only on `ConnectException`, i.e. when the TCP connection was refused and nothing was sent. The burst report prints how many such retries happened.
- **Why:** on Windows the listen backlog is capped (~200) below a 500-connection simultaneous start, so some connects are refused before reaching Tomcat. That's a property of the client machine, not the service. Retrying after a request was sent could duplicate it, so that is never done; those failures count as transport errors and fail the run.

## ADR-042 — Heap at 70% of the container
*Status: accepted (Phase 6; supersedes the 75% in ADR-024)*

- **Decision:** `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` (overridable via `JAVA_OPTS`).
- **Why:** on Railway's 1 GB trial container, 75% heap (768 MB) leaves ~250 MB for metaspace, code cache, direct buffers and thread stacks, too close to an OOM kill. Measured idle RSS is ~440 MB; the workload is many small short-lived objects, not a large heap. `ExitOnOutOfMemoryError` makes a real OOM a clean restart instead of a degraded process.

## ADR-043 — Paging rules ship with the code
*Status: accepted (Phase 6)*

- **Decision:** the paging list lives in `deploy/prometheus/alerts.yml` (checked with `promtool`), the reconcile alert uses a blackbox probe that requires `"ok":true`, and a Grafana dashboard sits in `deploy/grafana/`.
- **Why:** a paging list that exists only in prose drifts from the metric names; rules in the repo are reviewed and versioned with the code that emits the metrics. No invented metrics: reconcile is probed through its public endpoint rather than duplicated as a gauge.

## ADR-034 — Declines skip stack traces
*Status: accepted (Phase 2)*

- **Decision:** `DomainException` is constructed with `writableStackTrace=false`; declines are logged at INFO without a stack.
- **Why:** in a 20k stampede most responses are declines (`seat_taken`); filling 19k stack traces is pure CPU waste on the hot path. Unexpected exceptions still carry full stacks and log at ERROR.
