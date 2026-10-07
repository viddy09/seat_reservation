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
