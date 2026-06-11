# M3 · Task 5 — Routing + PRD fold-in

> Now safe to ship because coverage is closed (tasks 02–04 made `grep_java` the no-hole front
> door): make the grep→`grep_java` routing **portable** (it ships with the server, zero client
> config), update CLAUDE.md, and fold the LOCKED M3 decisions into the PRD. Last in M3 — depends on
> tasks 02–04, because routing onto an incomplete `grep_java` would reintroduce holes.
>
> **No PreToolUse hook.** (Dropped 2026-06-11, with the user.) A hook is Claude-Code-only harness
> config in `settings.json`; jcma is an MCP server meant to work with *any* client at zero config
> (the whole point of `alwaysLoad`/`_meta` — see memory `mcp-tools-deferred-by-harness`). Asking
> every adopter to hand-author a grep-intercepting hook walks that back, and non-Claude clients
> can't honor it anyway. The only routing layer jcma *controls and ships to every client* is the
> **MCP server instructions + each tool's own description** — so that is where routing lives.

## Prerequisites (read first, fresh session)
- **Done before this:** task-02/03/04 — `grep_java` is the full no-hole front door (both tiers,
  regex, graceful overflow).
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` ; `docs/grep-java-degrade-to-text.md`
  (Ordering + PRD integration) ; memory `mcp-tools-deferred-by-harness` (why the lever is
  server-side `alwaysLoad`, not host config) + `beat-grep-by-subsuming-it` (why coverage precedes
  routing, and why even an un-deferred tool + a CLAUDE.md line wasn't enough until the hole closed) ;
  current `CLAUDE.md` "Navigating Java" line ; `McpServer.INSTRUCTIONS` (the server-instructions
  string) ; PRD §6 + §5.1 + §11.

## Scope
- **MCP server instructions** (`McpServer.INSTRUCTIONS`): extend the existing navigation guidance to
  name `grep_java` as the **grep replacement** for `.java` searches — symbols-first, degrades to
  labelled text, so it is never worse than grep on coverage. This is the portable lever: it is
  returned in the `initialize` result and reaches every MCP client with no `.mcp.json` or hook
  setup. Keep it to a sentence or two (sent once per session). `grep_java`'s own tool description
  already self-advertises ("use instead of grep for `.java`") — keep them consistent, don't
  duplicate at length.
- **CLAUDE.md:** update the "Navigating Java" line to add `grep_java` as the grep-replacement front
  door (symbols-first, degrades to text), alongside the existing nav tools. This is jcma's own
  dogfood — Claude Code reads CLAUDE.md; it is the in-repo analog of the server instructions.
- **PRD fold-in (on acceptance):**
  - §6 (tools): add the `grep_java` row — `match`/`output`/`path`/`limit`/regex params, labelled
    tiers, not-exhaustive contract.
  - §5.1 (index): add the full-text segment (`text.seg`: literals/comments/Javadoc, inline-snapshot
    scanned, **not** trigram), its format + **measured** footprint (≈ 411 KB on this repo / ~3.9 MB
    on commons-lang, per the M3 design note — reversing D1's sibling-trigram idea on that evidence).
    Add `text.seg` to the physical-layout segment list.
  - §11 (undecided): remove the now-resolved items (the M3 index-structure/footprint and tool-surface
    decisions); leave any still-open ones.

## Out of scope (explicitly)
- **No `PreToolUse`/`Grep`/`Glob` hook**, in this repo's `settings.json` or shipped to adopters.
  Routing is advisory and server-side. (If *you personally* want Claude Code to nag you while
  dogfooding, that's a local convenience you can add by hand — it is not a project deliverable and
  not part of this task.)
- No change to non-Java search behavior: Gradle/JSON/MD/etc. stay on built-in grep (per D5 — jcma is
  a strict superset of grep only for `.java`).

## Protocol
- Server-instructions + CLAUDE.md are behaviour/config, not engine logic — validate by
  **observation**, not assertion. In a fresh session, confirm via the call-log
  (`${XDG_CACHE_HOME:-$HOME/.cache}/jcma/logs/`) that `.java` searches actually route to `grep_java`
  and non-Java searches still use built-in grep. (No red-green unit gate; but do not declare done on
  assertion — show the routing in a real session. The dogfood-serve recipe is memory
  `dogfood-serve-xdg-cache`.)
- PRD edits: keep `PRD.md` = stable what/why; this doc = how. Sync per the repo convention.

## Done when
- the MCP server instructions name `grep_java` as the portable `.java` grep replacement (ships to
  every client, zero config) · CLAUDE.md names `grep_java` as the Java grep replacement in the
  "Navigating Java" line · routing **verified live in a session via the call-log** (`.java` →
  `grep_java`, non-Java → built-in grep), not asserted · PRD §6/§5.1 updated, §11 pruned · the M3
  design note and overview marked delivered · **no hook added**.
