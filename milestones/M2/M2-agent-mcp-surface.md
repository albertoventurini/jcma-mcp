# M2 — Agent-native MCP surface (overview)

> **Type:** deliverable (production code). **Predecessor:** M1 (done; core engine + index green,
> `milestones/M1-RESULTS.md`). **Parent:** `PRD.md` §6 (tool surface) · §5 (system design, top two
> layers) · §2 (agent-native principles) · §8 (latency/token targets). **This doc is the *how*; PRD
> is the *what/why*** — consult PRD by section, on demand, not wholesale.

## Why this milestone

M1 shipped the engine + index + a one-writer, cancellable `QueryService` over an `AnalysisSession`,
verified through a dev-only `jcma` CLI. **M2 puts the agent-facing surface on top of it:** the MCP
stdio server (the *only* protocol surface, PRD §4), the §6 tools, and the agent-response layer
(grouping, snippets, FQNs, token budgeting/truncation) — the two top boxes of the §5 diagram.

M1 already serves several tools' backends through `QueryService`/`AnalysisSession`
(`find_definition` by symbol+position, `find_references` grouped with the unconfirmed tail, **direct**
`supertypes`/`subtypes`) and leaves others unwired behind the engine seam (`resolveType` →
`get_type_at`; `StructuralParser` → `outline`). So M2 is **two kinds of work**: the MCP/response
plumbing, **and** filling the backend gaps (`search_symbols` session passthrough, `get_type_at`,
`outline`, `get_source`, `call_hierarchy`, transitive hierarchy). Both live under M2.

The M0 spike `SpikeC.mcpLoop()` (newline-delimited JSON-RPC, naive substring JSON) is **reference,
not a base to extend** — `tools/call` needs real argument parsing, so a proper JSON layer is task 1.
Spikes under `milestones/m0-spike/` stay intact.

## Decisions locked for M2 (settled with the user 2026-06-09 — do not relitigate)

- **Hand-rolled JSON + hand-rolled MCP/JSON-RPC** (not the official MCP Java SDK). The SDK is
  Jackson + Project Reactor — reflection-heavy, the exact native-image fragility M0/M1 avoided (cf.
  M1 choosing plain atomic counters over Micrometer). Our surface is three methods over tiny
  messages, and we *want* full schema control (the §6 differentiator). Closes PRD §6's "evaluate the
  SDK only if native-image-friendly" — we do not. *(If ever revisited, the bar is a timeboxed
  `nativeCompile` spike proving the SDK builds clean — not assumed.)*
- **MCP transport = newline-delimited JSON over stdio** (one JSON-RPC object per line, as the spike
  loop framed it), launched as a subprocess by Claude Code.
- **Pause-to-index on launch (background-indexing deferred to M3).** The server uses the persisted
  index; if it is **missing/stale it builds synchronously at startup** (reusing the M1 `Indexer` +
  `Reconciler`), *after* the `initialize` response but *before* the first `tools/call` is served — so
  the handshake stays prompt and the first answer is always against a fresh index. Warm = `mmap +
  go`. Justified by M1 actuals: a cold parse-only scan is ~0.29 s (commons-lang) / ~0.48 s (jackson
  slice) — sub-second on medium repos. The **start-instantly-and-index-in-background** state machine
  earns its complexity only on large (~1M LOC) repos and is M3's "large-repo perf" charter, deferred
  by measurement, not assumed.
- **Transitive type hierarchy** (not direct-only). M1's `supertypes`/`subtypes` return direct edges;
  M2's `find_subtypes`/`find_supertypes`/`find_implementations` walk them transitively with a depth
  bound (task 5).
- **Deliverable = separate task files** (`tasks/task-NN-*.md`), one per task, each pickable in a
  fresh Claude Code session.

## Targets — carried from PRD §8 / M1-RESULTS (calibrate, don't invent)

The bars M2 work is measured against (the latency budgets are already met by the M1 engine; M2 must
not regress them and adds **token-bound** targets the response layer owns).

| Aspect | M2 target | Source |
|---|---|---|
| `search_symbols` p95 (served from index) | < 50 ms | PRD §8 |
| `find_definition` / `find_references` p95 | < 200 ms | PRD §8 (M1 warm p95 = 2.61 ms) |
| Tool result size | **bounded + truncation-marked**; per-tool caps calibrated from real output | PRD §2 #4, §6 |
| MCP `initialize` → handshake reply | prompt (never blocks on indexing) | this doc, pause-to-index |
| Native binary: cold start / RSS | < 100 ms / < 100 MB (no regression) | M1-RESULTS (14 ms / 25.8 MB) |

## Standard task protocol (applies to every task)

Same **test-first, checkpointed** unit as M1, ~30–40% of a context window.

1. **Write failing tests first** (+ any sample Java fixtures *and* the scripted MCP request/response
   transcripts they assert against). Tests compile but fail (red).
2. **STOP — checkpoint for review.** Present the test list + fixtures/transcripts + intended
   assertions and **wait for approval** before implementing. (Hard gate — never collapse into one
   turn.)
3. **Implement** to green.
4. **Verify three ways:** (a) `./gradlew test` green; (b) `./gradlew nativeCompile` green + the
   native CLI/MCP smoke check still passes; (c) the **manual check** for that task — drive the MCP
   server over stdio with a scripted `tools/call` (or `jcma serve` against a real repo) and eyeball
   the result.

**Test layering, every task:**
- **Unit** — hand-authored JSON/transcripts or tiny Java fixtures exercising exactly the feature.
- **Integration** — scripted MCP sessions over the **pinned M0 corpora** reused in-place
  (`milestones/m0-spike/corpus/commons-lang`, `…/jackson-databind`); reuse the M0/M1 hand-labeled
  worksheets as oracles where relevant.
- **Manual** — a scripted stdio session (or `jcma serve`) eyeballed, and an end-to-end Claude Code
  registration at task 8.

## Project structure (extends M1's `src/main/java/jcma/`)

```
src/main/java/jcma/
├─ mcp/            hand-rolled MCP/JSON-RPC stdio server (loop, framing, dispatch, error model)
│   └─ json/       hand-rolled JSON reader/writer/value (native-clean, no Jackson)
├─ tools/          the §6 tool handlers (ToolHandler impls) over QueryService
├─ response/       agent-response shaping: ToolResult model, grouping, snippets, FQNs, token budget
└─ cli/            `jcma serve` launches the MCP server (joins the existing dev CLI)
```

`milestones/m0-spike/` stays untouched (reference). `mcpLoop()` + the spike's JSON helpers are the
reference for task 1–2, re-typed into production shape, not copied.

## Ports inventory (from the M0 spike — reference, not a base to extend)

- **`SpikeC.mcpLoop()`** — the newline-delimited stdio loop, `initialize`/`tools/list` shapes,
  notification handling, `-32601` method-not-found, the id-echo. → tasks 1, 2 (JSON re-done properly).
- **`SpikeC.jsonStringField`/`jsonRawField`** — the naive extractors; the *cautionary* reference for
  why task 1 builds a real reader (escaping, nesting, the `arguments` object). → task 1.

## Task index

| # | File | Goal |
|---|------|------|
| 1 | [task-01-json-layer.md](tasks/task-01-json-layer.md) | Hand-rolled native-clean JSON reader/writer/value |
| 2 | [task-02-mcp-server-lifecycle.md](tasks/task-02-mcp-server-lifecycle.md) | MCP stdio server: lifecycle, `initialize`, `tools/list`, `tools/call` dispatch + errors; owns one `QueryService`; **pause-to-index** |
| 3 | [task-03-response-budget.md](tasks/task-03-response-budget.md) | Agent-response layer: `ToolResult` model + grouping/snippets/FQNs + **token budget & truncation** |
| 4 | [task-04-tools-core-navigation.md](tasks/task-04-tools-core-navigation.md) | Tools batch 1: `find_definition`, `find_references`, `search_symbols` |
| 5 | [task-05-tools-type-hierarchy.md](tasks/task-05-tools-type-hierarchy.md) | Tools batch 2: `find_supertypes`, `find_subtypes`, `find_implementations` (**transitive**) |
| 6 | [task-06-tools-engine-wrappers.md](tasks/task-06-tools-engine-wrappers.md) | Tools batch 3: `get_type_at`, `outline`, `get_source` (thin engine backends) |
| 7 | [task-07-call-hierarchy.md](tasks/task-07-call-hierarchy.md) | `call_hierarchy` — `CALLS`-edge traversal, callers/callees, grouped |
| 8 | [task-08-e2e-native-results.md](tasks/task-08-e2e-native-results.md) | End-to-end MCP harness + Claude Code registration + native; `M2-RESULTS.md` + PRD §11 fold-back |

Dependency order: 1 → 2 → 3 → {4, 5, 6, 7 independent} → 8.

## Exit criteria (definition of done)

- All 8 tasks green (unit + integration), `nativeCompile` green, native MCP smoke passing.
- **PRD §10 M2 verification reproduced end-to-end:** drive the server over stdio with scripted
  `tools/call` for every §6 tool and assert correct, context-bearing, **bounded** results; register
  the binary with Claude Code and exercise it live.
- `milestones/M2-RESULTS.md` (mirroring M1-RESULTS): measured-vs-target table, decisions ratified
  (hand-rolled JSON, pause-to-index, token-budget policy, transitive-hierarchy depth, call-hierarchy
  depth) folded back into **PRD §11** / §6.
- Spikes under `milestones/m0-spike/` left intact (reference).

## Open decisions to resolve *during* M2 (record as ratified, like M1)

- **Token-budget policy** (Task 3) — per-tool result caps + truncation markers, behind a swappable
  policy, **instrumented**, calibrated from real output sizes (not round numbers). *(PRD §6/§8.)*
- **Transitive hierarchy depth bound** (Task 5) — default depth + how a truncated walk is marked.
- **`call_hierarchy` depth** (Task 7) — one level vs. recursive (recommend one level + explicit
  "expand" via a follow-up call; decide on measurement).
- **`get_type_at` member-summary depth** and **`get_source` size bound** (Task 6).
- **Missing-index UX** (Task 2) — the exact `tools/call`-before-index-ready behavior under
  pause-to-index (synchronous build with a one-time stderr progress note).
