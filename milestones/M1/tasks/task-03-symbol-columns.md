# M1 · Task 03 — Index: symbol columns + moniker↔int32 + string arena (FFM)
> The columnar node store, read/written via FFM, productionizing `SpikeD.Base`.

## Prerequisites (read first, fresh session)
- **Done before this:** task-01 (build + native smoke), task-02 (engine — supplies symbol data shape).
- **Read:** PRD §5.1 (index store) ; M1 overview ; M0-RESULTS §"Incremental mmap format (Spike D)".
- **Port from M0 (reference, don't extend):**
  - `milestones/m0-spike/src/main/java/m0/SpikeD.java` — `Base.load/write` FFM columnar read/write.
  - `milestones/m0-spike/src/main/java/m0/SpikeC.java` — `capMmap()` as the native FFM round-trip smoke.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create/modify
- `src/main/java/jcma/index/SymbolStore.java` — columns: `kind, flags, enclosing, fileId, range,
  nameRef, sigRef, monikerRef`. FFM read/write via `Arena.ofShared()` + `FileChannel.map`.
- `src/main/java/jcma/index/StringArena.java` — dedup UTF-8 string table.
- `src/main/java/jcma/index/Moniker.java` — **define the SCIP-style moniker scheme** (closes a
  PRD §11 sub-decision; concrete grammar, e.g. `pkg/Type#method(sig).`); intern moniker ↔ int32.
- `src/main/java/jcma/cli/` — `index-dump --symbols` subcommand.

## Tests (red-first)
- Unit: round-trip N symbols incl. nested/enclosing chain; moniker stability across rewrite;
  phantom (`fileId = -1`) symbols preserved; bad-magic rejected.
- Native smoke: `capMmap`-style FFM round-trip under native-image.

## Manual CLI check
- `jcma index-dump --symbols <indexDir>` → lists symbols + monikers.

## Done when
- tests green · native FFM round-trip green · moniker grammar chosen + recorded (PRD §11).
</content>
