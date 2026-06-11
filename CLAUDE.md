# Working in this repo (guidance for Claude Code)

**jcma** — an agent-native Java (JDK 25+) code-intelligence engine. Sole consumer is an AI
coding agent, exposed over **MCP only** (no LSP); built for low memory + instant start +
semantic navigation. The repo is **greenfield: no code exists yet** — don't hunt for source to
extend.

The essentials are below, so you usually don't need to read more for a small task.
`PRD.md` is the full design and the source of truth, but it's large — **consult it by section,
on demand** (e.g. touching the index → §5.1; engine/fallback → §4; tools → §6), rather than
reading it wholesale. `milestones/*` hold the executable plans.

## Settled decisions — do not relitigate
Decided deliberately; reopen only with new evidence, and if you do, update `PRD.md`.
- **MCP only.** No LSP / `textDocument/*` / editor surface.
- **Engine = JavaParser + JavaSymbolSolver**, *pending the M0 spike*; fallback is a
  **javac-hybrid on HotSpot** (PRD §4). Both behind an `AnalysisEngine` interface.
- **Distribution = GraalVM native-image** (single binary). Native-image is reachable *only*
  because the core avoids `javac` — don't pull `javac`/`com.sun.source` into the native path.
- **Index = custom memory-mapped store** (PRD §5.1): graph model, both edge directions,
  lazy-resolve-and-cache, LSM base+overlay, filesystem-driven freshness, validate-on-read.
  Not SQLite, not a graph-DB engine.
- **No compiler-grade diagnostics in core** (agents get correctness by running the build).

## Current focus
**M0 (de-risking spike) is the gate** — [`milestones/M0-de-risking-spike.md`](milestones/M0-de-risking-spike.md).
**M1–M3 deferred** until M0 fires: M1 is partly contingent on M0's *results* (Spike-A failure
histogram, Spike-B perf curves), not just its GO/FALLBACK verdict — don't pre-draft it in detail.

## Conventions
- **`PRD.md` = stable what/why; `milestones/*` = executable how.** Keep them in sync when a
  decision changes; PRD §11 tracks what's still undecided.
- **A milestone doc downstream of a gate is written _after_ the gate fires** (or carries
  provisional markers). Don't plan against assumptions a gate exists to validate.
- **M0 spike code is kept, not deleted** — minimal/ugly is fine, but it's a valued reference for
  later stages, so it stays under `milestones/m0-spike/`. M1 starts as fresh production code
  (doesn't extend the spikes), but the spikes are retained, not thrown away.
- Running autonomously: follow each milestone's **Execution notes** — proceed on proposed
  defaults, record the choices made, block on a human only when genuinely stuck.
- **Navigating Java in this repo** ("where is X declared / who uses X / find a type by partial
  name") → call jcma's MCP tools (`search_java_symbols`, `find_java_definition`,
  `find_java_references`), not Grep/Read. To **search `.java` source generally** (any token, even a
  string literal or comment) → `grep_java`, not built-in Grep: it ranks symbol matches first, then
  degrades to labelled text, so it's never worse than grep on coverage. Non-Java files
  (Gradle/JSON/MD) stay on built-in grep. If they're deferred, `ToolSearch` them first. This is
  jcma's own dogfood: use it on itself.
