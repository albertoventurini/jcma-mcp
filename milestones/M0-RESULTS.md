# M0 Results — **provisional: GO (pending B/C/D)**

> Decision memo for [M0-de-risking-spike.md](M0-de-risking-spike.md). **Spike A (the #1 risk —
> resolution accuracy) is complete and GREEN.** Spikes B (perf/memory), C (native-image), D
> (incremental format) are not yet run; the final GO/FALLBACK is gated on them. Nothing in Spike A
> triggers the javac-hybrid fallback.
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
| G5 throughput       | ~100k LOC in a few seconds | parse+resolve 115k occ in ~15s (not yet isolated) | ⏳ Spike B |
| G6 memory           | parse-only RSS ≤ ~2× §8 medium; resolution bounded | — | ⏳ Spike B |
| G7 latency          | single-file resolve p50 reasonable; find-refs within few× 200ms | — | ⏳ Spike B |
| G8 incremental fmt  | edit→query→compaction round-trips | — | ⏳ Spike D |

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

## Native-image reachability config needed (Spike C)
— pending —

## M1 requirements surfaced by Spike A
1. **Mandatory "unconfirmed tail":** never present a find-refs/find-impls set as exhaustive when
   any candidate-file occurrence failed to resolve — carry unresolved candidates explicitly.
   Proven necessary (overload-ambiguity candidates in `isEmpty`/`length` worksheets).
2. **Overload-ambiguity** is the failure worth engineering around (commons' top bucket):
   primitive-array overloads. Degrades safely today; candidate for a targeted resolver
   improvement or an explicit "ambiguous among {…}" answer.
3. Nested-member-access / annotation-member gaps: narrow, low navigation value — accept + surface
   as unconfirmed.

## Recommendation
**Spike A: GO** — the JavaParser+SymbolSolver accuracy bet (the make-or-break risk) holds
decisively, with a 0% silent-wrong rate and all misses degrading safely. Proceed to Spike B
(perf/memory, reuses the harness), then C (native-image) and D (incremental format) before the
final M0 GO/FALLBACK. Caveat: G2/G3 judged by reading code on commons-lang; jackson correctness
not yet sampled.
