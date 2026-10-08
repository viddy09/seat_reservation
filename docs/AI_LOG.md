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
- **Problem hit:** 8 of 20 concurrency repetitions failed on the first run. Root cause was the test, not the service: repetitions reused the same user ids and idempotency keys on a new show, which the service correctly rejected as `idempotency_key_reused` (keys are scoped per user). Fixed by giving each repetition fresh user ids.
