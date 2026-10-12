# Seat reservation service

A JSON API for selling show seats that stays correct under a ~20,000-request concurrent stampede: a seat is never sold
twice, contention never surfaces as a 5xx, the books balance at every instant, retries are exactly-once, the per-user
limit holds under parallel requests, and identity comes only from a verified token. Java 21, Spring Boot 4.1 on virtual
threads, PostgreSQL 16 with explicit SQL; Postgres row locks and constraints are the entire concurrency control.

**Live URL:** _pending deployment (Railway)_ · **Design:** [docs/WRITEUP.md](docs/WRITEUP.md) ·
**Decisions:** [docs/DECISIONS.md](docs/DECISIONS.md) · **Observability:** [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md) ·
**Load tests:** [docs/LOADTEST.md](docs/LOADTEST.md)

## Quickstart

Requires Docker. From a fresh clone, no other steps:

```bash
docker compose up --build        # app on http://localhost:8080, Postgres 16 alongside
curl -s localhost:8080/readyz    # {"status":"UP"} once migrations have run (~15 s)
```

Compose ships safe local defaults (`ADMIN_API_KEY=local-dev-admin-key`, dev tokens on). Override any of them via a
`.env` file; see [.env.example](.env.example) for every variable.

Other targets (need JDK 21 for the Maven ones; `./mvnw` downloads Maven itself):

| Command | What |
|---|---|
| `make up` / `make down` | compose up (waits for readiness) / down and delete the DB volume |
| `make test` | `./mvnw verify`: compile with `-Xlint:all -Werror`, Checkstyle, all tests (Testcontainers needs Docker), Spotless |
| `make lint` / `make fmt` | Spotless + Checkstyle / apply formatting |
| `make run` | Postgres in Docker, app on the host (`JWT_SECRET` must be set, ≥ 32 chars) |
| `make burst URL=…` | run the burst tool (below) |
| `make logs` | follow the app's JSON logs |

## Using the API

Examples use `curl` and `jq`.

```bash
BASE=http://localhost:8080
ADMIN_KEY=local-dev-admin-key

# Tokens (dev issuer, enabled by ALLOW_DEV_TOKENS=true). User tokens need only a user_id.
ADMIN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -H "X-Admin-Key: $ADMIN_KEY" -d '{"user_id":"ops","role":"admin"}' | jq -r .token)
ALICE=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}' | jq -r .token)

# Create a show (admin): every seat starts available; per_user_limit defaults to 4
SHOW=$(curl -s -X POST $BASE/shows -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"Gala","price_paise":25000,"per_user_limit":4,"seats":["A1","A2","A3","B1"]}' | jq -r .id)

# View a show: per-seat status and counts {total, available, held, confirmed}
curl -s $BASE/shows/$SHOW | jq '{counts, seats}'

# Reserve (201). The idempotency key is required (header or "idempotency_key" in the body).
RES=$(curl -s -X POST $BASE/shows/$SHOW/reserve -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE" -H 'Idempotency-Key: order-1' -d '{"seats":["A2","A1"]}')
echo "$RES" | jq .
RID=$(echo "$RES" | jq -r .reservation_id)

# Retry with the same key and body: 200 + Idempotent-Replayed: true, same reservation_id
curl -si -X POST $BASE/shows/$SHOW/reserve -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE" -H 'Idempotency-Key: order-1' -d '{"seats":["A1","A2"]}' | grep -i replayed

# Owner-only lookup and cancel (another user gets 404); cancel is idempotent
curl -s $BASE/reservations/$RID -H "Authorization: Bearer $ALICE" | jq .status
curl -s -X POST $BASE/reservations/$RID/cancel -H "Authorization: Bearer $ALICE" | jq .status

# Books balance for the show; health; metrics
curl -s $BASE/shows/$SHOW/reconcile | jq '{ok, checks: [.checks[] | {name, ok}]}'
curl -s $BASE/healthz; curl -s $BASE/readyz
curl -s $BASE/metrics | grep ^seatres_
```

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/auth/token` | none (exists only with `ALLOW_DEV_TOKENS=true`; admin role needs `X-Admin-Key`) | 200 `{token, expires_at}` | 400, 401 `invalid_admin_key` |
| POST | `/shows` | admin | 201 show | 400, 401, 403, 422 |
| GET | `/shows/{id}` | public | 200 seats + counts | 404 |
| POST | `/shows/{id}/reserve` | user | 201 / 200 replay | 400, 401, 404, 409 `seat_taken`/`per_user_limit`/`idempotency_key_reused`, 422 |
| GET | `/reservations/{id}` | owner | 200 | 401, 404 |
| POST | `/reservations/{id}/cancel` | owner | 200 | 401, 404 |
| GET | `/shows/{id}/reconcile` | public | 200 `{ok, checks, numbers}` | 404 |
| GET | `/healthz` · `/readyz` · `/metrics` | none | 200 (readyz 503 when the DB is unreachable) | |

Every error has one shape, with the request id echoed from (or generated for) `X-Request-ID`:

```json
{"error":{"code":"seat_taken","message":"Some requested seats are no longer available","details":{"seats":["A1"]}},"request_id":"…"}
```

Money is integer paise (`price_paise`, `amount_paise`); `250.5`, `"250"` and `1e3` are rejected with 400. A `user_id`
in any request body is ignored: identity is the token's subject.

## Burst tool

Fires a concurrent stampede and verifies every invariant; exits 0 only if all checks pass (1 = a check failed,
2 = usage/setup error). Works with a local JDK 21, or with only Docker installed.

```bash
ADMIN_API_KEY=local-dev-admin-key ./burst.sh http://localhost:8080
ADMIN_API_KEY=<key> ./burst.sh https://<live-url> --concurrency 300 --total 20000
make burst URL=http://localhost:8080
```

Options: `--concurrency` (in flight, 500), `--total` (20000), `--seats` (1000), `--hot-seats` (5), `--storm-size`
(users per hot seat, 500), `--users` (2000), `--admin-key` (default `$ADMIN_API_KEY`). Docker-only on macOS/Windows:
use `http://host.docker.internal:8080` for a local target.

Scenarios, run concurrently: hot-seat storms, random 1–4 seat stampede, same key ×50, same key with different seats ×20,
one user ×10 against a limit of 4, a spoofed `user_id` body, cancel + re-book, and invariant sampling (`GET /shows` and
`/reconcile`) throughout. Sample output (local compose, 2026-10-12):

```text
Burst finished in 24226 ms: 20000 reserve requests, 1 cancels

Outcome distribution (reserve requests)
  201 created                             626
  200 idempotent replay                    49
  4xx idempotency_key_reused               19
  4xx per_user_limit                       30
  4xx seat_taken                        19276
  5xx                                       0
  transport errors                          0
  client connect retries (never sent)       0

Latency (ms):  p50=497  p95=1248  p99=1756  max=3931

Hot seats (winners / contenders)
  A1     1 / 500   …   A5     1 / 500

Final show counts: {"total":1000,"available":35,"held":0,"confirmed":965}
Final reconcile ok: true

Checks
  [PASS] zero 5xx                                   [PASS] same key x50: one reservation, rest replays
  [PASS] zero transport errors                      [PASS] same key, different seats: one winner
  [PASS] hot seat A1..A5 has exactly one winner     [PASS] one user, 10 parallel reserves: at most 4 seats
  [PASS] invariant held at every sample (48)        [PASS] spoofed body user_id ignored
  [PASS] final reconcile ok                         [PASS] cancel then re-book by another user
  [PASS] confirmed seats == 201s minus cancelled    [PASS] metrics confirmed/cancelled/declined == observed

RESULT: PASS
```

## Metrics and logs

- `GET /metrics` (Prometheus): outcome counters, seat gauges read from the DB at scrape time, HTTP latency histogram by
  route, `db_pool_*`, `db_tx_retries_total`. Example queries, a Grafana dashboard (`deploy/grafana/`) and alert rules
  for the paging list (`deploy/prometheus/`) are described in [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md).
- Logs: JSON on stdout, one access line per request with `request_id`, `user_id`, `show_id`, `route`, `status`,
  `latency_ms`, `outcome`. Locally `docker compose logs -f app`; on Railway, the service's **Logs** tab.

## Design in one paragraph

Each reservation is one READ COMMITTED transaction that claims the idempotency key (`INSERT … ON CONFLICT DO NOTHING`),
increments the user's seat count only if it stays within the limit (conditional upsert), locks the requested seats
`FOR UPDATE` in label order, and confirms them with a conditional `UPDATE` whose row count is asserted. Every
transaction locks reservation → user count → seats in that order, so there are no deadlocks; losers wait for the lock
and then see the seat taken, so a hot seat always ends with exactly one winner. Readiness and seat gauges use their own
small connection pool so a saturated main pool can't make the service look unhealthy. Full reasoning in
[docs/WRITEUP.md](docs/WRITEUP.md).

## Deploying (Railway)

`railway.json` builds the Dockerfile, health-checks `/readyz`, never sleeps, and restarts on failure. Create a project
with a PostgreSQL service and this repo as a service in the same region, then set on the app service:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` (private network; converted to JDBC at startup) |
| `JWT_SECRET` | ≥ 32 random characters (`openssl rand -base64 48`) |
| `ADMIN_API_KEY` | a secret; required to mint admin tokens |
| `ALLOW_DEV_TOKENS` | `true` |
| `DB_MAX_CONNS` | `20` (Railway Postgres allows 100 connections) |

Secrets live only in platform variables; nothing secret is committed.

## Repository layout

```
seat-service/   the API (config, api, domain, service, persistence, security, observability) + tests
burst/          the load and correctness tool (standalone fat jar)
deploy/         Prometheus scrape config, alert rules, blackbox modules; Grafana dashboard
docs/           ASSIGNMENT, DESIGN, DECISIONS, WRITEUP, OBSERVABILITY, LOADTEST, AI_LOG
```
