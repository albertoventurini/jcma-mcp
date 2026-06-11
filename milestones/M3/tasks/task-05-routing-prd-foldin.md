# M3 · Task 5 — Routing + PRD fold-in

> The parked enforcement layer, now safe to ship: a scoped `PreToolUse` redirect (`.java` greps →
> `grep_java`), a CLAUDE.md tweak, and folding the LOCKED M3 decisions into the PRD. Last in M3 —
> depends on tasks 02–04, because redirecting onto an incomplete `grep_java` would reintroduce holes.

## Prerequisites (read first, fresh session)
- **Done before this:** task-02/03/04 — `grep_java` is the full no-hole front door (both tiers,
  regex, graceful overflow).
- **Read:** `milestones/M3/M3-grep-java-degrade-to-text.md` ; `docs/grep-java-degrade-to-text.md`
  (Ordering + PRD integration) ; memory `mcp-tools-deferred-by-harness` + `beat-grep-by-subsuming-it`
  (why coverage precedes enforcement) ; the `update-config` skill (hooks live in `settings.json`,
  the harness executes them) ; current `CLAUDE.md` "Navigating Java" line ; PRD §6 + §5.1 + §11.

## [OPEN] — raise with the user *before* implementing
1. **Hook aggressiveness:** block `.java`-targeted greps with guidance, vs. non-blocking nudge.
   (Earlier discussion leaned "block symbol/`.java` greps, allow text/cross-language greps" — but
   with `grep_java` now covering text too, redirecting **all** `.java` greps is finally
   always-correct. Confirm block vs warn with the user; an over-eager block is the main risk.)
2. **Scope guard:** only `.java`-targeted searches; never non-Java (Gradle/JSON/MD stay built-in
   grep, per D5).

## Scope
- **Hook:** a `PreToolUse` hook on `Grep`/`Glob` (project `settings.json` via `update-config`) that,
  for `.java`-scoped searches, redirects to `grep_java` (block-with-guidance or nudge per [OPEN]-1).
  Must **not** fire on non-Java searches.
- **CLAUDE.md:** update the "Navigating Java" line to add `grep_java` as the grep-replacement front
  door (symbols-first, degrades to text), alongside the existing nav tools.
- **PRD fold-in (on acceptance):**
  - §6 (tools): add the `grep_java` row — `match`/`output`/`path`/`limit`/regex params, labelled
    tiers, not-exhaustive contract.
  - §5.1 (index): add the full-text segment (literals/comments/Javadoc), its format + **measured**
    footprint (from task-01/04).
  - §11 (undecided): remove the now-resolved items; leave any still-[OPEN] ones.

## Protocol
- Hook + CLAUDE.md are behaviour/config, not engine code — validate by **observation**: in a fresh
  session, confirm via the call-log (`~/.cache/jcma/logs/`) that `.java` searches route to
  `grep_java` and non-Java searches still use built-in grep. (No red-green unit gate, but do not
  declare done on assertion — show the routing in a real session.)
- PRD edits: keep `PRD.md` = stable what/why; this doc = how. Sync per the repo convention.

## Done when
- the scoped hook routes `.java` searches to `grep_java` (per the chosen aggressiveness) and leaves
  non-Java to built-in grep — **verified live in a session via the call-log**, not asserted ·
  CLAUDE.md names `grep_java` as the Java grep replacement · PRD §6/§5.1 updated, §11 pruned · the
  M3 design note and overview marked delivered.
