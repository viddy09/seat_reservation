# Definition of Done: self-review

Evidence for each item of the assignment's Definition of Done, as of 2026-10-12, after the Railway deployment.


| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | `git clone` → `docker compose up --build` works with no manual steps; `./mvnw verify` passes; CI green | **Done** | Fresh clone of `8aa802f` into an empty directory: `./mvnw -B verify` → 138 tests, BUILD SUCCESS; `docker compose up --build` → `/readyz` 200 after ~9 s; full 20k burst against it → RESULT: PASS (0 × 5xx). GitHub Actions green on every pushed head (`467c746`, `e7cfc16`, `6201a17`, …). |
| 2 | Live URL up, `/readyz` 200, and readiness 503 when the DB is unreachable | **Done** | Live: https://app-production-70543.up.railway.app `/readyz` → 200 `{"status":"UP"}`. DB-unreachable behaviour proven locally (the live database was not taken down): Local: stopping Postgres → `/readyz` 503 in 1.3 s, `/healthz` stays 200, recovery ~4 s after restart. |
| 3 | Burst against the live URL: zero 5xx, one winner per hot seat, invariant held at every sample, metrics reconciled, limit held | **Done** | Live, two runs of 20k @ 500 in flight: both PASS, 0 × 5xx, 0 transport errors, 1/500 winners on each hot seat, 32 and 30 invariant samples all ok, counter deltas == observed, limit held (p99 1,119 ms and 496 ms). Local 20k: 0 × 5xx, 1/500 winners on each of 5 hot seats, 48/48 samples ok, counter deltas == observed, 4 of 10 granted at limit 4 (docs/LOADTEST.md). |
| 4 | Idempotency: replay → 200 with the same `reservation_id`; same key, different body → 409 | **Done** | `ReserveApiIT.replay_returns200WithOriginalReservation`, `sameKeyDifferentBody_returns409`; `ReserveConcurrencyIT.I4_*` (×5); burst checks "same key x50" and "same key, different seats". |
| 5 | Spoofed body identity has no effect; non-owner cancel → 404 | **Done** | `ReserveApiIT.spoofedUserIdInBody_isIgnored`, `ReserveConcurrencyIT.I6_SpoofedUserIdInBodyIsIgnoredUnderConcurrency` (×5), `CancelAndReconcileApiIT.nonOwnerCancel_is404AndSeatStaysConfirmed`; burst check "spoofed body user_id ignored". ArchUnit: only `CurrentUser` reads the security context. |
| 6 | Logs are JSON with a request_id; log access method documented | **Done** | `ObservabilityIT.accessLogIsOneJsonLinePerRequestWithContext`, `cancelAlsoGetsItsAccessLine`; docs/OBSERVABILITY.md "Logs" (compose: `docker compose logs -f app`; Railway: service Logs tab). |
| 7 | All money is int64 paise; no float anywhere (grep to prove it) | **Done** | `grep -rnE --include='*.java' '^\s*[^/* ].*\b(float\|double\|Float\|Double\|BigDecimal)\b' .` → no matches (the only occurrences of the words are "double-sell" in two comments). Enforced by Checkstyle and a CI step. `price_paise`/`amount_paise` are `bigint`; `Math.multiplyExact`; `250.5`, `"250"`, `1e3` rejected with 400. |
| 8 | README, WRITEUP, DECISIONS, OBSERVABILITY, AI_LOG complete; AI-usage section left for the author | **Done** | README.md; docs/WRITEUP.md (§7 is a TODO for the author); docs/DECISIONS.md ADR-001…043; docs/OBSERVABILITY.md; docs/AI_LOG.md; plus docs/LOADTEST.md. |
| 9 | Commit history incremental and readable | **Done** | Conventional Commits, one logical change each (`feat(reserve)`, `test(concurrency)`, `perf(reserve)`, `fix(logs)`, …); no squashes, no rewritten history. |

## Invariants → proof

| Invariant | Automated proof (real Postgres, real concurrency) |
|---|---|
| I1 No double-sell | `I1_HotSeatStorm_ExactlyOneWinner` (500 users, ×5); `I1_OverlappingMultiSeatInOppositeOrders_NoDeadlockAllOrNothing` (×5); DB `CHECK` constraints (`SchemaIT`); burst: one winner per hot seat |
| I2 Zero 5xx | Asserted in every concurrency test and every burst |
| I3 Reconciliation at every instant | `I3_ReconcileHoldsDuringConcurrentLoad` (60 samples mid-burst, ×5); `Invariants.assertHold` after every concurrency test; burst samples throughout |
| I4 Idempotency | `I4_SameKeyFiftyTimes_OneReservationRestReplays`, `I4_SameKeyDifferentSeats_ExactlyOneWinsRestRejected` (×5) |
| I5 Per-user limit | `I5_TenParallelReserves_LimitFour_ExactlyFour`, `I5_ParallelMultiSeatRequests_NeverExceedLimit` (×5) |
| I6 Token identity, owner-only cancel, no cross-reservation release | `I6_SpoofedUserIdInBodyIsIgnoredUnderConcurrency`, `I6_CancelRacingReserve_NoResurrectionOrDoubleAssignment`, `I6_ConcurrentCancelsOfOneReservation_ReleaseExactlyOnce` (×5) |

## Remaining

- Author writes the AI-usage section of docs/WRITEUP.md from docs/AI_LOG.md.
