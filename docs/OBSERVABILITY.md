# Observability

How to watch the service during a burst, what every signal means, and what pages someone at 2am.

Design notes: metrics and logs are derived from one recorded outcome per request (ADR-037); seat gauges are read from
the database at scrape time on a separate connection pool, so they always agree with the API and stay readable while
the main pool is saturated (ADR-015, ADR-039).

## Endpoints

| Path | What | Notes |
|---|---|---|
| `GET /healthz` | Liveness: the process is up | No dependency checks; never restarts the app because the DB is down |
| `GET /readyz` | Readiness: accepting traffic **and** DB reachable | 503 when the DB is unreachable, while starting (port closed until migrations finish) or shutting down; DB ping uses the 2-connection observability pool with 1 s timeouts |
| `GET /metrics` | Prometheus text format | Same registry as `/actuator/prometheus` |
| `GET /shows/{id}/reconcile` | Per-check books balance for one show | Public, read-only, one consistent snapshot |

## Watching a burst live

```bash
BASE=http://localhost:8080            # or the live URL
SHOW=<show id printed by the burst tool>

# Outcomes, seats and pool, refreshed every second
watch -n1 "curl -s $BASE/metrics | grep -E '^(seatres_|db_pool_(acquired|pending|timeouts)|db_tx_retries)' | grep -v '_created'"

# Seat counts for one show (from the DB, same numbers as the API)
watch -n1 "curl -s $BASE/metrics | grep 'show_id=\"$SHOW\"'"

# The books, as the API sees them
watch -n1 "curl -s $BASE/shows/$SHOW/reconcile | jq '{ok, checks: [.checks[] | {name, ok}]}'"

# Logs: one JSON line per request (compose)
docker compose logs -f app | jq -c 'select(.logger_name=="access") | {status, route, outcome, decline_reason, latency_ms, request_id}'
```

On Railway: open the project → the app service → **Logs** (live tail, filterable; e.g. search `"status":5` or a
`request_id`), and **Metrics** for CPU/memory/network. `/metrics` on the public URL can be scraped by any Prometheus
(see `deploy/prometheus/prometheus.yml`); `deploy/grafana/seat-reservation.json` is a ready dashboard.

## Metrics

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `seatres_reservations_confirmed_total` | counter | | 201s from reserve |
| `seatres_reservations_declined_total` | counter | `reason`: `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `validation`, `unauthorized`, `not_found`, `contention`, `server_busy` | Reserve requests that did not create a reservation. All reasons start at 0. |
| `seatres_reservations_cancelled_total` | counter | | State-changing cancels (re-cancels not counted) |
| `seatres_seats` | gauge | `show_id`, `status` (`available`/`held`/`confirmed`) | **From the DB at scrape time**, 50 newest shows, cached ≤ 1 s |
| `seatres_seats_available` | gauge | `show_id` | Same source |
| `http_requests_total` | counter | `route` (template, e.g. `/shows/{showId}/reserve`, or `unmatched`), `method`, `code` | Every request, including 401s rejected by Spring Security |
| `http_request_duration_seconds` | histogram | `route` | Buckets 5 ms … 20 s |
| `db_pool_acquired` / `db_pool_idle` / `db_pool_connections` / `db_pool_max` | gauge | `pool` (`main`, `obs`) | Connections in use / idle / total / configured max |
| `db_pool_pending` | gauge | `pool` | Threads waiting for a connection right now |
| `db_pool_wait_seconds_{count,sum,max}` | summary | `pool` | Connection waits: count and total duration |
| `db_pool_timeouts_total` | counter | `pool` | Acquire timeouts (each became a 429) |
| `db_tx_retries_total` | counter | `operation`, `sqlstate` | Deadlock/serialization retries; expected to stay absent/zero |

Counters are per process: they reset on restart, and with N replicas they must be `sum()`-ed. The DB-derived gauges and
`/reconcile` are the source of truth (ADR-019). The burst tool checks that counter deltas equal the responses it saw.

### Example PromQL

```promql
# Confirmations and declines per second, by reason
sum(rate(seatres_reservations_confirmed_total[1m]))
sum by (reason) (rate(seatres_reservations_declined_total[1m]))

# Any 5xx in the last 5 minutes (must be 0)
sum(increase(http_requests_total{code=~"5.."}[5m]))

# Reserve latency p99
histogram_quantile(0.99, sum by (le) (rate(http_request_duration_seconds_bucket{route="/shows/{showId}/reserve"}[5m])))

# Seats by state for a show; available + held + confirmed is constant
sum by (status) (seatres_seats{show_id="<id>"})

# Pool pressure: average wait per acquisition, and waiters right now
rate(db_pool_wait_seconds_sum{pool="main"}[1m]) / rate(db_pool_wait_seconds_count{pool="main"}[1m])
db_pool_pending{pool="main"}

# Lock-order violations (should be empty)
sum by (operation, sqlstate) (rate(db_tx_retries_total[5m]))
```

## Logs

JSON (Logstash layout) on stdout, one access line per request (logger `access`), plus ERROR lines with stack traces
for anything unexpected. Probe and scrape routes (`/healthz`, `/readyz`, `/metrics`, `/actuator/**`) log their access
line at DEBUG. Tokens and secrets are never logged.

```json
{"@timestamp":"…","message":"POST /shows/{showId}/reserve 409 12ms","logger_name":"access","level":"INFO",
 "request_id":"4b0e…","user_id":"alice","show_id":"9ec5…","method":"POST","route":"/shows/{showId}/reserve",
 "status":409,"latency_ms":12,"outcome":"seat_taken","decline_reason":"seat_taken"}
```

| Field | Source |
|---|---|
| `request_id` | `X-Request-ID` if sent (log-safe chars, ≤ 128), else a UUID; echoed in the response header and every error body |
| `user_id`, `show_id`, `reservation_id` | Added once known (after authentication / route matching) |
| `method`, `route`, `status`, `latency_ms` | Access filter |
| `outcome` | `confirmed`, `idempotent_replay`, `cancelled`, `already_cancelled`, an error code (`seat_taken`, …), or `ok`/`error` |
| `decline_reason` | The `reason` label counted for this reserve request |

Levels: declines are INFO (they are normal under load); unexpected errors are ERROR with the stack trace; transaction
retries are WARN (exhausted retries are ERROR).

## The 2am paging list

Runnable as Prometheus rules in `deploy/prometheus/alerts.yml` (validated with `promtool check rules`).

| # | Page when | Rule | First look |
|---|---|---|---|
| 1 | **Any 5xx** | `SeatServiceAny5xx`: `increase(http_requests_total{code=~"5.."}[5m]) > 0` | ERROR log line with the same `request_id`; 503 = DB unreachable (check Railway Postgres), 500 = a bug |
| 2 | **A reconcile check fails** | `SeatServiceReconcileFailing` (blackbox probe requires `"ok":true`) and `SeatServiceSeatTotalChanged` | The failing check names in the `/reconcile` body; compare seats, reservations and `user_show_counts` for the show |
| 3 | **`db_tx_retries` spiking** | `SeatServiceTxRetriesSpiking`: rate > 0.1/s for 2 m; `SeatServiceContentionDeclines` for exhausted retries or timeouts | WARN `Retrying transaction operation=…`: a code path broke the global lock order (DESIGN.md §5) |
| 4 | **Readiness flapping** | `SeatServiceReadinessFlapping`: probe changed > 2× in 10 m, or `up` < 90% | `db_pool_*{pool="obs"}`, Postgres reachability; liveness should still be UP |
| 5 | **p99 latency over SLO** | `SeatServiceReserveP99OverSlo`: reserve p99 > 2.5 s for 10 m | Pool wait vs. hold time; DB CPU; is a burst in progress (expected) or not |
| 6 | **DB pool wait saturation** | `SeatServiceDbPoolSaturated` (> 50 waiting and avg wait > 2 s for 5 m), `SeatServiceDbPoolTimeouts` (any acquire timeout) | Timeouts become 429s; consider `DB_MAX_CONNS` against Postgres `max_connections`, or a waiting room for the on-sale |
