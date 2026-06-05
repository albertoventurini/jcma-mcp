# Milestone 0 — De-risking spike

> **Type:** gate, not a deliverable. The code is minimal/scrappy but **kept as a reference for
> later stages** (not deleted).
> **Status:** not started
> **Estimated effort:** ~3–5 focused days
> **Predecessor:** none · **Blocks:** M1 (committing to the engine + index format)
> **Parent:** [PRD.md](../PRD.md) §7 Milestone 0

## Objective

Answer one question with evidence, not opinion: **is the JavaParser + JavaSymbolSolver →
GraalVM native-image bet viable for an agent-native Java navigation tool?**

Concretely, prove four things on a *real* codebase: (A) resolution is **accurate enough** for
navigation, (B) it hits the **memory/latency budget**, (C) the whole stack **compiles to and
runs as a native-image**, (D) the **incremental mmap index format** can be mutated without a
full rescan. Any gate that fails triggers the documented fallback to the **javac-hybrid on
HotSpot** engine (PRD §4 Contingency) — better to learn that now than in month three.

We are *not* building product here. Minimal, ugly code is correct. **Keep it** after the decision
memo is written — the spikes are a useful reference for later stages; M1 starts as fresh
production code rather than extending them.

---

## Go / No-Go gates

The spike succeeds only if **all** gates pass. Thresholds below are **proposed starting bars** —
confirm/adjust them *before* running (see Open decisions), then record actuals against them.

| # | Gate | Proposed bar | Spike |
|---|------|--------------|-------|
| G1 | Reference/type resolution **coverage** | ≥ 95% of resolvable occurrences resolve without error on the scale repo | A |
| G2 | **Go-to-definition correctness** | ≥ 98% correct on the hand-labeled sample | A |
| G3 | **Find-references recall** | ≥ 95% vs. ground truth on labeled symbols | A |
| G4 | **Native-image** builds **and** parse+resolve+mmap+stdio all work in the binary | binary works (pass/fail) | C |
| G5 | **Index throughput** (parse-only, parallel) | ~100k LOC in a few seconds | B |
| G6 | **Memory** under load | parse-only-index RSS ≤ ~2× §8 medium target (headroom: persistence not built yet); resolution does not grow RSS unboundedly | B |
| G7 | **Resolution latency** | single-file resolve p50 reasonable; simulated `find_references` within a few× the §8 200ms target (pre-optimization headroom) | B |
| G8 | **Incremental format** round-trips | edit → query → compaction correct on the prototype | D |

> Why the headroom on G6/G7: M0 has no persistence/warm-start and an unoptimized store, so we
> measure *trajectory*, not final numbers. The §8 targets are the M1–M3 bar.

**On failure:** stop, write the decision memo, and pivot to the **javac-hybrid/HotSpot** fallback
— re-baseline §8 for a JVM (CDS/jlink startup, higher memory ceilings) and re-scope M1.

---

## Spikes

Each spike answers one question. Suggested order: **C-smoke → A → B (reuses A's harness) → D
(independent, can run in parallel).** C is checked early because a hard native-image
incompatibility could kill the approach cheaply; A is the core bet.

### Spike A — Resolution accuracy & coverage *(the #1 risk)*
**Question:** does JavaParser + JavaSymbolSolver resolve references/types correctly enough for
navigation?

1. Configure a `JavaSymbolSolver` with a `CombinedTypeSolver`: project source roots +
   dependency jars + JDK (`ReflectionTypeSolver`). Get the classpath manually
   (`mvn dependency:build-classpath`).
2. Parse all sources; walk every resolvable node — `NameExpr`, `MethodCallExpr`,
   `FieldAccessExpr`, `ObjectCreationExpr`, type references — and call `.resolve()`.
3. **Coverage:** count occurrences that resolve vs. throw `UnsolvedSymbolException`/other.
   Bucket failures by cause: generics/inference, overload ambiguity, lambdas/method refs,
   `var`, pattern matching/records/sealed, missing classpath, parser gaps.
4. **Correctness (sample):** hand-label **100–200 use-sites** for go-to-def (expected
   declaration) and **~10 symbols** for find-references (expected full usage set). Compare.
   *(Optional differential oracle: cross-check a subset against an existing tool — jdt.ls via
   VS Code, or `georgewfraser/java-language-server`.)*

**Record:** coverage %, correctness %, find-refs recall, and the failure-cause histogram (this
histogram is the most valuable artifact — it tells us exactly where SymbolSolver is weak and
whether those cases matter for agent navigation).

### Spike B — Performance & memory at scale
**Question:** does it trend toward the §8 budget?
Reuses Spike A's harness.

1. **Index throughput:** wall-clock the parse-only full scan, parallelized across **virtual
   threads**. Report LOC/sec and total.
2. **Memory (RSS):** measure at — (a) idle post-startup, (b) after full parse-only index,
   (c) during a batch of resolutions. Watch SymbolSolver's cache growth (known risk).
3. **Latency:** time to resolve one file's occurrences; time a **simulated `find_references`**
   = resolve all files whose text contains the target's simple name (the trigram-pruned
   candidate set from PRD §5.1).

**Record:** the numbers vs. G5/G6/G7, plus the RSS curve shape under sustained resolution.

### Spike C — Native-image viability
**Question:** can the stack compile to and run as a GraalVM native-image?

1. Install a native-image toolchain (GraalVM / Liberica NIK — **not** the stock Temurin 25;
   see Setup).
2. Build a **minimal program** exercising all four risky capabilities:
   - parse a file with JavaParser,
   - resolve one symbol with `JavaSymbolSolver` (incl. `ReflectionTypeSolver` — the fiddly bit),
   - mmap a file via the **FFM API** (`Arena` + `FileChannel.map` → `MemorySegment`) and read a struct,
   - a trivial **MCP stdio loop**: read JSON-RPC `initialize` + `tools/list` from stdin, reply on stdout.
3. Run under the **native-image-agent** on the JVM first to collect reflection/resource
   reachability metadata; then `native-image` build.
4. Run the binary; confirm all four work natively.

**Record:** pass/fail per capability, the metadata config needed (especially for SymbolSolver
reflection), binary size, startup time, RSS. Document every reachability workaround — it's the
seed of M1's `META-INF/native-image/` config.

### Spike D — Incremental mmap format prototype *(independent; JVM-only is fine)*
**Question:** can the CSR graph be mutated via LSM base+overlay+compaction without rescanning?

1. Define a minimal on-disk format: **base segment** = symbol columns + CSR fwd/rev adjacency
   for a small synthetic graph.
2. Implement an **overlay**: append a new "file"'s symbols/edges + **tombstones** for changed/
   deleted ones.
3. Implement a query reading **base ∪ overlay, skipping tombstones**.
4. Implement **compaction**: rewrite a fresh base from base+overlay, fsync, atomic rename.
5. Test: simulate add/modify/delete file edits; verify queries reflect them; verify compaction
   yields a correct compact base.

**Record:** correctness round-trip (G8), overlay-merge query overhead, compaction time.

---

## Test corpus

Pick **Maven** projects (manual classpath is trivial; Gradle is deferred per PRD §3). Two tiers:

- **Accuracy / labeling (clean, medium):** **Apache Commons Lang** — pure Maven, minimal deps,
  modern Java, easy to hand-label ground truth.
- **Scale / memory (larger, real deps):** **jackson-databind** (~120k LOC, depends on
  jackson-core/annotations) **or Guava**. Use this one for G1/G5/G6/G7.

Check both out at a pinned tag/commit (record it — Spike D's git-accelerator idea reuses it).

---

## Environment & setup

- **JDK:** Temurin 25 is present, but `native-image` needs a **GraalVM-class** distro
  (GraalVM for JDK 25 or Liberica NIK). Install separately; document the exact version.
- **Dependencies:** `com.github.javaparser:javaparser-symbol-solver-core` (pin the latest
  release; confirm it parses JDK-25 syntax — record any gaps).
- **Build:** Gradle or Maven for the spike harness (project's own build tool is still open,
  PRD §11) — doesn't matter for throwaway code; pick whatever is fastest.
- **Classpath of the target repo:** `mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`.

---

## Deliverables

1. **Spike code** under `milestones/m0-spike/` — kept as a reference (not deleted); M1 starts as
   fresh production code rather than extending it.
2. **`milestones/M0-RESULTS.md`** — the decision memo, the *real* output of M0:
   - the gate table with **actual** measured values filled in,
   - the Spike-A **failure-cause histogram**,
   - the native-image **reachability config** that was needed,
   - a one-line **GO / FALLBACK** recommendation with rationale.

### RESULTS.md skeleton
```markdown
# M0 Results — GO | FALLBACK

| Gate | Bar | Actual | Pass? |
|------|-----|--------|-------|
| G1 coverage         |  |  |  |
| G2 go-to-def        |  |  |  |
| G3 find-refs recall |  |  |  |
| G4 native-image     |  |  |  |
| G5 throughput       |  |  |  |
| G6 memory           |  |  |  |
| G7 latency          |  |  |  |
| G8 incremental fmt  |  |  |  |

## Failure-cause histogram (Spike A)
## Native-image reachability config needed (Spike C)
## Recommendation + rationale
```

---

## Out of scope for M0 (defer to M1+)
- Index **persistence/durability**, warm-start reconciliation, FS watcher.
- The full **MCP tool set** (only the trivial echo loop here).
- **Classpath auto-detection** beyond a manually-provided list.
- **Invalidation correctness** beyond Spike D's round-trip (edit-locality, validate-on-read).
- Any production concern: error handling, packaging, config, ranking quality.

## Open decisions to resolve during M0
- **Confirm the G1–G3 bars** before running (PRD §11: exact navigation-correctness bar).
- **Scale-repo choice** (jackson-databind vs. Guava).
- **GraalVM distro & version** (GraalVM for JDK 25 vs. Liberica NIK).
- **Hash algorithm** for fingerprints (xxHash64 vs. alternative) — can defer to M1, but if
  Spike D touches it, decide here.
