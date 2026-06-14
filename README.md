# jcma — Java Code-Map for Agents

jcma is a Java (JDK 25+) code-intelligence engine built primarily for AI
coding agents.

It exposes semantic navigation of a Java codebase as a set of MCP tools.
The main design principles are:

- fast to start: GraalVM native image, no JVM warm-up
- fast to answer: index read straight from mmap via FFM, no deserialization; lazy resolve-and-cache; incremental LSM updates
- gentle on memory: aims to beat the standard Java LSP server, `jdt-ls`
- precise: full type resolution, no heuristics à la Tree-sitter
- token-conservative

Under the hood it's **JavaParser + JavaSymbolSolver**, compiled to a **GraalVM native
image** — one lightweight binary that starts instantly and serves from a memory-mapped index
whose footprint scales with what the agent asks, not with how big the repo is.

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

## Install as a Claude Code plugin
The plugin contributes only the MCP-server wiring — you supply the binary (LSP-server model;
auto-download is not yet available).

1. **Get the binary.** Download the `jcma` native image for your platform and put it on `PATH`
   (or set `JCMA_BINARY=/full/path/to/jcma`). To build it yourself: `./build-native-image.sh`.
2. **Index once per repo:** `jcma index .` (`serve` needs an existing index).
3. **Install in Claude Code:**
   ```
   /plugin marketplace add albertoventurini/jcma
   /plugin install jcma@jcma
   ```

## Status
The core is built and green through **M3**: M0 returned **GO** on the JavaParser → native-image
bet (all gates passed — see [`milestones/M0-RESULTS.md`](milestones/M0-RESULTS.md)); M1 built the
engine + index, M2 the MCP surface, M3 the `grep_java` text tier.

## Docs
- **[`ARCHITECTURE.md`](ARCHITECTURE.md)** — how it's built and why (start here for the design).
- **[`PRD.md`](PRD.md)** — the stable what & why; consult by section (§4 decisions, §5.1 index, §6 tools).
- **[`milestones/`](milestones/)** — executable plans and the measured results record.
- **[`CLAUDE.md`](CLAUDE.md)** — operating guidance for agents working in this repo.
