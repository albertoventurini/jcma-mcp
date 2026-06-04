# M0 Results — **provisional: GO (pending C)**

> Decision memo for [M0-de-risking-spike.md](M0-de-risking-spike.md). **Spikes A (resolution
> accuracy — the #1 risk), B (perf/memory — the #1 *technical* risk), and D (incremental mmap
> format) are complete and GREEN.** Only Spike C (native-image) remains; the final GO/FALLBACK is
> gated on it. Nothing so far triggers the javac-hybrid fallback.
>
> Throwaway harness + raw artifacts under [`m0-spike/`](m0-spike/) (`out/` is gitignored;
> reproduce via `m0-spike/README.md`). Engine: `javaparser-symbol-solver-core:3.28.2`,
> `LanguageLevel.JAVA_25`. Corpora pinned: commons-lang `rel/commons-lang-3.20.0`,
> jackson-databind `jackson-databind-2.20.2`.

| Gate | Bar (calibrated, see below) | Actual | Pass? |
|------|-----|--------|-------|
| G1 coverage         | ≥ 97% resolvable occurrences resolve (safe-degrading misses) | **99.61%** jackson / 99.48% commons | ✅ |
| G2 go-to-def        | ≥ 99% correct (silent-wrong is the harmful mode) | **100%** (147/147, commons sample) | ✅ |
| G3 find-refs recall | ≥ 98% recall + unconfirmed-tail mandatory | **100%** recall, ~0% FP (10 symbols) | ✅ |
| G4 native-image     | builds & parse+resolve+mmap+stdio run in binary | — | ⏳ Spike C |
| G5 throughput       | parse ~100k LOC < 2s parallel | **0.48s** for 135k LOC (281k LOC/s, 14 cores) | ✅ |
| G6 memory           | live retained set bounded under sustained resolution | **flat ~310 MB** live set across full 135k-LOC sweep (committed RSS deferred to native) | ✅ |
| G7 latency          | cold per-file resolve p50 reasonable; warm find-refs within few× 200ms | per-file p50 **4 ms**; warm find_references worst **262 ms** | ✅ |
| G8 incremental fmt  | edit→query→compaction round-trips | **0 mismatches** over 200 edits + replay + compaction; overlay overhead ~1× | ✅ |

## Bars recalibrated from actuals (closes PRD §11 "exact navigation-correctness bar")

The M0 doc's placeholder 95/98/95 were accepted-on-faith round numbers. We re-derived them from
the **failure-mode split**, which matters more than any headline %:

- **Safe-degrading failure** (resolve *throws*): engine knows it failed → product returns
  lexical/trigram candidates tagged **"unconfirmed."** Agent is told, recovers, never concludes
  "absent." → tolerable; this is what coverage (G1) measures.
- **Silent-wrong failure** (resolve *succeeds but is incorrect/incomplete*): confident wrong
  answer, no signal. → the genuinely harmful mode; G2/G3 measure it, so their bars sit tighter.

**Measured silent-wrong rate ≈ 0%** (G2 0/147 wrong; G3 0 false-positives). Hence: G1 ≥ 97%
(completeness, not safety), G2 ≥ 99%, G3 ≥ 98% **with the unconfirmed-tail property mandatory**.
Ratify in M1 and write into PRD §11.

## Failure-cause histogram (Spike A) — most valuable artifact

All failures are **safe-degrading** (they throw). Causes, validated from exemplars:

- **jackson** (453): NESTED_MEMBER_ACCESS 46.8% (`JsonInclude.Value.empty()` — outer type
  resolves, nested member doesn't), ANNOTATION_MEMBER 33.3% (`ann.value()` accessor),
  MISSING_CLASSPATH 18.5% (`PackageVersion` = build-generated class, not on our manual cp),
  overloads/lambdas <2%.
- **commons** (246): OVERLOAD_AMBIGUITY 63.4% (primitive-array overloads of `Arrays.equals/sort`
  + commons' own; plus a bare-`null`-arg bug), LAMBDA_METHODREF 23.2% (`T[]::new` array-ctor
  refs, unbound `Comparable::compareTo`), NESTED 7.7%, MISSING_CLASSPATH 3.7%, OTHER 2.0%.

Per-category coverage weak spots: FIELD_ACCESS 93.3% (jackson, nested access), METHOD_REF ~60%
(tiny absolute counts). None silent.

## Performance & memory (Spike B) — full detail in `m0-spike/out/spikeB-summary.md`
- **G5 throughput:** parse-only parallel (virtual threads) — jackson 135,887 LOC in **0.48 s**
  (281k LOC/s), commons 100,950 LOC in 0.29 s. ~6–10× inside target.
- **G6 memory (the #1 *technical* risk):** under a full 135k-LOC resolution sweep the post-GC
  **live set is flat at ~310 MB** (211 MB baseline + ~107 MB SymbolSolver cache, plateaued from
  the first 25 files) → **caches are bounded, no unbounded growth.** Committed VmRSS (~1.37 GB
  under `-Xmx6g`) is transient allocation churn, not retained — heap-policy-dependent, deferred
  to the native baseline (Spike C).
- **G7 latency:** cold per-file resolve p50 **4 ms** (p99 288 ms — one-time index cost); warm
  find_references (steady-state query) p50 ~20 ms, worst 262 ms (102-candidate overloaded name) —
  within 200 ms × headroom; the M1 edge cache turns repeats into lookups.

## Incremental mmap format (Spike D) — full detail in `m0-spike/out/spikeD-results.md`
Prototyped the §5.1 store on a synthetic graph (50k symbols, 250k edges, 2k files): FFM-mapped
columnar base (`Arena`+`FileChannel.map`→`MemorySegment`, packed int columns + **CSR both
directions**) with an LSM **base + indexed overlay + compaction**, moniker-stable identity.
- **G8: PASS — 0 mismatches** across 200 per-file edits (add / method-body / API-surface scoped
  referrers / delete), crash-log replay on reopen, and post-compaction queries — all checked vs
  an in-memory oracle.
- **Overlay-merge query overhead ~1×** (16.6 ms vs 18.2 ms for a 5k-query batch with 521 edited
  files) when the overlay is indexed as §5.1 intends — per-file mutation needs **no rescan**.
- **Compaction** (rewrite base + fsync + atomic rename + reopen) **586 ms** for the full graph;
  query-identical pre/post.
- The FFM read/write path worked under the JVM — **seeds Spike C's "FFM mmap under native-image"
  check** (the one remaining unknown).

## Native-image reachability config needed (Spike C)
— pending (the last gate) —

## M1 requirements surfaced by Spike A
1. **Mandatory "unconfirmed tail":** never present a find-refs/find-impls set as exhaustive when
   any candidate-file occurrence failed to resolve — carry unresolved candidates explicitly.
   Proven necessary (overload-ambiguity candidates in `isEmpty`/`length` worksheets).
2. **Overload-ambiguity** is the failure worth engineering around (commons' top bucket):
   primitive-array overloads. Degrades safely today; candidate for a targeted resolver
   improvement or an explicit "ambiguous among {…}" answer.
3. Nested-member-access / annotation-member gaps: narrow, low navigation value — accept + surface
   as unconfirmed.

## M1 requirements surfaced by Spike B
1. **Resolved-edge cache is load-bearing for latency** — warm find_references re-resolves every
   candidate today (worst 262 ms); the §5.1 cached edges convert repeats to lookups.
2. **Optimize for allocation churn, not retention** — transient heap spikes to ~1 GB; caching
   resolved edges (not ASTs) in the compact store should cut both churn and committed RSS.

## M1 requirements surfaced by Spike D
1. **Overlay must be indexed, not scanned** — query overhead is ~1× with an indexed overlay but
   blows up (~340×) with a naive linear merge; the in-memory overlay needs src/dst indices.
2. **Phantom nodes for dangling edges** — a delete can leave an edge whose target's declaring file
   is gone; the moniker persists as a node with no declaration (consistent with monikers naming
   never-parsed dependency symbols + validate-on-read). Compaction must preserve these.

## Recommendation
**Spikes A + B + D: GO.** Accuracy holds (0% silent-wrong, all misses safe-degrading); the #1
technical risk is disproven (SymbolSolver live set flat/bounded; throughput + latency under
budget); and the trickiest store mechanism round-trips correctly with ~1× overlay overhead. Only
**Spike C** (native-image — re-baselines memory/startup, validates the FFM read path Spike D
exercised, where the §8 native targets get tested) stands between here and the final M0
GO/FALLBACK. Caveats: G2/G3 judged by reading code on commons-lang (jackson correctness not yet
sampled); Spike B JVM-only/observational; Spike D on a synthetic graph.
