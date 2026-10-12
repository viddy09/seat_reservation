# AI usage log

A factual running record of what was asked of the AI assistant (Claude Code), what it proposed, and where the human changed or overrode the proposal. This is source material for the "AI usage" section of WRITEUP.md, which the human writes in their own words.

---

## 2026-10-06 — Brief review

- **Asked:** read `docs/ASSIGNMENT.md` and understand it.
- **AI response:** summarised the brief; flagged that the file references itself as "the full assignment", contains leftover Go-specific instructions (pgxpool, slog, goroutines, `-race`, golangci-lint, `httpapi/store` layering), and that the workspace was not a git repo.
- **Human decision:** provided the GitHub repo URL.

## 2026-10-07 — Repo and environment setup

- **AI action:** `git init -b main`, added `origin` → `https://github.com/viddy09/seat_reservation.git`. Remote initially returned "Repository not found" (private repo / credentials); human resolved access, remote then reachable and empty.
- **AI observation:** local JDK is 21.0.4 (not 25); Docker 27.4 present; `gh` CLI absent.
- **Human decisions:**
  - Java **21** (over the brief's preferred 25).
  - Commit author `admin <prakashparmar.pp109@gmail.com>` (set repo-locally).
  - Base package: "as per standard and project scope" → AI proposed `com.paytmmoney.seatreservation`.
  - Asked whether Fly.io / Railway are free. AI checked both pricing pages: Fly.io trial 2 h machine time / 7 days, managed Postgres from $38/month; Railway $5 credit / 30 days without a card, then Hobby $5/month. AI recommended Railway; **human chose Railway**.

## 2026-10-07 — Phase 0 (design)

- **AI action:** verified Spring Boot latest GA via start.spring.io (4.1.1); checked Railway health-check semantics (deploy-time only), proxy limits (10k concurrent connections, 5-min requests), Postgres `max_connections` (100) and trial resources (1 GB / 2 vCPU).
- **AI proposal:** `docs/DESIGN.md` (requirements restatement, 21 ambiguities with resolutions, schema, lock order, transaction pseudocode, status-code table, metrics, sizing) and `docs/DECISIONS.md` (ADR-001…026).
- **Notable AI-proposed resolutions beyond the brief, pending human review:** 409 `contention` when retries are exhausted; 429 `server_busy` on pool/statement timeout vs 503 only for DB connectivity loss; 401 `invalid_admin_key` on `/auth/token`; `COLLATE "C"` on `seat_label`; gauges limited to the 50 most recent shows.
- **Human decision:** approved the design and all five flagged resolutions as proposed ("go ahead").

## 2026-10-07/08 — Phase 1 (skeleton)

- **Asked:** proceed with Phase 1 after Phase 0 approval.
- **AI action:** generated a reference project from start.spring.io (Boot 4.1.1) to confirm Boot 4 starter names and copied the Maven wrapper from it; looked up current plugin versions on Maven Central; inspected Boot 4 jars with `javap` for moved packages (`EnvironmentPostProcessor`, `HealthIndicator`, `JdbcConnectionDetails`, `ErrorController`).
- **AI output:** multi-module Maven build with Spotless/Checkstyle/`-Werror`; typed config; `DATABASE_URL` conversion; separate observability pool; `/healthz`, `/readyz`, `/metrics`; request-id filter; error envelope; Flyway V1 schema; Dockerfile, compose, Railway config, Makefile, CI; ADR-027…030.
- **Problems hit and fixed by the AI during verification:** Testcontainers failed while Docker Desktop was still starting (re-run passed); Hikari timeouts must be raw milliseconds, not `20s`; the `DATABASE_URL` post-processor initially checked the resolved property (always set by application.yml) instead of the raw `SPRING_DATASOURCE_URL` variable.
- **Human decision:** approved Phase 1 and asked to push and continue ("yes pls"). The first push failed with 403: the machine's saved GitHub credential belongs to account `prapp200`, which has no write access to `viddy09/seat_reservation`. AI reported the two fixes (add collaborator, or re-login as viddy09); pending on the human.
- **Environment note:** the human restarted the PC mid-phase; Docker Desktop's WSL VM stayed stopped until the AI force-restarted Docker Desktop and ran `wsl --shutdown`.

## 2026-10-08 — Phase 2 (shows, auth, reserve, idempotency)

- **AI output:** sealed domain declines; seat/idempotency/money rules; HS256 JWT resource server with sub/role validators and a dev token issuer; strict JSON; show create/get; the reserve transaction exactly as designed (key claim → conditional per-user upsert → ordered `FOR UPDATE` → conditional update with row-count assert); SQLSTATE-based retry and DB failure mapping; unit, integration and concurrency tests (I1, I2, I4, I6); ADR-031…034.
- **Scope note (stated to the human):** the reserve transaction already contains the per-user-limit step and handles multi-seat requests, because it is one algorithm; Phase 3 adds the dedicated multi-seat, limit and cancel concurrency tests.
- **Human decision:** "yes pls go ahead". Push was still refused (credential `prapp200`) and no Railway account/CLI was available to the AI, so the AI continued with Phase 3 locally and left the deploy steps with the human.
- **Problem hit:** 8 of 20 concurrency repetitions failed on the first run. Root cause was the test, not the service: repetitions reused the same user ids and idempotency keys on a new show, which the service correctly rejected as `idempotency_key_reused` (keys are scoped per user). Fixed by giving each repetition fresh user ids.

## 2026-10-09 — Phase 3 (multi-seat, per-user limit, cancel, reconcile)

- **AI output:** owner-only idempotent cancel in the global lock order; single-statement reconcile endpoint with six checks; endpoint tests (cancel, idempotent re-cancel, non-owner 404, rebook after cancel, replay after cancel, decline not memoised, all-or-nothing, reconcile detects corruption); concurrency tests repeated 5x (overlapping multi-seat in opposite orders, 10 parallel reserves with limit 4, parallel pairs, reconcile sampled during load, cancel racing 50 buyers, 20 concurrent cancels); ADR-035/036.
- **AI self-correction:** first added a REPEATABLE READ template for reconcile, then removed it because a single statement already reads one snapshot.
- **Result:** all 40 new tests passed on the first run; full build 126 tests green.
- **Human decision:** "continue" (x3). Then asked whether the AI was unable to connect to the repo; AI explained reads worked but pushes were denied to credential `prapp200` and gave two fixes. The human fixed access and pushed main themselves ("now check again"); AI verified origin/main == local 467c746 and that GitHub Actions CI completed with conclusion `success`.

## 2026-10-12 — Phase 4 (metrics, DB collector, access logs)

- **AI output:** reservation outcome counters (all reasons pre-registered), HTTP counter + latency histogram by route template, scrape-time seat gauge collector on the observability pool, `db_pool_*` via renaming Hikari meters for both pools, one JSON access line per request with request/user/show/reservation context, ADR-037…039.
- **Problems hit and fixed by the AI:** a deprecated `MultiCollector` method and a `this-escape` warning failed the `-Werror` build (removed the override; made the collector final); `db_pool_total` was silently exported as `db_pool` because Prometheus reserves `_total` for counters (renamed to `db_pool_connections`).
- **Result:** full build 132 tests green.
- **Human decision:** "continue" (Phase 4 accepted; proceed to Phase 5).

## 2026-10-12 — Phase 5 (burst tool, local load tests)

- **AI output:** `burst` Maven module (JDK HttpClient + virtual threads, fat jar), `burst.sh` (JDK or Docker-only), `make burst`; all eight scenarios from the brief; report with outcome table, latency percentiles, per-hot-seat winners, invariant samples, final counts, reconcile and metric deltas; non-zero exit on any violation; docs/LOADTEST.md; ADR-040/041.
- **Load-test findings and changes (AI-driven, numbers in docs/LOADTEST.md):**
  - Pool waits up to 11.5 s → added a single non-locking pre-read that also declines already-taken seats (sanctioned optional optimisation in the brief), keeping replays on the transactional path.
  - Doubling the pool did not help (app-side constraint locally); pool kept at 20.
  - Windows listen-backlog refusals → connect-only retries in clients, counted in the report.
  - Docker Desktop port forwarding inflated latency; in-network run measured separately.
- **Bugs the AI found and fixed while doing this:** the access log silently dropped every cancel line (`reservation_id` written twice → JSON encoder error); a metrics test wrongly treated the deliberate `show_id` gauge label as a leak; the burst poller shared the load's in-flight cap and sampled only 6 times per run (now 48).
- **Result:** full 20k burst PASS locally (in-network p99 1.76 s, 0 errors). Railway run pending the owner's project/URL.
- **Human decision:** "do all": proceed with every remaining item that does not need the Railway account.

## 2026-10-12 — Phase 6 (hardening and docs)

- **AI actions and evidence:** cold-start test (zero 5xx, ready in ~16–18 s); SIGTERM mid-burst (zero 5xx, graceful drain, correctness held); fresh-clone verification (`./mvnw verify` 138 tests green, `docker compose up --build` ready ~9 s, 20k burst PASS); float/secret grep; README examples executed against the running service.
- **AI output:** README.md, docs/WRITEUP.md (AI-usage section left as a TODO for the author), docs/OBSERVABILITY.md, docs/CHECKLIST.md, Prometheus alert rules (validated with promtool), blackbox and scrape configs, Grafana dashboard, ADR-042/043; heap lowered to 70% for the 1 GB container.
- **AI self-corrections:** the first draft of the alert rules referenced a metric that doesn't exist (`seatres_reconcile_failed`) and a convoluted balance expression; replaced with a blackbox probe of `/reconcile` and a simple seat-total rule before committing. The burst report counted samples taken while the service was down as invariant failures; now reported as "unreachable". An accidental throwaway commit was made and immediately undone with a soft reset before any push (history unaffected).
- **Still open at end of phase:** Railway deployment and the live burst; the author's AI-usage section.

## 2026-10-12 — Deployment to Railway

- **Asked:** "Can't you deploy on Railway?" AI explained it could via the Railway CLI if the human logged in (browser login can't happen in the agent session) and recommended `railway login` in the human's own terminal so no token appeared in chat. **Human:** logged in.
- **AI actions:** installed the Railway CLI; created project `seat-reservation`, a PostgreSQL service and an `app` service; generated `JWT_SECRET`/`ADMIN_API_KEY` locally (stored outside the repo at the owner's home directory, passed to Railway via stdin, never printed); set `DATABASE_URL=${{Postgres.DATABASE_URL}}` (verified it resolves to the private `postgres.railway.internal`), `ALLOW_DEV_TOKENS=true`, `DB_MAX_CONNS=20`; deployed with `railway up`; generated the domain https://app-production-70543.up.railway.app.
- **Verification:** live smoke flow; two full 20k bursts both PASS (0 × 5xx, 0 transport errors, p99 1,119 ms and 496 ms); 0 ERROR/WARN server log lines, 0 pool timeouts, 0 tx retries; redeploy polled every 0.5 s: 118/118 successful.
- **Noted:** Railway deprecates `railway.json` (works until 2026-12-01).

## 2026-10-12 — AI-usage section

- **Human override of the brief:** the brief said the AI must not write the WRITEUP "AI usage" section in the author's voice. Asked to write it for the author, the AI noted the original instruction and then drafted it at the author's request. It was restricted to facts in this log, the opinions it had first included were removed, and it is labelled "Drafted with the assistant at my request".
