# M1 · Task 05 — Index: trigram name index
> Name search + candidate-file pruning (the input to lazy-resolve).

## Prerequisites (read first, fresh session)
- **Done before this:** task-03 (SymbolStore supplies names/symbolIds), task-04 (occurrences).
- **Read:** PRD §5.1 (trigram / name search) ; M1 overview.
- **Port from M0 (reference, don't extend):** none directly; the FFM read/write idioms from
  `SpikeD.Base` apply if postings are mmap'd.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/index/TrigramIndex.java` — postings name-trigram → fileId/symbolId set;
  substring query + ranking. **In-task decision (PRD §11): heap-resident vs mmap'd postings —
  recommend mmap'd** (honour "RSS scales with what you touch"); record the choice.
- `src/main/java/jcma/cli/` — `search <query>` subcommand.

## Tests (red-first)
- Unit: index a fixture, query by substring, assert candidate set; assert pruning returns a
  *small fraction* of files for a rare name and the right set for a common one.

## Manual CLI check
- `jcma search <query>` → ranked symbols (FQN, kind, file:line, signature).

## Done when
- tests green · native green · heap-vs-mmap choice recorded (PRD §11).
</content>
