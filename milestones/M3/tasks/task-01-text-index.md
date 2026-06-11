# M3 · Task 1 — Text index (string literals + comments + Javadoc)

> The foundation: index the D2 text corpus from `.java` sources into a full-text segment with a
> pure read API. **No MCP surface** — that's task-02. Everything else in M3 reads this.

## Prerequisites (read first, fresh session)
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` (overview + locked decisions) ;
  `docs/grep-java-degrade-to-text.md` (rationale) ; PRD §5.1 (index model) ; `jcma.index.*`
  (`TrigramIndex`, `SymbolStore`, the segment/LSM layout, the decl-trigram vs `usage-names.seg`
  split) ; the M1 indexing path (`Indexer` / `Reconciler`) and how segments are written/mmapped.
- **Decisions locked:** D2 (corpus = string literals + comments + Javadoc; `.java` only; text blocks
  fold into literals; identifiers **not** re-indexed — the symbol trigram tier serves those).

## [OPEN] — raise with the user *before* implementing
1. **Index structure + footprint.** Extend the existing trigram machinery with a new full-text
   segment over the literal/comment/Javadoc token stream, vs. a separate structure. The corpus is
   larger than declared-symbol names, so **measure first** (size on commons-lang + this repo) and
   match format to access pattern (substring/regex pre-filter), mirroring the decl-vs-usage split.
2. **Comments/Javadoc cost.** If they dominate the footprint, ship them behind a toggle, or
   literals-only v1. Decide from the measurement.
3. **Possible split — `task-01b` freshness.** If wiring the text segment into the M1 invalidation
   machinery (node-diff + reverse-edge walk; LSM base+overlay; validate-on-read) is large, carve it
   into its own session. Decide once the static index is built and its size is known.

## What to build
- A writer that walks `.java` sources and extracts the D2 corpus with position info: each indexed
  unit is `(source-kind ∈ {string-literal, comment, javadoc}, file, line, col, text)`. Use the
  existing M1 parse pass — do **not** pull `javac`/`com.sun.source` (native-image clean, PRD §4).
- A persisted segment (per the [OPEN]-1 decision) supporting fast **substring** lookup (the regex
  pre-filter lands in task-03), returning match sites as `file:line[:col]` + source-kind + the
  matching line snippet.
- A **pure read API** on the session/`QueryService` (no resolve, no deadline-sensitive work — same
  shape as the `search_java_symbols` passthrough), e.g. `searchText(query, limit?) → List<TextHit>`.
- Participate in freshness like other segments (or defer to task-01b per [OPEN]-3).

## Protocol (test-first — hard gate)
Write failing tests + a sizing measurement → **STOP for user review of the [OPEN] decisions and the
measured footprint** → implement → verify. Do not collapse into one turn.

## Tests (red-first)
- Extraction: on a fixture with a known string literal, a line comment, a block comment, a Javadoc,
  and a text block → each is indexed with the right `source-kind` + correct `file:line:col`; code
  identifiers and keywords are **not** in the text index.
- Read API: a substring present only inside a string literal is found and labelled `string-literal`;
  one only in a comment is labelled `comment`; a query matching nothing returns empty (not error).
- Determinism: segment bytes are stable across rebuilds (testable/round-trippable like other segs).
- Freshness (here or task-01b): editing a file updates/removes its text hits.

## Manual check
- Build the index on this repo; spot-check a known literal (e.g. a log message) and a Javadoc phrase
  resolve to the right sites. Record the segment size in the task's "As built" + the overview Targets.

## Done when
- tests green · native green · text corpus (D2) indexed from `.java` with positions · pure read API
  returns labelled, position-bearing hits · **footprint measured and within the §5.1 budget**, with
  the structure decision recorded · freshness handled (here or a filed task-01b).

## As built (2026-06-11)

**Structure decision — REVERSED [OPEN]-1, with the measurement as the new evidence.** The locked D1
"sibling trigram segment" was dropped in favour of an **inline-snapshot segment scanned linearly**.
The footprint measurement (below) showed a trigram index over this corpus is dominated by Javadoc and
blows the §5.1 budget; trigram only buys sub-linear query, which a ~3 MB corpus does not need (a scan
is single-digit ms). Inline storage costs ≈ the raw bytes, keeps the query **hermetic** (never reads
source files) with a snapshot-coherent failure mode, and a trigram pre-filter can layer on top of the
same corpus later (M4) if a large-repo measurement ever demands it. Decided with the user after
weighing inline-snapshot (chosen) vs. ranges-into-live-files (rejected: query depends on the
filesystem + needs per-read fingerprint validation to avoid fabricating hits).

**Toggle default ([OPEN]-2) = all three kinds.** Removing the trigram blow-up made Javadoc affordable
(it costs its raw bytes, not ~3×), so there is no hole and no need for a literals-only default. The
`includedKinds` toggle survives as the single write-point filter (`LsmStore.TEXT_KINDS`,
`TextIndex.write`) for future narrowing.

**Footprint measurement** (`TextIndexFootprintMeasurement`; trigram-proxy for the *old* design vs. the
inline `text.seg` actually shipped):

| corpus | string-literal | comment | javadoc | all three (inline) | (old trigram, rejected) |
|---|---|---|---|---|---|
| this repo (105 files) | 125 KB | 140 KB | 681 KB | **~230 KB** | 877 KB |
| commons-lang (527 files) | 1.43 MB | 1.78 MB | 6.92 MB | **~3.9 MB** | 9.95 MB |

Context: existing index totals — this repo ≈ 538 KB, commons-lang ≈ 4.1 MB. Inline `text.seg`
all-three ≈ the size of the existing whole index; the rejected trigram form was ~2.4× it.

**Freshness ([OPEN]-3) handled here — no task-01b.** Text units ride `compact()` (base unchanged ∪
overlay changed, **no re-parse**); `searchText` reads base ∪ overlay − tombstones; the overlay log
round-trips texts; `EdgeResolver.FileSlice` re-emits texts so Tier-2 re-applies don't drop them.

**Files:** `engine/{TextKind,TextUnit}`, `StructuralParser.textUnits()`, `index/TextIndex`,
`index/FileIndex` (+`texts`), `index/Indexer`, `index/LsmStore`, `resolve/EdgeResolver`,
`session/{TextHit,AnalysisSession}`, `query/QueryService`. Read API: `QueryService.searchText(query,
limit, deadline) → List<TextHit>`. **No MCP surface** (task-02).
