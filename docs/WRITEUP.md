# Write-up: seat reservation under a stampede

A JSON API that sells seats for shows and stays correct under ~20,000 concurrent requests: no seat sold twice, no 5xx,
books that balance at every instant, exactly-once idempotency, a per-user limit that can't be beaten in parallel, and
identity taken only from a verified token. Java 21, Spring Boot 4.1 (MVC on virtual threads), PostgreSQL 16 via
`JdbcClient` and explicit SQL. Postgres is the only concurrency control: row locks, unique indexes, CHECK constraints
and conditional writes. Every decision referenced below is in [DECISIONS.md](DECISIONS.md) (ADR-nnn).

## 1. The atomic decision

**Mechanism:** one `READ COMMITTED` transaction per reservation that takes row locks in a single global order and
finishes with a conditional write whose row count is asserted.

```text
pre-read (no locks): show, unknown seats, already-taken seats, does (user,key) exist?      -- ADR-040
BEGIN READ COMMITTED
 1. INSERT INTO reservations ... ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING ...   -- claim the key
      no row -> SELECT existing: same fingerprint -> 200 replay, else 409 idempotency_key_reused
 2. INSERT INTO user_show_counts ... ON CONFLICT DO UPDATE SET seat_count = seat_count + n
      WHERE seat_count + n <= limit RETURNING seat_count                                     -- per-user limit
      no row -> 409 per_user_limit
 3. SELECT seat_label, status FROM seats WHERE show_id=$1 AND seat_label = ANY($2)
      ORDER BY seat_label FOR UPDATE                                                         -- lock seats in order
      any not 'available' -> 409 seat_taken {seats}
 4. UPDATE seats SET status='confirmed', reservation_id=$id, user_id=$u
      WHERE show_id=$1 AND seat_label = ANY($2) AND status='available'                        -- assert rows == n
COMMIT -> 201
```

**Why it is race-free.**
- Two buyers of the same seat both reach step 3. `FOR UPDATE` lets exactly one hold the row; the other **waits**. When
  the first commits, the waiter re-reads the row under READ COMMITTED, sees `confirmed`, and declines. There is no
  window between "check" and "write" because the check happens on a locked row.
- Step 4's `AND status='available'` plus the row-count assertion is defence in depth, and the schema itself refuses an
  inconsistent seat: `CHECK ((status='available') = (reservation_id IS NULL))`.
- Losers wait instead of failing fast (no `NOWAIT`/`SKIP LOCKED`, ADR-007). If the lock holder rolls back because a
  *different* seat in its multi-seat request was taken, the next waiter still finds the hot seat available and wins.
  Fail-fast would leave that seat with zero winners.
- Multi-seat requests are all-or-nothing: any decline throws, and the whole transaction, including the key claim and
  the quota increment, rolls back.

**Deadlock avoidance.** Every transaction (reserve and cancel) climbs the same ladder in the same direction:
(1) the reservation / idempotency row, (2) the `user_show_counts` row, (3) seat rows sorted by `seat_label`. Labels are
stored `COLLATE "C"`, so Postgres' order is byte order, the same as Java's sort, on every server locale (ADR-017). With
one global order no cycle of waits can form, so `[A1,A2]` racing `[A2,A1]` can't deadlock. A retry loop still wraps
the whole transaction from outside (3 attempts, jittered backoff, only on SQLSTATE `40P01`/`40001`, metric
`db_tx_retries_total`), as a safety net; in every test and burst it has fired zero times. If it were ever exhausted the
client gets 409 `contention` with `Retry-After`, not a 5xx, because the database is healthy and nothing was written.

**Why not SERIALIZABLE?** Every loser of a hot seat would abort with 40001 and retry, multiplying load exactly when
there is least headroom (ADR-005). Explicit locks make losers queue cheaply instead.

**The hot-path pre-read (ADR-040).** Load testing showed losers of hot seats holding pool connections while queued on
the row lock. A single non-locking read before the transaction now declines seats that are already taken, so most
declines cost one short read and no transaction. It can only ever produce a decline; a seat freed a moment later gives
a stale `seat_taken`, which is a legitimate race outcome. It never decides a sale, so it can't cause a double-sell. The
read is skipped for keys that already have a reservation, so retries of successful requests still replay.

## 2. Idempotency

- **Storage:** the reservation row itself carries `(user_id, idempotency_key, request_hash)` with
  `UNIQUE (user_id, idempotency_key)`. Keys are per user, from the `Idempotency-Key` header or `idempotency_key` body
  field (both present and different → 422; neither → 400).
- **Exactly once:** claiming the key is the transaction's *first* write (ADR-008). A concurrent request with the same
  key blocks on the unique-index entry until the first transaction ends: if it commits, the second conflicts and
  becomes a replay; if it rolls back (e.g. `seat_taken`), the second proceeds as a fresh request.
- **Replay:** same key + same fingerprint → **200** with the original reservation (its *current* state, e.g. after a
  cancel) and `Idempotent-Replayed: true`. It is counted as `declined{reason="idempotent_replay"}`: the graders assert
  exactly one 201 per hot seat, so a replay must not look like a second confirmation (ADR-009).
- **Same key, different body:** fingerprint = `sha256(show_id + "\n" + sorted unique seats)`, so `["A13","A12"]` and
  `["A12","A13"]` are the same request. A different fingerprint (other seats or another show) → 409
  `idempotency_key_reused`.
- **Declines are not memoised (ADR-010):** a declined attempt rolls back its key claim. Retrying it re-evaluates, which
  is safe because a decline moved nothing, and gives the correct current answer (e.g. after a cancel freed the seat).
- **Proven:** 50 concurrent identical requests → one reservation, 49 replays with the same `reservation_id`; 20
  concurrent same-key/different-seat requests → one 201, 19 `idempotency_key_reused`. Both repeated in every test run
  and in every burst.

## 3. Holds and expiry

**Chosen model (v1):** `POST /shows/{id}/reserve` goes straight to `confirmed`; release is an explicit owner-only
`POST /reservations/{id}/cancel` (idempotent; non-owner or unknown id → 404 so existence doesn't leak). There is no
payment step to wait for, so a hold would only add an expiry path that can break the graded invariants (ADR-011).
`held` exists in the schema, in every count, gauge and reconcile check (always 0 today), and the schema already has
`hold_expires_at` with `CHECK ((status='held') = (hold_expires_at IS NOT NULL))`.

**How TTL holds slot in:**
1. `POST /shows/{id}/hold`: the reserve transaction unchanged except step 4 writes `status='held',
   hold_expires_at = now() + ttl`; the reservation gets status `held`.
2. `POST /reservations/{id}/confirm`: lock order (1)→(3), then
   `UPDATE seats SET status='confirmed', hold_expires_at=NULL WHERE reservation_id=$id AND status='held' AND
   hold_expires_at > now()`; row count ≠ n → 409 `hold_expired`.
3. Expiry is a **conditional UPDATE, never read-then-write**: a sweeper selects expired held reservations
   `FOR UPDATE SKIP LOCKED` (skipping is fine for a sweeper; a skipped row is retried next sweep), then in the same lock
   order releases `WHERE reservation_id=$id AND status='held' AND hold_expires_at < now()`, decrements
   `user_show_counts`, and marks the reservation `expired`.
4. Reserve's step 3 treats `held AND hold_expires_at < now()` as releasable inside its own transaction, so
   correctness never depends on the sweeper's timing.

## 4. Consistency vs. availability under a partition

We choose **consistency**. Postgres is the single system of record; if the app can't reach it, it does not guess:
`/readyz` returns 503 (so the platform stops routing), DB-backed requests return 503 `service_unavailable`, and no
reservation is accepted from a cache or a local queue that might later conflict. Liveness stays UP, so the platform
doesn't restart a healthy process. Readiness probes on a separate 2-connection pool with 1 s timeouts, so it answers
quickly whether the DB is down or the main pool is merely saturated (ADR-015). Saturation is not a partition: excess
requests queue for a connection (up to 20 s) and, if that is ever exceeded, get a retryable 429 rather than a 5xx
(ADR-014). Tested: stopping Postgres turns `/readyz` to 503 in ~1.3 s while `/healthz` stays 200; it recovers ~4 s
after Postgres returns.

## 5. Observability

Counters for confirmed/declined-by-reason/cancelled, HTTP counts and latency by route template, seat gauges read from
the database at scrape time, `db_pool_*` for both pools, `db_tx_retries_total`; one JSON access line per request with
`request_id`, `user_id`, `show_id`, `route`, `status`, `latency_ms`, `outcome`; and a public reconcile endpoint. The
burst tool checks that counter deltas equal what clients saw. Details, PromQL and the 2am paging list (any 5xx, a
failing reconcile check, `db_tx_retries` spiking, readiness flapping, p99 over SLO, pool wait saturation) are in
[OBSERVABILITY.md](OBSERVABILITY.md), with runnable rules in `deploy/prometheus/alerts.yml`.

## 6. Measured results

Full numbers and analysis: [LOADTEST.md](LOADTEST.md).

| Environment | Requests | Elapsed | p50 | p99 | max | 5xx | Checks |
|---|---|---|---|---|---|---|---|
| Local compose (client in the compose network) | 20,000 @ 500 in flight | 24.2 s | 497 ms | 1,756 ms | 3,931 ms | 0 | all PASS, 48 invariant samples |
| Railway (live, client in India over the internet) | 20,000 @ 500 in flight | 13.5 s | 293 ms | 496 ms | 1,164 ms | 0 | all PASS (2 runs; run 1 p99 1,119 ms) |

On Railway the app starts in 2.7 s, and a redeploy polled every 0.5 s showed 118/118 successful responses (the new
instance is healthy before the old one stops). Also measured locally: cold start to ready ~16–18 s on Docker Desktop
with zero 5xx during the restart; SIGTERM mid-burst drains
gracefully with zero 5xx (requests arriving while a single instance is down are refused at TCP, and correctness held:
one winner per hot seat, books balanced).

Test suite: 138 tests (`./mvnw verify`), including invariant tests I1–I6 under real concurrency against real Postgres,
each repeated 5× with a start gate.

## 7. AI usage

*Drafted with the assistant at my request from the factual record in [AI_LOG.md](AI_LOG.md).*

I built this with an AI coding assistant (Claude Code) working in my repository, in a phased way I set up
beforehand: I wrote a detailed brief ([ASSIGNMENT.md](ASSIGNMENT.md)) with the stack, the correctness invariants, the
exact transaction design, and a working agreement. The agreement required stopping after every phase for my go-ahead,
small Conventional Commits, a decision log, and running the build and tests before calling anything done.

**What the assistant did.** It wrote the design document and decision log, all application and test code, the burst
tool, the Docker/compose/CI/Railway configuration and the documentation. It also ran every verification itself
(`./mvnw verify`, compose smoke tests, the burst locally and against the live URL, a fresh-clone build) and reported
the output to me at each stop.

**What I decided.**
- Java 21 instead of the brief's preferred Java 25 (that's the JDK on my machine).
- Railway as the platform, after asking the assistant to compare Fly.io and Railway pricing (it recommended Railway).
- I left the base package name to the assistant ("as per standard"); it chose `com.paytmmoney.seatreservation`.
- I approved the Phase 0 design, including five resolutions that went beyond the brief: 409 `contention` when retries
  are exhausted, 429 vs 503 for pool saturation vs DB loss, 401 for a missing admin key, `COLLATE "C"` on seat labels,
  and gauges bounded to the 50 newest shows.
- I approved each phase before the next one started.
- I handled what needed my accounts: GitHub push access (my saved credential was a different account, which I fixed)
  and the Railway login. The assistant then created the project and deployed with the CLI from my session.
- I changed my own brief twice: I asked the assistant to do the remaining Phase 6 work in one go ("do all"), and I
  asked it to draft this section, which the brief originally reserved for me.

**What went wrong, and how it was caught.** The record shows the assistant's own mistakes, found by running things
rather than by inspection:
- A concurrency test reused user ids across repetitions; the service was right, the test was wrong.
- The access log silently dropped every cancel line (duplicate JSON key).
- A Prometheus gauge was silently renamed by the exporter.
- A first draft of the alert rules referenced a metric that doesn't exist.
- The burst report counted unreachable samples as invariant failures.

Load testing also drove the one real design change after Phase 0: a non-locking pre-read that declines already-taken
seats (ADR-040). It cut worst-case latency from ~16 s to ~4 s locally.

**Where to look.** Every decision, with the alternatives rejected and the failure mode it prevents, is in
[DECISIONS.md](DECISIONS.md). The request-by-request record of what I asked, what the assistant proposed and what I
changed is in [AI_LOG.md](AI_LOG.md).

## 8. What's next

- **TTL holds + a payment step** (section 3): `available → held → confirmed`, conditional-UPDATE expiry.
- **Per-show sharding:** all locks are per show, so shows can be partitioned across databases by `show_id` with no
  cross-shard transactions.
- **Read replica for GETs:** `GET /shows/{id}` and gauges from a replica; the reserve path and `/reconcile` stay on the
  primary.
- **Queue / waiting room for mega on-sales:** admit users at the rate the primary sustains (token bucket at the edge)
  instead of letting 100k requests queue on 20 connections.
- **Multi-instance metrics:** aggregate counters with `sum()`; keep DB-derived gauges and `/reconcile` as the source
  of truth; size `DB_MAX_CONNS` per instance so `N × pool + obs` fits `max_connections` (or put PgBouncer in front).
