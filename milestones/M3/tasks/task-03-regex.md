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

## [OPEN] — raise with the user *before* implementing
1. **`query` default semantics:** regex-by-default (grep-like) with `fixed_string: true` to opt into
   literal, vs. literal-default with `regex: true`. (Doc leans regex-default.)
2. **Case sensitivity default:** grep is case-sensitive; IntelliJ tends smart-case. Pick the default
   + the `case_sensitive` override.
3. **Regex execution strategy:** how trigram pre-filtering composes with `java.util.regex`
   verification; anchoring (`^`/`$` per-line vs whole-text), multiline, and behaviour when the
   pattern has no extractable trigrams (fall back to a scan of the candidate set — bound it).

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
