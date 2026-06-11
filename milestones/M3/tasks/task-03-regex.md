# M3 · Task 3 — Regex (`grep_java`)

> Close the regex hole (D4). `query` becomes a regular expression, so the agent never falls back to
> built-in `Grep` for regex searches. Trigram pre-filter → `java.util.regex` verify (native-image
> safe). Parallelizable with task-04 after task-02.

## Prerequisites (read first, fresh session)
- **Done before this:** task-01 (text index), task-02 (`grep_java` MVP).
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` ; `docs/grep-java-degrade-to-text.md`
  (D4 + the regex [OPEN] items) ; task-01's index/read API ; how the trigram index pre-filters
  candidates today.
- **Decision locked:** D4 — regex from the start, not literal-only.

## [RESOLVED] decisions (2026-06-11, with the user) — fold into PRD §6 at task-05
- **D-a — `query` is regex by default; `fixed_string: true` opts into literal** (grep `-F`). Lower
  friction for the agent (its grep reflex is regex-by-default).
- **D-b — regex applies to BOTH tiers, uniform semantics** (never "regex on a subset"). A
  metachar-free, case-sensitive pattern keeps today's trigram/`indexOf` fast path as an invisible
  optimization (identical result set); the regex path (`Matcher.find`, substring-style) is taken only
  when the pattern carries a metacharacter or is case-insensitive.
- **D-c — case-sensitive by default; `case_sensitive: false`** opts into `CASE_INSENSITIVE` (symbols
  then go verify-all, since the trigram index is case-sensitive).
- **D-d — `MULTILINE` on, `DOTALL` off.** `^`/`$` anchor per physical line within a text unit; `.`
  does not cross `\n`. **Caveat (documented):** comments/Javadoc are indexed via JavaParser
  `getContent()`, which keeps the internal `\n` + ` * ` gutter, so `^Computes` won't match a Javadoc
  line stored as ` * Computes`. String literals + line comments anchor cleanly. Accepted rather than
  reopen task-01's frozen stored format.
- **Implementation seam:** a single `jcma.index.SearchSpec` (lowest package) carries the match policy
  and is threaded through `TextIndex`/`TrigramIndex`/`LsmStore` → `AnalysisSession` → `QueryService` →
  `GrepJavaTool`; `String` overloads delegate to a literal spec so `search_java_symbols`/CLI/REPL are
  untouched.
- **Reconciliation with task-01:** the text tier is a linear inline-scan (no trigram index), so the
  "no-trigram bounded fallback" is the *normal* text path — regex is just `Matcher.find()` inside the
  existing deadline-bounded scan. A literal-seed → trigram pre-filter can layer on in M4 if a
  large-repo measurement ever demands it.

## What to build
- Extend `GrepJavaTool` input: `query` interpreted per [OPEN]-1; add `fixed_string` and
  `case_sensitive` (per [OPEN]-1/2).
- Regex path over the **text tier**: extract required trigrams from the pattern → candidate sites
  from the index → verify with `java.util.regex`. No-trigram patterns → bounded candidate scan.
- Keep the symbol tier behaviour from task-02 (regex applies to the text tier; symbol-name matching
  stays as-is unless the user decides otherwise — raise if ambiguous).
- Native-image: `java.util.regex` only; no new reflective deps.

## Protocol (test-first — hard gate)
Failing tests + transcripts → **STOP for user review of the [OPEN] decisions** → implement → verify.

## Tests (red-first)
- A regex (`foo.*bar`, char classes, alternation) matches the right literal/comment sites and
  excludes non-matches; equivalent `fixed_string` query matches literally (special chars not
  interpreted).
- Case sensitivity honors the chosen default + the override.
- A pattern with no usable trigrams still returns correct results (candidate scan path), bounded.
- Pathological/broad regex stays within the task-03 budget (marked not-exhaustive) — no flood.

## Manual check
- `grep_java {"query":"log\\.(debug|trace)\\("}` and a `fixed_string` counterpart on this repo →
  eyeball correctness + boundedness.

## Done when
- tests green · native green · `grep_java` handles regex (+ `fixed_string`, `case_sensitive`) over
  the text tier, trigram-accelerated with a bounded no-trigram fallback · the [OPEN] defaults
  recorded · the regex hole is closed (no reason left to use built-in `Grep` on `.java`).
