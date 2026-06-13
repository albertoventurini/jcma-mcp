# jcma — Java Code-Map for Agents

An agent-native Java (JDK 25+) code-intelligence engine. Its only consumer is an **AI coding
agent** (Claude Code first), exposed over **MCP** — no LSP, no editor surface. It does fast,
low-memory, *semantic* navigation of a codebase and returns context-rich, token-bounded answers.

Built on **JavaParser + JavaSymbolSolver**, compiled to a **GraalVM native image**: a single,
lightweight binary that starts fast and serves over a memory-mapped index whose footprint scales
with what you query, not the size of the repo.

## MCP tools
- **`search_java_symbols`** — find a declaration by partial name
- **`find_java_definition`** — where a symbol is declared
- **`find_java_references`** — every confirmed use of a symbol (grouped, with an unconfirmed tail)
- **`find_java_supertypes`** / **`find_java_subtypes`** — walk the type hierarchy
- **`grep_java`** — search Java source: ranks semantic symbol matches first, then degrades to
  text (string literals, comments, Javadoc), so it is never worse than grep on coverage

## Build & run
```bash
./gradlew test                       # build + tests (JVM)
./build-native-image.sh              # → build/native/nativeCompile/jcma (needs GraalVM 25)

jcma index .                         # cold-index the current repo (index lives under ~/.cache/jcma)
jcma serve                           # run the MCP server (JSON-RPC over stdio)
jcma repl                            # warm interactive query loop
jcma refs <symbol>                   # one-shot CLI queries: refs / def / supertypes / search / outline …
```
Point an MCP client at the binary with `serve` (see [`.mcp.json`](.mcp.json) for the wiring).

## Status
The core is built and green through **M3**: M0 returned **GO** on the JavaParser → native-image
bet (all gates passed — see [`milestones/M0-RESULTS.md`](milestones/M0-RESULTS.md)); M1 built the
engine + index, M2 the MCP surface, M3 the `grep_java` text tier.

## Docs
- **[`ARCHITECTURE.md`](ARCHITECTURE.md)** — how it's built and why (start here for the design).
- **[`PRD.md`](PRD.md)** — the stable what & why; consult by section (§4 decisions, §5.1 index, §6 tools).
- **[`milestones/`](milestones/)** — executable plans and the measured results record.
- **[`CLAUDE.md`](CLAUDE.md)** — operating guidance for agents working in this repo.
