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

## Railway (live URL)

_Pending: the Railway project and URL are being set up by the repository owner._
