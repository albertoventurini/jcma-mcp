# M1 · Task 04 — Index: CSR fwd/rev adjacency + occurrences + edge-type enum
> The typed bidirectional graph + occurrence records in mmap.

## Prerequisites (read first, fresh session)
- **Done before this:** task-03 (SymbolStore + StringArena + Moniker supply symbol ids).
- **Read:** PRD §5.1 (graph model, both edge directions) ; M1 overview.
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeD.java` — CSR accessors (`offset[]` + flat
    `targets[]`, fwd+rev); `Oracle` as a reusable in-memory test double.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/index/Csr.java` — fwd + rev: `offset[]` + flat `targets[]`.
- `src/main/java/jcma/index/EdgeType.java` — enum `CONTAINS, REFERENCES, CALLS, EXTENDS,
  IMPLEMENTS, OVERRIDES, HAS_TYPE, INSTANTIATES, ANNOTATED_BY, THROWS, IMPORTS`.
- `src/main/java/jcma/index/Occurrence.java` — `fileId, range, enclosingSymbolId, role`
  (role ∈ {read, write, call, typeref}).
- `src/main/java/jcma/cli/` — `index-dump --edges <symbol>` subcommand.

## Tests (red-first)
- Unit: insert edges; assert `fwd(x)` and `rev(x)` both match an in-memory oracle (port
  `SpikeD.Oracle` as a test double); occurrence carries enclosing symbol + range.

## Manual CLI check
- `jcma index-dump --edges <symbol>` → prints fwd/rev neighbours with edge types.

## Done when
- tests green · native green · fwd/rev round-trip matches oracle.
</content>
