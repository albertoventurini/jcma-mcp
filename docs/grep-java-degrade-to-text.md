# `grep_java` — degrade-to-text search (design note + task seed)

> Beat built-in grep by *subsuming* it for Java: one tool the agent already has a verb for
> (`grep`), returning semantic **symbol** matches first and degrading to labelled **text**
> (string-literal / comment / Javadoc) matches — never a "wasn't-a-symbol" hole.
>
> **Status: proposal, not yet a numbered milestone task.** Decisions below marked **[LOCKED]**
> were taken with the user on 2026-06-11; **[OPEN]** items must be brought back to the user in the
> fresh session — do not decide them solo. On acceptance, fold the LOCKED parts into PRD §6 (tools)
> and §5.1 (index). **Promoted to milestone M3** — see `milestones/M3/M3-grep-java-degrade-to-text.md`
> and its `tasks/`; this note remains the design rationale the tasks link back to.

## Why this exists

The agent reflexively reaches for built-in `grep` over jcma's symbol tools, and the reflex is
*rational*: grep has **total text coverage and no hole** — it never returns "that's not a symbol I
handle." A symbol-only tool (`search_java_symbols`, or any LSP `workspaceSymbol`) does have that
hole, and the agent can't predict in advance whether a given query is "symbol-shaped," so
"try jcma → miss → grep anyway" costs more than "just grep first." Demonstrated in-session
2026-06-11: even with tools un-deferred and a CLAUDE.md instruction, the agent grepped string
literals + protocol strings + identifiers to *locate* a registration site (a concept, not a
symbol). The deciding variable is **query-shape + coverage, not protocol** — so the fix is coverage,
not another protocol and not (yet) an enforcement hook.

The bet: a tool named **`grep_java`** captures the agent's own verb for "search this codebase,"
and by defaulting to **both** tiers it has no hole — making it a strict superset of built-in grep
*and* `search_java_symbols`, scoped to Java sources. This is IntelliJ's shift+shift ("Search
Everywhere": symbols ranked first, text as a graceful, labelled fallback) wearing the name the agent
reaches for. See memory `beat-grep-by-subsuming-it`.

**Differentiator over grep:** on a broad query grep floods then truncates *arbitrarily* (filesystem
order, no ranking), so the useful hit can be buried or cut. `grep_java` **ranks before truncating**
(symbols first, then text by relevance), so what survives the budget is the useful part. That is the
core reason this tool earns its place rather than just reproducing grep.

## Decisions locked with the user (2026-06-11)

- **[LOCKED] D1 — Keep both tools.** Add `grep_java` as the no-hole front door; keep
  `search_java_symbols` as the precise symbol-only path (it is `grep_java(match=symbols)`, already
  wired into CLI/REPL via `QueryService.searchSymbols`). Reassess deprecating `search_java_symbols`
  *later*, only once we observe whether `grep_java` actually wins the reflex.
- **[LOCKED] D2 — Text corpus = string literals + comments + Javadoc.** (Text blocks fold into
  string literals; char literals trivial.) Substring-inside-an-identifier (grep's "Resolver" in
  "EdgeResolver") is already served by the symbol trigram tier, so the text tier does **not**
  re-index identifiers.
- **[LOCKED] D3a — Large results: content-with-overflow-fallback.** Default to ranked content within
  budget; auto-collapse to a per-file aggregation view when matches exceed the threshold. (Mirrors
  IntelliJ "N usages in M files"; per-file aggregation is also native to grep — `-c`/`-l` — and the
  default of the built-in `Grep` tool, so the agent is already fluent in it.)
- **[LOCKED] D3b — Expose grep-style output modes.** Optional `output: content | files | count`
  (default `content`), mirroring grep `-c`/`-l`, so the agent can deliberately start aggregated.
- **[LOCKED] D3c — Narrow affordance.** A `path` glob scope param (restrict to a subtree) + a
  `limit`, so the overflow "narrow your query" hint is actionable in one follow-up call.
- **[LOCKED] D3d — Honest truncation.** Rank-before-truncate + an explicit *not-exhaustive, N total*
  marker. Reuse M2 task-03's response budget and the `find_references` "never present a set as
  exhaustive" pattern.
- **[LOCKED] D4 — Regex from the start.** `query` is a regular expression by default (grep-like),
  not a fast-follow. A literal/substring-only v1 would reopen the hole for every regex search and
  keep the agent on built-in grep.
- **[LOCKED] D5 — Java sources only.** `grep_java` searches `.java` sources; Gradle/JSON/Markdown
  stay built-in grep's job. The tool **description must say so**, or the agent will wrongly expect
  whole-repo coverage.

## Tool contract — `grep_java`

**Input** (JSON schema in `ToolRegistry`):
- `query` *(string, required)* — a regular expression by default; `fixed_string: true` to match
  literally (grep `-F`). **[OPEN]** regex default vs literal default, and case-sensitivity default
  (grep is case-sensitive; IntelliJ tends smart-case) — confirm with user.
- `match` *(enum `symbols | text | both`, default `both`)* — `symbols` ≡ `search_java_symbols`;
  `both` is the no-hole default.
- `output` *(enum `content | files | count`, default `content`)* — D3b.
- `path` *(string glob, optional)* — restrict to a subtree (D3c).
- `limit` *(int, optional)* — caller-expandable cap (D3c).
- `case_sensitive` *(bool, optional)* — **[OPEN]** default.
- `kind` *(symbol-kind filter, optional)* — reuse the existing `search_java_symbols` filter for the
  symbol tier.

**Output** — ranked, every entry carries a `kind` label so symbol vs string is never ambiguous:
- `symbol` — FQN, symbol-kind, `file:line`, signature (the existing `search_java_symbols` shape).
- `string-literal` / `comment` / `javadoc` — `file:line[:col]`, the matching line snippet
  (grep-style `file:line: <line>`), and which text-source matched.
- Symbols are ordered first (via existing `SymbolRanking`); text follows, ranked
  exact-token > substring, then fewer-files-first. **[OPEN]** exact ranking function for the text
  tier.

## Large-result behaviour (D3 in detail)

1. **Ranked content by default**, filling the task-03 token budget symbols-first.
2. **On overflow** (matches over threshold): collapse to the **aggregation view** — total match
   count, per-file counts, the file list, a few top-ranked snippets — plus an actionable hint to
   narrow via `path` or a tighter `query`. This is also reachable on demand via `output: files` /
   `count`.
3. **Always** emit the *not-exhaustive, N total* marker when truncated; never imply the set is
   complete.
4. **[OPEN] Threshold + budget values must be calibrated from measured actuals**, not round numbers
   (see memory `calibrate-targets-from-failure-modes`): measure match-count distributions on a real
   corpus (commons-lang / this repo), set the collapse threshold and caps from the curve + the
   token budget, and from safe-vs-silent failure behaviour.

## What gets indexed, and the cost constraint

- Corpus (D2): string literals + comments + Javadoc, from `.java` sources only (D5).
- **Index design is [OPEN] and load-bearing — low memory is a core value (PRD §5.1).** Options:
  extend the existing trigram machinery (cf. `TrigramIndex`, the decl-trigram / `usage-names.seg`
  split — see memory `graph-native-index-design`) with a new full-text segment over the literal/
  comment/Javadoc token stream, vs. a separate structure. **Measure the footprint** (the corpus is
  larger than declared-symbol names) and mirror the decl-vs-usage split discipline — match the
  format to the access pattern. Bring the size/structure tradeoff (and whether comments+Javadoc
  earn their cost, or ship behind a toggle) back to the user.
- **Regex over the index (D4):** trigram pre-filter → verify with `java.util.regex` (native-image
  safe), ripgrep-style. **[OPEN]** exact approach, anchoring, multiline.

## Still open — decide WITH the user in the fresh session

Do **not** resolve these solo (user wants in on every important call):
1. Regex-default vs literal-default for `query`; `case_sensitive` default (smart-case?).
2. Index structure + memory footprint (PRD §5.1); whether comments/Javadoc ship on or behind a toggle.
3. Text-tier ranking function; overflow threshold + budget caps (calibrate from actuals).
4. Regex execution strategy over the trigram pre-filter.
5. Timing of any future `search_java_symbols` deprecation (D1 says keep both for now).

## Where it plugs into existing code

- New `src/main/java/jcma/tools/GrepJavaTool.java`; register in `jcma.mcp.ToolRegistry` with its
  input schema (it inherits the `_meta` always-load exemption automatically).
- Symbol tier reuses `QueryService.searchSymbols(query, kind?, limit?)` →
  `TrigramIndex`/`SymbolStore`.
- Text tier needs a new pure session/`QueryService` read over the new text index (no resolve, no
  deadline-sensitive work — same shape as the `search_java_symbols` passthrough).
- Output shaping + budget reuse M2 task-03 (`ToolResult`); aggregation/overflow extends it.
- Validation uses the existing call-log (`~/.cache/jcma/logs/`) — see memory
  `observability-throughout`.

## Ordering & the parked enforcement hook

Coverage **before** enforcement. A `PreToolUse` hook redirecting `Grep` → jcma is premature while
jcma is symbol-only — it would force the agent onto a holey tool. Once `grep_java` closes the hole,
a *scoped* redirect (`.java`-targeted greps) becomes always-correct, or the CLAUDE.md line alone may
suffice. The hook stays parked until this ships.

## PRD integration (on acceptance)

- §6 (tools): add the `grep_java` row (input/output, the `match`/`output` params, the labelled
  tiers, the not-exhaustive contract).
- §5.1 (index): add the full-text segment (literals/comments/Javadoc), its format, and its measured
  footprint.
- §11 (undecided): move the resolved items out; leave the **[OPEN]** ones until the user decides.

## Build protocol & done-when

- **Red-pause-green is a hard gate** (memory `red-pause-green-gate`): write failing tests +
  transcripts → **STOP for user review** → implement → verify. Do not collapse into one turn.
- **Done when:** tests green · native green · `grep_java` answers over MCP with labelled
  symbols-first + degraded text tiers, content-with-overflow-fallback, honest truncation, regex,
  `path`/`limit`/`output` params · footprint measured and within the §5.1 budget · symbol-tier perf
  no worse than `search_java_symbols` today (p95 < 50 ms) · and the real signal: the call-log shows
  the agent reaching for `grep_java` on searches where it previously defaulted to built-in grep.
