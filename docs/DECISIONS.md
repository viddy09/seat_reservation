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
