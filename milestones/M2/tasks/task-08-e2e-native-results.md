# M2 · Task 8 — End-to-end MCP harness + Claude Code registration + native + M2-RESULTS

> The milestone's verification + sign-off: all nine tools, over real stdio, in the native binary,
> driven by Claude Code, with results and decisions folded back into the PRD.

## Prerequisites (read first, fresh session)
- **Done before this:** tasks 1–7 (every tool wired).
- **Read:** M2 overview ; PRD §10 (M2 verification) + §6 (the nine tools) + §8 (targets) ;
  M1-RESULTS / M0-RESULTS (the measured-vs-target format to mirror).

## Protocol (test-first; full version in the overview)
Write the harness + failing end-to-end assertions → **STOP for review** → implement/run → verify.

## Scope — files to create/modify
- `src/test/java/jcma/mcp/McpEndToEndTest.java` — a scripted MCP client (drives the server over piped
  stdio, or launches `jcma serve` as a subprocess): `initialize` → `tools/list` (assert **all nine**
  §6 tools advertised with schemas) → a `tools/call` for **each** tool against the pinned corpora,
  asserting correct, **context-bearing**, **bounded** results (reuse the M1 worksheet oracles).
- **Native MCP smoke** — extend the native smoke check (M1) to pipe a real `initialize` +
  `tools/list` + one `tools/call` through the **native binary** and assert a well-formed answer;
  confirm no new reflection gaps (the hand-rolled JSON keeps this clean — verify, don't assume).
- **Claude Code registration (manual, recorded)** — register the binary as an MCP server, exercise
  each tool live from a Claude Code session; capture the transcript/notes in M2-RESULTS.
- `milestones/M2-RESULTS.md` — mirror M1-RESULTS: measured-vs-target table (search p95, find_* p95,
  per-tool result sizes vs. budget caps, native cold-start/RSS no-regression), one section per
  settled decision.
- **PRD fold-back** — update **§6** (any tool-schema/shape specifics now concrete) and **§11**
  (hand-rolled JSON ratified; pause-to-index ratified; token-budget policy + caps; transitive-
  hierarchy bound; call-hierarchy depth; member-summary/get_source bounds).

## Tests (red-first)
- `McpEndToEndTest`: the full nine-tool sweep green over stdio; every result parses, is
  context-bearing, and is within its budget cap (assert the truncation marker appears when forced).
- Native smoke: `initialize`+`tools/list`+`tools/call` through the binary → well-formed.

## Manual check
- Live Claude Code session against the registered binary: run each tool, eyeball usefulness +
  token economy; note anything to fix.

## Done when
- All 8 M2 tasks green · `nativeCompile` green · native MCP smoke passing · nine-tool end-to-end sweep
  green over stdio · Claude Code registration exercised live · `M2-RESULTS.md` written · PRD §6/§11
  updated · native cold-start/RSS not regressed vs M1 (14 ms / 25.8 MB).

## Notes
- This task is **integration + sign-off**, not new tool logic — if a tool needs real fixes they go
  back to its task. Keep this one about proving the whole surface and recording the numbers.
