# Load test results

Every run uses the burst tool (`./burst.sh <BASE_URL>`, see README) with defaults unless stated: 20,000 reserve
requests, 500 in flight, 1,000 seats, 5 hot seats × 500 users, 2,000 users, plus the scripted scenarios (same key ×50,
same key / different seats ×20, one user ×10 at limit 4, spoofed body identity, cancel + re-book). A run passes only if
every check passes (zero 5xx, zero transport errors, exactly one winner per hot seat, invariant held at every sample,
reconcile ok, metrics reconcile with observed responses, limit held, replays never create a second reservation).

## Local (docker compose), 2026-10-12

Machine: Windows 11, Docker Desktop (WSL2 VM, 8 CPUs, 12 GB), app and Postgres 16 in compose, `DB_MAX_CONNS=20`.

| Run | Client location | Elapsed | p50 | p95 | p99 | max | 5xx | Result |
|---|---|---|---|---|---|---|---|---|
| Before pre-read (2k requests) | Windows host | 11.1 s | 2,016 ms | 6,212 ms | 8,543 ms | 10,251 ms | 0 | PASS* |
| After pre-read, 20k | Windows host | 46.7 s | 727 ms | 3,724 ms | 6,767 ms | 16,105 ms | 0 | PASS |
| After pre-read, 20k | inside the compose network | **24.2 s** | **497 ms** | **1,248 ms** | **1,756 ms** | **3,931 ms** | **0** | **PASS** |

\* An earlier identical run had 80 client-side `ConnectException`s (see "Findings").

Outcome distribution of the passing in-network run: 626 × 201, 49 × 200 replay, 19,276 × 409 `seat_taken`,
30 × 409 `per_user_limit`, 19 × 409 `idempotency_key_reused`, 0 × 5xx, 0 transport errors; 48 invariant samples, 0
failed; final counts `{"total":1000,"available":35,"held":0,"confirmed":965}`; metric deltas equal to observed outcomes.

### Findings and fixes

1. **Bottleneck: pool connections held by requests that were going to decline anyway.** Each reserve took three
   connections (show, unknown-seat check, transaction), and hot-seat losers waited in the row-lock queue *holding* a
   connection. Pool metrics showed 11,789 acquisitions waiting 8,819 s in total (avg 0.75 s, max 11.5 s) against
   ~37 ms average hold. Fix: one non-locking pre-read that also declines already-taken seats (ADR-040). Declines now
   cost one short read and no transaction.
2. **More connections did not help.** Pool 20 vs 40 on the same burst: 30.2 s vs 29.8 s; average hold time doubled
   (50 → 95 ms). Postgres sat at ~5% CPU with its connections idle in `ClientRead` while the app was at ~190% CPU,
   so locally the constraint is the app/VM side, not the database. Pool stays at 20 (ADR-024).
3. **Docker Desktop's port forwarder dominates local latency.** The same 10k burst: 21–30 s from the Windows host,
   15 s from inside the compose network. Idle single-request latency through the forwarder is ~12 ms even for
   `/healthz`. Local numbers are therefore an upper bound; the deployed numbers are the ones that matter.
4. **Client-side `ConnectException` on Windows** under a 500-connection start gate: Windows caps the listen backlog
   (~200) below Tomcat's `accept-count`. The request never left the client, so the burst tool and the test client
   retry *connects only* (bounded, counted and printed as "client connect retries (never sent)"); anything after
   the connection is established is never retried. Linux CI never needed it.
5. **JIT warm-up** is visible: a second identical run on the same container was ~25% faster (27.2 s → 21.1 s, max
   13.2 s → 7.2 s). Relevant for cold starts on the platform (Phase 6 measures it).

## Restarts (local compose), 2026-10-12

**Cold start** (`docker compose restart app`, polling `/healthz`, `/readyz` and `GET /shows/{id}` every 100 ms from
t=0):

| t | /healthz | /readyz | GET /shows/{id} |
|---|---|---|---|
| 0.2 s | 200 | 200 | 200 (old process still draining) |
| 1.0 s | refused | refused | refused (process down, port closed) |
| ~16–18 s | 200 | 200 | 200 (migrations done, port open) |

Zero 5xx at any point; readiness never reported UP before the database and migrations were ready (ADR-028).

**SIGTERM mid-burst** (`docker compose stop -t 45 app` 14 s into a 20k burst, then start): 0 × 5xx; graceful shutdown
logged "Commencing graceful shutdown … complete". While the single instance was down, 13,322 connects were refused and
22 idle keep-alive connections were closed by the shutdown (transport errors, not HTTP errors). Correctness held
through the restart: exactly one winner per hot seat, confirmed seats == seats in 201s minus cancels (917 == 917),
final reconcile ok. In-process counters restarted from zero, so counter-vs-observed checks don't apply across a
restart (ADR-019); DB gauges and `/reconcile` stayed authoritative. On Railway a redeploy starts the new instance and
waits for `/readyz` before stopping the old one, so a deploy doesn't open that window.

## Railway (live URL), 2026-10-12

Deployment: https://app-production-70543.up.railway.app. One app service (Dockerfile, Railway trial: up to 2 vCPU /
1 GB) and one PostgreSQL service in the same project, connected over the private network
(`postgres.railway.internal`), `DB_MAX_CONNS=20`. Client: the burst tool on a Windows laptop in India, over the public
internet and TLS through Railway's edge. Default burst (20k requests, 500 in flight, 1,000 seats, 5 hot seats × 500).

| Run | Elapsed | p50 | p95 | p99 | max | 201 | 200 replay | `seat_taken` | `per_user_limit` | `key_reused` | 5xx | Transport errors | Invariant samples | Result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 13.96 s | 294 ms | 484 ms | 1,119 ms | 3,240 ms | 607 | 49 | 19,295 | 30 | 19 | **0** | **0** | 32, 0 failed | **PASS** |
| 2 | 13.46 s | 293 ms | 372 ms | 496 ms | 1,164 ms | 606 | 49 | 19,294 | 32 | 19 | **0** | **0** | 30, 0 failed | **PASS** |

Both runs: exactly 1 winner of 500 on each hot seat; final reconcile ok; confirmed seats == seats in 201s minus cancels
(965 and 966); counter deltas equal to observed outcomes for every reason; one user ×10 at limit 4 → exactly 4;
spoofed body identity ignored; cancel + re-book succeeded. Server side after both runs: 0 ERROR/WARN log lines,
`db_pool_timeouts_total{pool="main"} 0`, max pool wait 1.05 s, no `db_tx_retries_total` series (zero retries).

p50 ≈ 290 ms is dominated by the round trip from India to Railway's region; the server-side time is much smaller.

**Startup on Railway:** "Started SeatReservationApplication in 2.685 seconds"; Flyway V1 in 12 ms.

**Redeploy under polling:** `railway redeploy` while polling `/readyz` and `GET /shows/{id}` every 0.5 s for 4 minutes:
118/118 samples were 200 on both. The new deployment became healthy before the old one was removed, so deploys do not
open the single-instance restart window measured locally.
