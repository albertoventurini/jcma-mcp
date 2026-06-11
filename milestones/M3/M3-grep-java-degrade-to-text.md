# M3 — `grep_java`: degrade-to-text search (overview)

> **Type:** deliverable (production code). **Predecessor:** M2 (agent-native MCP surface — server,
> §6 tools, response budget). **Parent:** `PRD.md` §6 (tool surface) · §5.1 (index) · §2
> (agent-native principles) · §8 (latency/token targets). **Design rationale:**
> `docs/grep-java-degrade-to-text.md` (the *why*; read it first). **This doc is the *how*.**
>
> Numbering note: M3 was previously a loose forward-reference for "large-repo perf / background
> indexing" — that charter **moved to M4** (it is *"deferred by measurement, not assumed"*). M3 is
> now this milestone, the evidence-backed next step.

## Why this milestone

The agent reflexively reaches for built-in `grep` over jcma's symbol tools because **grep has no
"wasn't-a-symbol" hole** — total text coverage — and the agent can't predict in advance whether a
query is symbol-shaped, so "try jcma → miss → grep anyway" costs more than "just grep first."
Measured in-session 2026-06-11 (call-log: zero navigation calls during real feature work; the agent
grepped to *locate* code from fuzzy signals). The deciding variable is **query-shape + coverage, not
protocol** — un-deferral (M2's `_meta` always-load) made the tools *visible* but did not change the
*choice*. Description tuning and an enforcement hook both fight the reflex; coverage removes its
root cause.

M3 builds the tool that **subsumes grep for Java**: `grep_java` — the agent's own verb — returning
semantic **symbol** matches first and degrading to labelled **text** (string-literal / comment /
Javadoc) matches. By defaulting to *both* tiers it has no hole, making it a strict superset of
built-in grep *and* `search_java_symbols`, scoped to `.java` sources. IntelliJ shift+shift, wearing
the name the agent reaches for. **Differentiator over grep:** on broad queries grep floods then
truncates *arbitrarily*; `grep_java` **ranks before truncating**, so the useful hit survives.

**Coverage before enforcement.** The parked `PreToolUse` redirect (M2 thread) stays parked until
this ships — redirecting onto a symbol-only tool would force the agent onto a holey tool. Once the
hole is closed, a scoped redirect becomes always-correct (M3 task-05).

## Decisions locked with the user (2026-06-11 — do not relitigate)

- **D1 — Keep both tools.** Add `grep_java` (no-hole front door); keep `search_java_symbols` (the
  precise symbol-only path, ≡ `grep_java(match=symbols)`). Reassess deprecation *later*, on evidence.
- **D2 — Text corpus = string literals + comments + Javadoc**, `.java` only. Text blocks fold into
  literals. Identifier-substring is already served by the symbol trigram tier — the text tier does
  not re-index identifiers.
- **D3a — Large results: content-with-overflow-fallback.** Ranked content within budget;
  auto-collapse to per-file aggregation past a (calibrated) threshold.
- **D3b — grep-style output modes.** Optional `output: content | files | count` (default `content`).
- **D3c — Narrow affordance.** `path` glob scope + `limit`.
- **D3d — Honest truncation.** Rank-before-truncate + explicit *not-exhaustive, N total* marker
  (reuse M2 task-03 budget + the `find_references` "never exhaustive" pattern).
- **D4 — Regex from the start** (`query` is a regex by default; not a fast-follow).
- **D5 — Java sources only**, stated in the tool description.

## Targets — calibrate, don't invent (PRD §8 / M1-RESULTS)

- Symbol-tier latency: **no regression vs `search_java_symbols` today** (p95 < 50 ms).
- Text-tier latency + the content→aggregation collapse threshold + budget caps: **derive from
  measured match-count distributions** on a real corpus (commons-lang / this repo) and from
  safe-vs-silent failure behaviour — not round numbers (memory `calibrate-targets-from-failure-modes`).
- Index footprint: within the §5.1 low-memory budget; **measured**, mirroring the decl-vs-usage
  trigram split discipline (memory `graph-native-index-design`).

## Tasks (separate files, one per fresh session)

```
task-01 ──► task-02 ──┬─► task-03
                      ├─► task-04
                      └─► (03+04 done) ──► task-05
```

- **task-01 — Text index** (`tasks/task-01-text-index.md`): index literals/comments/Javadoc → a
  full-text segment + pure read API. *Foundation; no MCP surface.* Opens with the **[OPEN] index
  structure + footprint** decision. May split a **task-01b — text-index freshness** if the
  invalidation hookup is large.
- **task-02 — `grep_java` MVP** (`tasks/task-02-grep-java-mvp.md`): the tool, both tiers,
  literal/substring, symbols-first + labelled text, simple honest cap. *First reflex-flipping win.*
- **task-03 — Regex** (`tasks/task-03-regex.md`): regex query semantics (trigram pre-filter →
  `java.util.regex` verify), `fixed_string` / `case_sensitive`. Depends on task-01.
- **task-04 — Large-result UX** (`tasks/task-04-large-result-ux.md`): overflow→aggregation,
  `output` modes, `path`, `limit`, calibrated thresholds. Depends on task-02.
- **task-05 — Routing + PRD fold-in** (`tasks/task-05-routing-prd-foldin.md`): scoped `PreToolUse`
  hook (now safe), CLAUDE.md tweak, fold LOCKED decisions into PRD §6/§5.1, prune §11. Depends on
  02–04.

task-03 and task-04 are **parallelizable** after task-02.

## Still open — decide WITH the user (do not resolve solo)

Carried from the design note; each task flags its own. Bring back to the user, per their standing
request to be in the loop on every important decision:
1. Regex-default vs literal-default for `query`; `case_sensitive` default (smart-case?).
2. Index structure + footprint (PRD §5.1); whether comments/Javadoc ship on or behind a toggle.
3. Text-tier ranking function; overflow threshold + budget caps (calibrate from actuals).
4. Regex execution strategy over the trigram pre-filter (anchoring, multiline).
5. Timing of any future `search_java_symbols` deprecation.

## Execution notes

- **Red-pause-green is a hard gate** (memory `red-pause-green-gate`): failing tests + transcripts →
  **STOP for user review** → implement → verify. Never collapse into one turn.
- Each task file is self-contained and fresh-session-readable; start from its *Prerequisites*.
- Provisional until PRD fold-in (task-05): LOCKED items are firm; **[OPEN]** items are not yet PRD
  decisions and must be raised with the user before being implemented.
