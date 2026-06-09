# M2 · Task 2 — MCP stdio server: lifecycle, handshake, dispatch, error model

> The protocol surface (PRD §4: MCP is the *only* surface). Owns one `QueryService`; pauses to index.

## Decisions locked (overview)
- **Newline-delimited JSON-RPC over stdio** (one object per line), hand-rolled on task-1 JSON.
- **Pause-to-index on launch** — synchronous build-if-missing/stale, **after** the `initialize`
  reply but **before** the first `tools/call`. Background indexing is deferred to M3.

## Prerequisites (read first, fresh session)
- **Done before this:** task-1 (JSON layer).
- **Read:** M2 overview ; PRD §6 (tool surface table + transport) ; §5 (top two boxes) ;
  M1-RESULTS Task-12 (the `QueryService`/`AnalysisSession` this owns) ; `jcma.cli.Repl` (the
  in-process session model this generalises).
- **Reference, don't extend:** `SpikeC.mcpLoop()` — `initialize`/`tools/list` shapes, notification
  (no-id) handling, `-32601`, id-echo, `shutdown`/`exit`.

## Protocol (test-first; full version in the overview)
Write failing tests + transcripts → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/mcp/McpServer.java` — the stdio loop: read line → parse → route → write reply.
  Handles `initialize` (protocolVersion + `serverInfo` + `capabilities.tools`), `tools/list`
  (delegates to the schema registry), `tools/call` (dispatch to a `ToolHandler`), `ping`,
  `shutdown`/`exit`, EOF. Ignores notifications (no `id`). **JSON-RPC error model:** `-32700` parse,
  `-32600` invalid request, `-32601` method-not-found, `-32602` invalid params; a *tool* failure is a
  successful JSON-RPC result with `isError: true` content (MCP convention), never a transport error.
- `src/main/java/jcma/mcp/ToolHandler.java` — the seam: `name()`, `schema()` (input JSON schema for
  `tools/list`), `call(JsonValue args) → ToolResult`. A `ToolRegistry` holds the handlers.
- `src/main/java/jcma/mcp/ToolResult.java` — **minimal** result for now (text content + `isError`);
  task-3 enriches it into the full shaping/budget model. Keep the seam, not the richness, here.
- `src/main/java/jcma/cli/Serve.java` + `Main` dispatch — `jcma serve <repo>`: discover the
  workspace, **pause-to-index** (build/reconcile via the M1 `Indexer`/`Reconciler` if absent/stale,
  with a one-time stderr progress note), open one `AnalysisSession`→`QueryService`, run `McpServer`.
- **Observability:** per-`tools/call` metric (count + timer) via the existing `jcma.obs` registry.
- One real-but-trivial tool (e.g. a `ping`/health handler) proves the loop end-to-end; the §6 tools
  land in tasks 4–7.

## Tests (red-first)
- `McpServerTest` (in-process, piped streams): `initialize` → well-formed result with capabilities;
  `tools/list` → the registered schemas; a `tools/call` to the trivial tool → result; an unknown
  method → `-32601`; malformed JSON line → `-32700`; a notification (no id) → **no reply**; `exit` →
  loop terminates. The `initialize` reply arrives **without** waiting on indexing.
- `ServeLifecycleTest`: launching against a repo with **no** index builds it before the first
  `tools/call` answers (assert the index dir appears / a known symbol resolves); launching against an
  already-indexed repo does **not** rebuild (mmap-and-go; assert no re-index).

## Manual check
- `printf '{"jsonrpc":"2.0","id":1,"method":"initialize",...}\n{...tools/list...}\n' | jcma serve <repo>`
  — eyeball the handshake + tool list; native binary too.

## Done when
- tests green · native green (native MCP smoke: pipe `initialize`+`tools/list` to the binary) ·
  handshake never blocks on indexing · first `tools/call` is served against a fresh index.

## Decisions to settle / record
- **Missing-index UX** — synchronous build with a stderr note (recommended) vs. an `isError` "indexing"
  result the agent retries. Record the chosen behavior.
- **`initialize` protocolVersion** to advertise (pin the current MCP spec date the spike used unless
  Claude Code negotiates otherwise) — record it.
