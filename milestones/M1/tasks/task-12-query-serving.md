# M1 · Task 12 — Virtual-thread cancellable, time-boxed query serving
> Non-blocking, cancellable, bounded query loop (engine-side; MCP wiring is M2).

## Prerequisites (read first, fresh session)
- **Done before this:** task-10 (EdgeResolver), task-11 (invalidation) — the work being scheduled.
- **Read:** PRD §5.1 (cancellable/time-boxed serving) + §6 ; M1 overview ; M0-RESULTS
  §"Performance & memory (Spike B)" (latency targets).
- **Port from M0 (reference, don't extend):** `SpikeB` latency harness (p50/p90/p99) for the perf tests.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/query/QueryService.java` — structured-concurrency execution,
  **cancellation** + **time-box**; on timeout return best-effort partial + the unconfirmed tail
  (never crash, never hang).
- `src/main/java/jcma/cli/` — `--deadline <ms>` flag on query subcommands.

## Tests (red-first)
- Unit: a long resolve is cancelled and returns promptly; a time-boxed query returns partial
  results flagged unconfirmed; concurrent queries don't corrupt the overlay (race test).

## Manual CLI check
- `jcma refs <hot-symbol> --deadline 50ms` → partial+unconfirmed within budget on jackson-databind.

## Done when
- tests green · native green · cancellation/timeout return partial+unconfirmed within budget ·
  warm find_references p95 < 200 ms with the edge cache (§Targets) — fold into M1-RESULTS.
</content>
