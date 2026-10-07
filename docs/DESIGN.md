# Phase 0 — Design

Status: **approved 2026-10-07**.
Every non-trivial choice below is recorded with alternatives in [DECISIONS.md](DECISIONS.md); `ADR-nnn` references point there.

---

## 1. Requirements in my own words

We are building a JSON-only HTTP service that sells seats for shows. An admin creates a show with a fixed set of seat labels and a price; users reserve one or more seats; owners can cancel. Graders will clone the repo, build it from clean, and hit a public URL with ~20,000 concurrent requests, many of them deliberately colliding on the same "hot" seats, reusing idempotency keys, exceeding per-user limits, and spoofing identity.

What "done" means, in priority order:

1. **Correct under concurrency.** A seat is sold at most once (I1); counts always add up (I3); idempotency gives exactly-once (I4); per-user limit can never be exceeded (I5); identity comes only from the verified JWT and only the owner can cancel (I6).
2. **Zero 5xx** (I2). Contention, lock waits, retries and pool saturation are *normal* in a stampede and must surface as 4xx domain outcomes or be absorbed by queueing — never as 500/502/503/504 while the database is healthy.
3. **Builds and deploys from a fresh clone** — `docker compose up --build` with no manual steps, `./mvnw verify` green, CI green, live on Railway.
4. **Observable** — Prometheus metrics whose DB-derived gauges reconcile with API state, one JSON log line per request with a request id, a reconcile endpoint, and a paging list.
5. **Clean code** I can extend live (most likely: add TTL holds).

Postgres is the only moving part besides the app: row locks, unique indexes, CHECK constraints and conditional writes are the entire concurrency-control story. No Redis, queues or distributed locks.

---

## 2. Stack (verified 2026-10-07)

| Concern | Choice | Note |
|---|---|---|
| Language | **Java 21 LTS** | Local JDK is 21.0.4; chosen by user. pgjdbc will be ≥ 42.7 so driver locks don't pin virtual-thread carriers. (ADR-001) |
| Framework | **Spring Boot 4.1.1** (latest GA on start.spring.io) | Spring Framework 7, **Jackson 3** (`tools.jackson.*` packages), modular starters. Supports Java 21. |
| Build | Maven multi-module + committed `./mvnw` | modules: `seat-service`, `burst` |
| Web | Spring MVC, embedded Tomcat, `spring.threads.virtual.enabled=true` | |
| DB | PostgreSQL 16, Spring `JdbcClient`, explicit SQL, HikariCP, Flyway | No JPA (ADR-004) |
| Security | OAuth2 resource server, JWT HS256 (`NimbusJwtDecoder.withSecretKey` / `NimbusJwtEncoder`) | |
| Observability | Actuator + Micrometer Prometheus, Boot structured logging (logstash JSON) | |
| Tests | JUnit 5, AssertJ, Testcontainers PostgreSQL + `@ServiceConnection`, ArchUnit | |
| Quality | Spotless (palantir-java-format), Checkstyle, `-Xlint:all -Werror` | |
| Platform | **Railway** (app service + Postgres service, private network) | (ADR-002) |
| Base package | `com.paytmmoney.seatreservation` | (ADR-023) |

---

## 3. Ambiguities and the resolution chosen

| # | Ambiguity | Resolution |
|---|---|---|
| A1 | `docs/ASSIGNMENT.md` is our working brief; it says "the full assignment is in docs/ASSIGNMENT.md" (itself). No separate original brief is in the repo. | Treat this file as authoritative. If the original Paytm brief surfaces, diff it against this design. |
| A2 | Leftover Go wording (pgxpool, slog, goroutines, `-race`, gofmt/golangci-lint, `httpapi → store`, `context.Context`, "Docker or Go installed"). | The brief's own Java section overrides: JdbcClient/Hikari, Logback JSON, virtual threads + `@RepeatedTest`, Spotless/Checkstyle, `api → service → persistence`. "Docker or Go" → "Docker or a JDK 21". |
| A3 | Java 25 vs 21. | Java 21 LTS (user decision). Docker images pinned to Temurin 21. |
| A4 | Railway specifics change some brief assumptions. | Railway health checks run **only at deploy time** (not continuously), so a slow `/readyz` won't trigger restarts there — but we still isolate it on its own pool because the Docker HEALTHCHECK and compose do poll it. Railway's edge proxy caps **10,000 concurrent connections** and **5 min** per request; Railway Postgres defaults to `max_connections = 100`. Burst default concurrency is therefore ≤ 2,000 in-flight. Trial plan: 1 GB RAM / 2 vCPU per service. |
| A5 | How are admin tokens obtained? "admin tokens require `ADMIN_API_KEY`". | `POST /auth/token {"user_id":"ops","role":"admin"}` must carry header `X-Admin-Key: <ADMIN_API_KEY>` (constant-time compare). Missing/wrong key → **401** `invalid_admin_key` (one status beyond the table's "400", documented in ADR-022). User tokens need no key. |
| A6 | `ALLOW_DEV_TOKENS=false` behaviour. | `/auth/token` returns **404** (route behaves as if absent). Production-style deployments can mint tokens elsewhere; the live grader URL runs with it `true`. |
| A7 | Format of `user_id` in tokens. | `^[A-Za-z0-9_.@-]{1,64}$`, else 400. Token TTL 1 h (configurable). |
| A8 | Format of the idempotency key. | 1–128 visible ASCII chars (`^[\x21-\x7E]{1,128}$`). Missing → **400** `idempotency_key_required`; malformed or header≠body → **422**. |
| A9 | Order of checks on `POST /shows/{id}/reserve` (which error wins when several apply). | Fixed order, ADR-018: **401** auth → **400** unreadable JSON → **400** key missing → **422** key header≠body / malformed → **422** shape (empty, duplicates, label pattern) → **404** show → **409** `per_user_limit` if `n > limit` → **422** `unknown_seat` → transaction (**201 / 200 replay / 409**). A replay of a valid original passes every pre-check identically, so ordering never turns a replay into an error. |
| A10 | Can show / seat data be read outside the transaction? | Yes. A show's price, limit and seat-label set are **immutable after creation** (no update/delete endpoints, enforced by having no such SQL). Pre-transaction reads of them are therefore race-free. Seat *status* is only ever decided under row locks. (ADR-026) |
| A11 | Same key used on a different show. | Fingerprint includes `show_id`, so it's a different body → **409** `idempotency_key_reused`. |
| A12 | Replay after the original reservation was cancelled. | **200** + `Idempotent-Replayed: true` with the reservation's *current* state (`status: cancelled`). The key stays bound to that reservation; re-booking needs a new key. |
| A13 | Retry budget exhausted on 40P01/40001 (should be impossible with our lock order). | **409** `contention` with `Retry-After: 1`, metric `declined{reason="contention"}` (extends the reason enum), logged at ERROR and paged on. Not a 5xx because the DB is healthy. (ADR-013) |
| A14 | Pool-acquire timeout / `statement_timeout` under extreme overload. | Designed not to happen (sizing + queueing), but if it does: **429** `server_busy` + `Retry-After`. Only a genuine connectivity failure (SQLSTATE class `08`, connection refused) returns **503** — the "DB genuinely broken" case where failing closed is the correct, consistent choice. (ADR-014) |
| A15 | Does `GET /shows/{id}` reveal who holds each seat? | No. Per-seat output is `{label, status}` only. User ids are not public. |
| A16 | Gauge label `show_id` cardinality — every burst run creates a show. | Gauges are emitted for the **50 most recently created shows** (configurable). Reconcile endpoint covers any show. (ADR-025) |
| A17 | What does the per-user limit count? | Seats the user *currently* holds as confirmed in that show. Cancel decrements, so cancelled seats return quota. |
| A18 | `POST /shows` limits. | `name` 1–200 chars; `price_paise` integer > 0; `seats` 1–10,000, unique, `^[A-Z]{1,3}[0-9]{1,4}$` (row letters + seat number, e.g. `A1`, `AA12`, `ZZZ9999`); `per_user_limit` optional, default 4, 1 ≤ limit ≤ 100. |
| A19 | Seat ordering — Java `String.compareTo` vs Postgres collation. | `seat_label text COLLATE "C"` so byte order is identical in SQL and Java (all labels are ASCII). The lock order must be one global order; collation-dependent sorting would be a hidden source of inconsistency. (ADR-017) |
| A20 | `GET /reservations/{id}` / cancel by non-owner. | **404** (indistinguishable from unknown id). |
| A21 | `/metrics` `http_requests_total` naming vs Micrometer defaults (`http_server_requests_seconds`, label `uri`). | Register our own meters with the exact names/labels from the brief; keep Micrometer's defaults too. |

---

## 4. Schema (Flyway `V1__schema.sql`)

```sql
CREATE TABLE shows (
  id              uuid PRIMARY KEY,
  name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  price_paise     bigint      NOT NULL CHECK (price_paise > 0),
  per_user_limit  int         NOT NULL CHECK (per_user_limit >= 1),
  total_seats     int         NOT NULL CHECK (total_seats >= 1),
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  show_id          uuid        NOT NULL REFERENCES shows(id),
  seat_label       text COLLATE "C" NOT NULL,
  status           text        NOT NULL DEFAULT 'available'
                     CHECK (status IN ('available','held','confirmed')),
  reservation_id   uuid        NULL,
  user_id          text        NULL,
  hold_expires_at  timestamptz NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (show_id, seat_label),
  -- Defence in depth: the DB itself refuses an inconsistent seat.
  CHECK ((status = 'available') = (reservation_id IS NULL)),
  CHECK ((status = 'available') = (user_id IS NULL)),
  CHECK ((status = 'held') = (hold_expires_at IS NOT NULL))
);
CREATE INDEX seats_show_status_idx ON seats (show_id, status);
CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservations (
  id               uuid PRIMARY KEY,
  show_id          uuid        NOT NULL REFERENCES shows(id),
  user_id          text        NOT NULL,
  idempotency_key  text        NOT NULL,
  request_hash     bytea       NOT NULL,          -- sha256, 32 bytes
  seats            text[]      NOT NULL,          -- sorted, unique
  amount_paise     bigint      NOT NULL CHECK (amount_paise > 0),
  status           text        NOT NULL CHECK (status IN ('confirmed','cancelled')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, idempotency_key)               -- the exactly-once guarantee
);
CREATE INDEX reservations_show_idx ON reservations (show_id);

CREATE TABLE user_show_counts (
  show_id     uuid NOT NULL REFERENCES shows(id),
  user_id     text NOT NULL,
  seat_count  int  NOT NULL CHECK (seat_count >= 0),
  PRIMARY KEY (show_id, user_id)
);
```

Seats are bulk-inserted on show creation with `INSERT ... SELECT $show, unnest($labels::text[])` (one statement, one round-trip, inside the same transaction as the show row).

---

## 5. Global lock order

Every transaction that mutates reservation state acquires locks in this order and never in any other:

1. **reservation row** (reserve: the idempotency-key unique-index entry of the row it inserts; cancel: `SELECT ... FOR UPDATE` on the existing row)
2. **`user_show_counts` row** for `(show, user)`
3. **seat rows**, `ORDER BY seat_label` (`COLLATE "C"`)

Because every transaction climbs the same ladder in the same direction, no cycle of waits can form, so deadlocks cannot occur between our own transactions. The SQLSTATE retry loop is a safety net, not part of the correctness argument.

---

## 6. Transaction pseudocode

All mutations run via `TransactionTemplate` with `ISOLATION_READ_COMMITTED`, invoked from a `TxRetry` wrapper that sits **outside** the transaction (ADR-013, ADR-020).

### 6.1 Reserve (`POST /shows/{id}/reserve`)

```
pre-transaction (no locks; show & seat set are immutable — ADR-026):
  user   := CurrentUser from SecurityContext            -- never from the body
  key    := resolve(header Idempotency-Key, body.idempotency_key)
  seats  := sort(unique(body.seats))                    -- duplicates were already 422
  show   := SELECT price_paise, per_user_limit FROM shows WHERE id=$show   -- 404 if none
  if |seats| > show.per_user_limit -> 409 per_user_limit
  if count(seats existing in show) < |seats| -> 422 unknown_seat {seats:[missing]}
  hash   := sha256(show_id || '\n' || join(seats, ','))
  amount := Math.multiplyExact(show.price_paise, |seats|)

TxRetry(max 3 attempts, jittered backoff, retry only on SQLSTATE 40P01 / 40001):
  BEGIN (READ COMMITTED)

  -- (1) claim the key: concurrent same-key requests block on the unique index
  INSERT INTO reservations (...) VALUES (...,'confirmed')
    ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id
  if no row:
      existing := SELECT * FROM reservations WHERE user_id=$u AND idempotency_key=$k
      ROLLBACK
      return existing.request_hash == hash ? 200 REPLAY(existing) : 409 idempotency_key_reused

  -- (2) per-user limit: atomic conditional upsert, no read-then-write
  INSERT INTO user_show_counts VALUES ($show,$u,$n)
    ON CONFLICT (show_id,user_id) DO UPDATE
      SET seat_count = user_show_counts.seat_count + EXCLUDED.seat_count
      WHERE user_show_counts.seat_count + EXCLUDED.seat_count <= $limit
    RETURNING seat_count
  if no row: ROLLBACK -> 409 per_user_limit

  -- (3) lock seats in global order; losers queue, then see 'confirmed' and decline
  rows := SELECT seat_label, status FROM seats
          WHERE show_id=$show AND seat_label = ANY($seats) ORDER BY seat_label FOR UPDATE
  taken := rows where status <> 'available'
  if taken non-empty: ROLLBACK -> 409 seat_taken {seats: taken}

  -- (4) conditional write, row count asserted (defence in depth)
  n := UPDATE seats SET status='confirmed', reservation_id=$id, user_id=$u, updated_at=now()
       WHERE show_id=$show AND seat_label = ANY($seats) AND status='available'
  if n != |seats|: ROLLBACK -> IllegalStateException (must never happen; would be a 500 + page)

  COMMIT -> 201 reservation
```

Why the second same-key request is safe: it blocks on the unique-index entry until the first transaction ends. If the first **commits**, the second's insert conflicts → replay (200) or reused (409). If the first **rolls back** (e.g. `seat_taken`), the index entry disappears and the second proceeds as a fresh request — correct, because declines are not memoised (ADR-010).

Why waiting on seat locks (no `NOWAIT` / `SKIP LOCKED`): if the current holder of a hot seat rolls back because *another* seat in its multi-seat request was taken, a `NOWAIT` loser would already have given up and the hot seat could end with **zero** winners. Waiting guarantees exactly one winner per available seat. (ADR-007)

### 6.2 Cancel (`POST /reservations/{id}/cancel`)

```
TxRetry:
  BEGIN (READ COMMITTED)
  r := SELECT * FROM reservations WHERE id=$id AND user_id=$token_user FOR UPDATE      -- lock 1
  if none: ROLLBACK -> 404 not_found          (non-owner and unknown are indistinguishable)
  if r.status = 'cancelled': ROLLBACK -> 200 r  (idempotent)
  UPDATE user_show_counts SET seat_count = seat_count - |r.seats|
    WHERE show_id=r.show_id AND user_id=$u                                             -- lock 2
    (row count must be 1; CHECK seat_count >= 0 catches drift)
  SELECT seat_label FROM seats WHERE show_id=r.show_id AND seat_label = ANY(r.seats)
    ORDER BY seat_label FOR UPDATE                                                     -- lock 3
  n := UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL, updated_at=now()
       WHERE show_id=r.show_id AND reservation_id=$id AND status='confirmed'
       -- guarding on reservation_id makes it impossible to release someone else's seat
  assert n == |r.seats|
  UPDATE reservations SET status='cancelled', updated_at=now() WHERE id=$id
  COMMIT -> 200 r (status=cancelled)
```

### 6.3 Create show (`POST /shows`, admin)

```
BEGIN; INSERT INTO shows ...; INSERT INTO seats SELECT $id, unnest($labels); COMMIT -> 201
```

### 6.4 Future: TTL holds (expected live-interview extension)

Already in the schema: `status='held'`, `hold_expires_at`, CHECK tying them together.

1. `POST /shows/{id}/hold` = reserve with step (4) writing `status='held', hold_expires_at=now()+ttl`; reservation status gains `'held'`.
2. `POST /reservations/{id}/confirm` = lock order 1→3, then `UPDATE seats SET status='confirmed', hold_expires_at=NULL WHERE reservation_id=$id AND status='held' AND hold_expires_at > now()`; row count ≠ n → 409 `hold_expired`.
3. Expiry = conditional UPDATE, never a read-then-write: a sweeper (or lazily at the start of reserve for the requested seats) runs `UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL, hold_expires_at=NULL WHERE status='held' AND hold_expires_at < now() RETURNING reservation_id`, then decrements `user_show_counts` and marks the reservations `expired` — following the same lock order (reservation → counts → seats) by first selecting expired reservation ids `FOR UPDATE SKIP LOCKED` (skip is safe for a *sweeper*: a skipped row is retried on the next sweep, unlike a buyer).
4. Reserve's step (3) treats a `held` seat whose `hold_expires_at < now()` as available-after-release, so expiry never depends on the sweeper's timing.

---

## 7. Status-code table

| Endpoint | Success | Errors (`error.code`) |
|---|---|---|
| `POST /auth/token` | 200 `{token, expires_at}` | 400 `invalid_request`/`validation`, 401 `invalid_admin_key`, 404 when `ALLOW_DEV_TOKENS=false` |
| `POST /shows` | 201 show (all seats available) | 400 `invalid_json`, 401 `unauthorized`, 403 `forbidden`, 422 `validation` |
| `GET /shows/{id}` | 200 seats + `{total, available, held, confirmed}` | 404 `not_found` (also for a malformed uuid) |
| `POST /shows/{id}/reserve` | **201** new / **200** replay (+`Idempotent-Replayed: true`) | 400 `invalid_json`, 400 `idempotency_key_required`, 401, 404, 409 `seat_taken {seats}`, 409 `per_user_limit`, 409 `idempotency_key_reused`, 409 `contention` (A13), 422 `validation` / `idempotency_key_mismatch` / `unknown_seat`, 429 `server_busy` (A14) |
| `POST /reservations/{id}/cancel` | 200 (`cancelled`; idempotent) | 401, 404 |
| `GET /reservations/{id}` | 200 | 401, 404 |
| `GET /shows/{id}/reconcile` | 200 `{ok, checks[], numbers}` | 404 |
| `GET /healthz` | 200 | — |
| `GET /readyz` | 200 / **503** if DB ping fails, migrations pending, or shutting down | — |
| `GET /metrics` | 200 Prometheus text | — |
| any unknown route / wrong method | — | 404 `not_found` / 405 `method_not_allowed` |
| DB unreachable (any DB-backed route) | — | 503 `service_unavailable` (fail closed, A14) |

Error envelope (every error, including Spring Security 401/403 and framework 400/404/405/422):

```json
{"error":{"code":"seat_taken","message":"Some requested seats are no longer available","details":{"seats":["A12"]}},"request_id":"3f2c..."}
```

Exceptions → HTTP mapping lives in exactly one `@RestControllerAdvice` (plus the security entry point / access-denied handler, which delegate to the same envelope writer). Domain errors are a sealed hierarchy under `DomainException`.

---

## 8. Metrics (`/metrics`, also `/actuator/prometheus`)

| Name | Type | Labels | Source |
|---|---|---|---|
| `seatres_reservations_confirmed_total` | counter | — | after COMMIT of a reserve |
| `seatres_reservations_declined_total` | counter | `reason` ∈ `seat_taken, per_user_limit, idempotent_replay, idempotency_key_reused, validation, unauthorized, not_found, contention, server_busy` | reserve outcomes |
| `seatres_reservations_cancelled_total` | counter | — | after COMMIT of a state-changing cancel (not for idempotent re-cancel) |
| `seatres_seats` | gauge | `show_id, status` | **DB at scrape time**, observability pool, ≤1 s cache, 50 most recent shows |
| `seatres_seats_available` | gauge | `show_id` | same query |
| `http_requests_total` | counter | `route` (pattern, e.g. `/shows/{id}/reserve`), `method`, `code` | request filter |
| `http_request_duration_seconds` | histogram | `route` | request filter |
| `db_pool_acquired`, `db_pool_idle`, `db_pool_total`, `db_pool_pending` | gauge | `pool` (`main`/`obs`) | `HikariPoolMXBean` |
| `db_pool_wait_seconds` (count + sum + buckets) / `db_pool_timeouts_total` | histogram / counter | `pool` | custom Hikari `MetricsTrackerFactory` |
| `db_tx_retries_total` | counter | `sqlstate`, `operation` | `TxRetry` |
| JVM, Tomcat, Hikari defaults | — | — | Micrometer binders |

Reconciliation property the burst tool checks: `confirmed_total − cancelled_total` (deltas over the run) == `seatres_seats{status="confirmed"}` for the burst's show == `GET /shows/{id}` confirmed count.

---

## 9. Throughput & resilience settings (initial; tuned and re-recorded in Phase 5)

| Setting | Value | Why |
|---|---|---|
| `DB_MAX_CONNS` (main Hikari) | 20 | Railway Postgres `max_connections=100`; Postgres on 2 vCPU saturates well below that, and hot rows serialise anyway. Leaves room for obs pool, Flyway, psql, redeploy overlap (old + new instance briefly both connected). |
| Hikari `connection-timeout` | 20 s | Excess requests *queue* for a connection instead of failing; well below Railway's 5-min proxy limit. |
| Obs pool | 2 conns, 1 s connect/validation timeout | readiness + gauges never wait behind the burst (ADR-015) |
| `statement_timeout` (main pool) | 15 s | bounds a runaway statement; with ≤20 in-flight txs a seat-lock queue is ≤20 short txs deep, so this never fires on contention |
| `lock_timeout` | unset | an aggressive one turns contention into errors |
| Tomcat `max-connections` / `accept-count` | 20000 / 10000 | the burst queues instead of being refused; virtual threads make each waiting request cheap |
| `server.shutdown` | graceful, 30 s phase timeout | readiness → `REFUSING_TRAFFIC` first, drain, then pool closes |
| JVM | `-XX:MaxRAMPercentage=75`, container-aware | Railway trial: 1 GB per service |
| Retry | 3 attempts, jitter 10–50 ms ×2^n, SQLSTATE `40P01`/`40001` only | |

---

## 10. Module / package layout

```
pom.xml                     parent (Java 21, Boot 4.1.1 BOM, Spotless, Checkstyle, -Xlint:all -Werror)
seat-service/src/main/java/com/paytmmoney/seatreservation/
  SeatReservationApplication.java
  config/         @ConfigurationProperties (validated), Jackson, Security, DataSources (main + obs), DATABASE_URL → JDBC
  api/            controllers, request/response records, ApiExceptionHandler, RequestIdFilter, AccessLogFilter
  domain/         Show, Reservation, SeatLabel, RequestFingerprint, Money, sealed DomainException tree (no Spring, no I/O)
  service/        CreateShow, ReserveSeats, CancelReservation, GetShow, GetReservation, Reconcile (own TransactionTemplate + TxRetry)
  persistence/    ShowRepository, SeatRepository, ReservationRepository, UserShowCountRepository, TxRetry (SQLSTATE)
  security/       JwtConfig, TokenIssuer, CurrentUser (identity only from SecurityContext), envelope entry point / denied handler
  observability/  MetricNames, ReservationOutcomeRecorder, SeatGaugeCollector (obs pool), HttpMetricsFilter, DbPoolMetrics
seat-service/src/main/resources/application.yml, db/migration/V1__schema.sql
seat-service/src/test/java/...   unit / integration / concurrency (I1–I6) / ArchUnit
burst/                           load + correctness tool, fat jar
burst.sh, Makefile, Dockerfile, docker-compose.yml, .env.example, railway.json, .github/workflows/ci.yml, docs/
```

ArchUnit enforces: `api → service → persistence`; `domain` depends on nothing but the JDK; only `persistence` touches `JdbcClient`; only `security.CurrentUser` reads the `SecurityContext`.

---

## 11. Test plan (named after the invariant each proves)

| Test | Proves |
|---|---|
| `I1_HotSeatStorm_ExactlyOneWinner` — 500 users × 1 seat, `@RepeatedTest(5)` | 1×201, 499×409 `seat_taken`, 0 other |
| `I1_OppositeOrderMultiSeat_NoDeadlockAllOrNothing` — `[A1,A2]` vs `[A2,A1]` storms | no 5xx, every reservation all-or-nothing |
| `I2_*` — every concurrency test asserts zero 5xx and zero transport errors | |
| `I3_ReconcileHoldsAfterEveryTest` — runs all reconcile checks after each test | |
| `I4_SameKeyFifty_OneReservationRestReplays` | 1×201, 49×200 replay, 1 row in DB |
| `I4_SameKeyDifferentSeats_Consistent` | 1×201, rest 409 `idempotency_key_reused` or 200 replay |
| `I4_Replay_ReturnsOriginal` / `I4_DeclineNotMemoised` | |
| `I5_TenParallelReservesLimitFour_AtMostFour` | ≤4 seats; `user_show_counts` matches |
| `I6_SpoofedBodyUserIdIgnored` / `I6_NonOwnerCancel_404SeatStaysConfirmed` | |
| `I6_CancelRacingReserve_NoResurrection` | seats never double-assigned |
| Unit: validation, fingerprint order-insensitivity, money overflow, error mapping, JWT (bad sig, expired, wrong role), Jackson strictness (`250.5` → 400) | |
