# `skim_java` — a token-economical whole-file view (design note + task seed)

> The agent keeps reaching for built-in `Read` to skim a `.java` file it only wants to *understand
> the shape of* — pulling 600 lines to use 30. Give it a cheaper front door: the file rendered as
> **real Java with method bodies elided**, trivially-short bodies kept inline, docs preserved, and a
> demoted line-number gutter for coordinates + size-at-a-glance. A companion `read_symbol(FQN)` tool
> drills into any one declaration's full source; the FQNs that address it are derivable from the view
> itself, so the skim weaves into the other symbol-addressed tools with no resolve roundtrip.
>
> **Status: `skim_java` BUILT & green (M3, 2026-06-18).** All the [OPEN]s below were resolved with the
> user on 2026-06-18 and the tool shipped; the LOCKED behavior is folded into PRD §6. The companion
> `read_symbol` drill-down and the `tree` view remain **deferred** (see "Open questions", now resolved).
> Implementation: a fourth `StructuralParser.Parsed.skim()` projection (verbatim source spans, AST-free),
> a `jcma.render.SkimRenderer` (gutter + char gate + `{ … }` elision), and `jcma.tools.SkimJavaTool`
> wired in `Serve`; plus a `jcma skim <file>` CLI twin for calibration. So it was mostly *exposure +
> rendering* over the existing parse, not new analysis, as predicted.

## Why this exists

Observed in-session: even with jcma's MCP navigation tools available, the agent still `Read`s whole
Java files while exploring — because it wants the *logic*, and `find_definition` / `grep_java` answer
"where / who," not "what's in this file." Reading the whole file to learn its shape is the reflex,
and for a large file most of those tokens are spent on bodies the agent didn't need.

The bet mirrors `grep_java` (see `grep-java-degrade-to-text.md`): name the tool after the verb the
agent already reaches for — here, **read/skim** — and make it a strictly cheaper substitute *for the
triage step*. It does **not** replace reading. It replaces the *first, exploratory* read with a
skeleton; the agent then drills into only the declarations that matter via `read_symbol(FQN)` (or a
ranged `Read` for non-symbol regions). The workflow is **skim to triage → `read_symbol` to drill**,
not "skeleton instead of source."

Crucial scope limit, stated up front: **the logic lives in the bodies.** A skim tells you a file's
*shape*, never its *behavior*. It is for orientation, navigation, and mapping across many files —
not for understanding or changing what code does. Used past that line it produces confident-but-
shape-only answers, which is the silent-wrong failure mode this repo treats as the harmful one.
The tool's honesty is in showing exactly what it omits (an elided body is visibly `{ … }`, with the
line-gap showing how much is hidden), so the agent always knows when it's looking at shape vs source.

## What it returns

One **compilation unit → real Java, bodies elided**, e.g.:

```
 1  package shop;
 3  /** An immutable snapshot of a customer's cart.
 4    * Also persists items on database when they are inserted. */
 5  public final class Cart {
 6      private List<Item> items;
 7      public void addItem(Item i) { items.add(i); }
 8      public BigDecimal total() { … }
13  }
```

- **Real Java, not a DSL.** The agent is fluent in Java; "the file with bodies collapsed" is what an
  IDE's collapse-all gives a human and needs zero mental translation. A pseudo-tree (`method
  total(): BigDecimal`) was considered and rejected — it reads worse and buys nothing. *[LOCKED]*
- **Source-true line-number gutter — demoted, not the drill mechanism.** `read_symbol(FQN)` is now
  the primary drill-down (see the weave section), so line numbers no longer exist to feed a ranged
  `Read`. They're kept for two residual jobs: *size-at-a-glance* (`total()` jumping 8→13 means lines
  9–12 are the elided body, so the gap shows how much each `{ … }` hides) and *coordinate coherence*
  with the line-reporting tools (`grep_java`, `find_references`, and a plain `Read` of non-symbol
  regions like the import block). Demoted, not deleted. *[LOCKED]*
- **No FQN side-table in `source`.** The FQNs are derivable from the view itself (see the weave
  section); printing them would duplicate the screen. *[LOCKED]*

## The three dials (one tool, one extraction)

This is **one MCP tool** with optional parameters over a single parse — not several tools. *[LOCKED]*

### 1. `view` — `source` (default) | `tree`
`source` is the real-Java rendering above. `tree` is a structured, machine-readable declaration tree
(kinds + names + ranges) and earns its place *only* if a program consumes the structure as data; for
the agent, `source` dominates. **[OPEN]** whether `tree` ships at all or waits for a consumer.

### 2. `inlineBodyMaxChars` — inline trivially-short bodies
A body is kept inline when it's small; otherwise it collapses to `{ … }`. The measure is **the
normalized body rendered on a single line, by character count** — *not* line count and *not*
statement count, both of which are accidental:
- line count is formatting-dependent (the one trap that already sank "one physical line");
- statement count misses the long single statement (`return items.stream().map(…).filter(…)…
  collect(…)` is one statement but expensive).

So: collapse the body's whitespace/newlines to single spaces, count characters, inline under the
budget. Default ~50–60 chars (**[OPEN]** exact default; **[OPEN]** whether the budget covers the
whole rendered `signature + { body }` line — the unit actually read — rather than body-only). The
char gate is the approach; the number is tunable. *[LOCKED: approach]*

**Bias to show on ties.** Showing a body is never a lie (verbatim truth, at worst a few wasted
tokens); eliding a body the agent needed is the only move that can mislead. So borderline → inline.

### 3. `includeDocs` — default `true`
Docs (class- and method-level Javadoc) render **verbatim**, size-independent, by default. Set
`false` to drop them entirely — a clean drop, not truncation or a marker. *[LOCKED]*

## How it weaves into jcma's other tools (`read_symbol`)

As described so far the skim is a *leaf*: it shows a file but hands the agent nothing the other
symbol-addressed tools (`find_references`, `find_definition`, `find_subtypes`) can consume, so every
follow-up would need a separate resolve. Two decisions close that gap.

### `read_symbol(FQN)` — the symbol-addressed drill-down *[LOCKED]*
A companion tool: given a declaration's FQN, return its full source. **One** tool for both types and
methods — a type and a method are both just symbols; `read_type` + `read_method` were considered and
collapsed into one (two tools duplicate machinery and spend two slots in a deliberately frugal tool
budget). It earns its place over a plain ranged `Read`, not as "another way to slice":

- **It coheres with jcma's model.** Every other jcma tool is *symbol-addressed*; `Read` is *line-
  addressed*, a different mental model. "Given a symbol, return its source" is the natural sibling of
  "given a symbol, return its references."
- **It returns the complete semantic unit** — doc + signature + body, one guaranteed-whole chunk:
  exactly the doc/body verify-pair. A ranged `Read` at a guessed offset can clip the leading Javadoc
  or stop short of the closing brace; `read_symbol` can't. So it's *more correct* at re-uniting a doc
  with its evidence (the point of the char-gate section below).
- **Stable addressing.** An FQN re-resolves after the file is edited; cached line numbers go stale.

Costs, honestly: +1 tool; a parse-per-call to locate the symbol (a ranged `Read` is a cheaper raw
slice); and it leans on the selector handling overloads/constructors (see open questions). Plain
ranged `Read` stays the fallback for *non-symbol* regions `read_symbol` can't name — imports, file-
level annotations, initializer blocks, a span inside one huge method.

### Declaration monikers only — no side-table, no type resolution *[LOCKED]*
The FQNs that address `read_symbol`/`find_references` are **declaration monikers**: package +
enclosing type(s) + member name + param types — e.g. `shop.order.OrderService.settle(Order)`. These
are free from the parse, no resolution. We deliberately do *not* resolve **reference types**: a field
`private OrderRepository repo;` shows `OrderRepository` as written, and its moniker is
`OrderService.repo` (where it's *declared*), **not** `OrderRepository.repo` — the field's type is a
separate axis, one `find_definition("OrderRepository")` away if wanted. Declaration-moniker (cheap,
shown) vs type-reference (resolved, skipped) is the line drawn under `inlineBodyMaxChars`'s sibling
discussion earlier.

Critically, **there is no FQN side-table in the `source` view** — an earlier draft had one and it
didn't earn its keep. Every component of a declaration's FQN is *already on screen*: the package
line, the enclosing type, the signature with params. The agent assembles the selector from what it
reads. And jcma's selector is *suffix-anchored and forgiving* (`QualifiedName` / `resolveTargets`),
so the agent doesn't even need the full string — `read_symbol("OrderService.settle(Order)")`, often
just `read_symbol("settle")`, resolves. A table would reprint the screen. The one non-derivable bit —
canonical selector syntax for constructors / nested types — belongs in the tool's *description*, not
a per-call table. FQNs are emitted **explicitly only in the `tree` view**, for a programmatic consumer
that won't parse Java; in `source` they stay implicit.

### The weave
```
skim_java(File)                          → real Java + gutter; FQNs derivable from the view
  ├─ read_symbol(FQN)                    its full source — doc + body, complete       ← new
  ├─ find_references(FQN)                who uses it
  ├─ find_definition(FQN | TypeName)     where it's declared (incl. a signature's reference type)
  └─ find_subtypes / find_supertypes     the hierarchy
```
Every arrow is keyed on the same symbol identity the agent read off the skim — no resolve roundtrip
between steps. The skim is the launchpad that surfaces the symbols; everything else consumes them.

## Why docs are kept (the hard-won part)

This was the most-debated decision; record the reasoning so it isn't relitigated.

- **The tool must not classify, trim, or summarize docs.** Deciding which doc claims are
  "trustworthy" requires understanding the prose — an LLM, which jcma must never become. A single
  Javadoc sentence freely mixes a structural claim ("immutable") with a behavioral one ("persists to
  DB on insert"); they can't be mechanically separated. Even a syntactic split on Javadoc *tags*
  (`@deprecated`/`@throws`) fails — the free-prose first sentence is usually the best triage signal
  and carries no tag, so tag-filtering keeps the noise and drops the summary. So the tool stays a
  faithful extractor: docs verbatim, or off. No cleverness. *[LOCKED]*
- **Truncation amplifies stale-doc danger** (why an earlier "first line only" idea was dropped). The
  first sentence is the reassuring *intent*; the staleness that bites lives in the tail (`@return
  null if empty`, `@throws`, caveats). Cutting to the first line keeps the comforting half and drops
  the dangerous half — backwards.
- **Docs carry what the body structurally cannot**, which is why "you'll read the body anyway"
  doesn't make them redundant: contract/preconditions ("MUST hold the lock"), non-local pointers
  ("@deprecated — use settleV2"), and the cheap triage signal that lets the agent find the one
  method it cares about *without opening anything* — the redacted view's whole purpose. The body of
  a method never tells you a better method exists. These survive even when the doc's *behavioral*
  claim is redundant or deferred. *[LOCKED: keep, default on]*
- **Staleness is real but lower-stakes for what's kept.** A behavioral claim might be acted on as
  *fact* (stale → wrong answer); a contract/pointer note is used as a *lead* (stale → one wasted
  glance). The doc content that survives the "drop behavioral claims" concession is exactly the
  staleness-tolerant kind.

### The char gate and the doc question are the same threshold

Worth recording because it's the non-obvious payoff. Verifying a method's *behavioral* doc claim and
reading its body are the **same act** — there's no separate verify step. So `inlineBodyMaxChars`,
chosen purely for cost, also partitions doc-verifiability for free:

- **below threshold** — body shown → the doc's behavioral claim is checkable *in place, at zero extra
  cost* (the one-line `items.add(i)` body visibly debunks "persists to DB");
- **above threshold** — body elided → the claim is unverifiable in place, but the method is big enough
  that a drill-down was warranted anyway.

One number cleanly separates "cheap enough to show, and thereby verify the doc" from "expensive
enough that you should go read it." A prose-blind character count delivers a semantic property
(is this claim checkable from what's on screen?) without the tool understanding a single word —
which is why we get the verifiability partition without the forbidden classification step. It
doesn't make stale docs *safe*; it confines the unverifiable ones to the large methods the agent
would open anyway.

## Pitfalls

- **The substitution trap.** The danger isn't that the skim is partial — it's that it makes the
  agent *feel* it understands a file it has only seen the shape of, and answer confidently. Mitigated
  by visible elision (`{ … }` + line-gap) and by treating "I skimmed it" as "I know the shape,"
  never "I know the behavior."
- **Staleness if served from a cache.** A cached skim of a since-edited file paints a wrong picture.
  Re-parsing the single file fresh (as `jcma outline` does today, no index lookup) is cheap and
  sidesteps it. **[OPEN]** confirm: always fresh-parse, do not serve from the index overlay.
- **Calibration of the char budget.** Too low → needless drill-downs of getters/delegators; too high
  → no tokens saved. Hence tunable, with a measured default rather than a round number on faith.

## Relationship to existing code

`StructuralParser.Parsed.outline()` → `FileOutline`/`Outline` already yields package + nested
declarations with kinds, signatures, param types, and **line/col ranges** — everything the `tree`
view needs and the ranges the `source` view needs. The CLI `jcma outline` prints a containment tree
from it today. Gaps to close for `source`: render real-Java-with-elided-bodies (the outline is
currently declaration-only — it drops imports, `extends`/`implements`, modifiers, field/return
types, annotations, and the verbatim doc/body text), apply the char gate, and keep the gutter.
`read_symbol` reuses the existing target selector (`QueryService.resolveTargets` /
`jcma.query.QualifiedName`, already shared by the tools/CLI/REPL) to locate the declaration, then
returns its source span — so it is also mostly *exposure*, not new analysis.

## Open questions for the user — RESOLVED (2026-06-18)

- **[RESOLVED]** Tool name — **`skim_java`** (parallels the `_java` nav-tool family; captures the
  "read this file" reflex). The companion drill-down would be `read_symbol`.
- **[RESOLVED]** `inlineBodyMaxChars` — **body-only**, measured on the body's whitespace-normalized
  inner text (a short body inlines regardless of signature length; bias-to-show on ties). Default
  **100**, *calibrated* on jcma's own `src/main` (~770 block bodies): the boundary where the corpus
  splits from delegators/getters/guards (≤~100) into multi-statement real logic — not a round number.
- **[RESOLVED → DEFERRED]** The `tree` view waits for a programmatic consumer (it would carry the
  explicit FQNs for `read_symbol`); `source` is the only view shipped.
- **[RESOLVED]** **Always fresh-parse**, never served from the index — the index holds no render-grade
  data (no modifiers/annotations/verbatim doc+body), so a parse is required regardless; this also moots
  staleness.
- **[RESOLVED → DEFERRED]** `read_symbol(FQN)` is deferred. When built it reuses
  `QueryService.resolveTargets` / `QualifiedName` (the overload-agnostic selector that strips the
  `(params)` descriptor) and returns **all** matching overloads, one section each (see
  `qualified-name-selector`).
- **[RESOLVED → DEFERRED]** **Single file only** for now. Multi-file (glob/dir) is a later loop over the
  single-file renderer + budget shaping; the single-file path needs no rework to add it.
