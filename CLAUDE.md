# Working in this repo (guidance for Claude Code)

**jcma** — an agent-native Java (JDK 25+) code-intelligence engine. Sole consumer is an AI
coding agent, exposed over **MCP only** (no LSP); built for low memory + instant start +
semantic navigation. The core is **built and green through M3** — there is real source under
`src/main/java/jcma/`.

Start from [`ARCHITECTURE.md`](ARCHITECTURE.md) for how the pieces fit. `PRD.md` is the stable
what/why and source of truth, but it's large — **consult it by section, on demand** (index →
§5.1; engine/fallback → §4; tools → §6), not wholesale. `milestones/*` hold the executable plans
and the measured results (`M0-RESULTS.md`, `M1-RESULTS.md`).

## Settled decisions — do not relitigate
Decided deliberately; reopen only with new evidence, and if you do, update `PRD.md`.
- **MCP only.** No LSP / `textDocument/*` / editor surface.
- **Engine = JavaParser + JavaSymbolSolver** (M0 returned GO; the javac-hybrid fallback proved
  unnecessary). Behind an `AnalysisEngine` interface; the javac path stays documented but unbuilt.
- **Distribution = GraalVM native-image** (single binary). Reachable *only* because the core
  avoids `javac` — don't pull `javac`/`com.sun.source` into the native path.
- **Index = custom memory-mapped store** (PRD §5.1): graph model, both edge directions,
  lazy-resolve-and-cache, LSM base+overlay, filesystem-driven freshness, validate-on-read.
  Not SQLite, not a graph-DB engine.
- **No compiler-grade diagnostics in core** (agents get correctness by running the build).

## Conventions
- **`PRD.md` = stable what/why; `milestones/*` = executable how; `ARCHITECTURE.md` = how the code
  fits.** Keep them in sync when a decision changes; PRD §11 tracks what's still undecided.
- **Calibrate gates from measured actuals + safe-vs-silent failure behaviour**, never round
  numbers on faith (the M0/M1 results convention). Safe-degrading misses (resolve *throws* →
  tagged "unconfirmed") are tolerable; silent-wrong answers are the harmful mode.
- **M0 spike code is kept, not deleted** — a valued reference under `milestones/m0-spike/`.
- Running autonomously: follow each milestone's **Execution notes** — proceed on proposed
  defaults, record the choices made, block on a human only when genuinely stuck.
- **Navigating Java in this repo** ("where is X declared / who uses X / find a type by partial
  name") → call jcma's MCP tools (`search_java_symbols`, `find_java_definition`,
  `find_java_references`), not Grep/Read. To **search `.java` source generally** (any token, even a
  string literal or comment) → `grep_java`, not built-in Grep: it ranks symbol matches first, then
  degrades to labelled text, so it's never worse than grep on coverage. Non-Java files
  (Gradle/JSON/MD) stay on built-in grep. This is jcma's own dogfood: use it on itself.
