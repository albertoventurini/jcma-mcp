# M1 · Task 11 — Invalidation (model-everything, node-diff cascade)
> Correctness is exact graph reachability, not a fingerprint heuristic.

This is a **parent doc**: task-11 is split into three sub-tasks (11a/11b/11c), each runnable in a
fresh Claude Code session. Read this doc for the shared model, then pick a sub-task file.

## Why this was re-planned (the decision)
The original task-11 ("every cached edge carries a *dependency fingerprint*; API-surface edits are
scoped by the *changed simple-name set + trigram*") was **undermodelled**. A fingerprint is a hash
used as a *proxy* for "did the thing I depend on change?" — it detects *that* something changed but
discards *what* changed and *who* depended on it, forcing a lexical (trigram) **guess** at the blast
radius. That guess under-approximates on Java's non-lexical dependencies (supertypes, interfaces,
overrides, overload sets), and **under-invalidation = a stale wrong answer**.

**Decision: model the dependency structure fully and invalidate by exact graph reachability.** The
index is already graph-native (references are edges to member-granularity nodes); we finish the job
and let invalidation be a *node-diff + reverse-edge walk* instead of a hash + trigram heuristic.

## The model (shared context for all three sub-tasks)
- **Edit-locality is structural, not a heuristic.** A reference is an edge to a **member-granularity
  node** (`app/Service#run().`), and a node's *incoming* edges live in the **referrer's** file slice
  (LsmStore attributes an edge to its `src` symbol's file). So a **method-body edit** re-parses the
  edited file — re-deriving only its *own* outgoing edges — while its members keep their moniker
  identity, so every incoming edge stays valid and untouched. *This already works today*; no
  fingerprint needed for the common case.
- **Cross-file invalidation = node-diff + reverse-edge walk (exact).** Re-indexing a changed file
  diffs its **old vs new node set**; for each **removed / signature-changed** node, walk
  `store.rev(node)` to the *exact* referrer files and return them to **unresolved** — they
  re-resolve lazily on the next query that touches them. No trigram, no changed-name-set, no guess.
- **Completeness needs the non-lexical edges modeled too** — hence 11a and 11b: the **type
  hierarchy** (`EXTENDS`/`IMPLEMENTS`/`OVERRIDES`) and **unconfirmed references** (persisted as a
  dependency edge to the type they were attempted against). With those in the graph, a supertype
  edit and a newly-satisfiable lookup cascade by the *same* reverse-edge walk.
- **The filesystem boundary keeps a fingerprint — and only there.** The **content fingerprint**
  (`size, mtime, xxHash64`) answers "did this file's bytes change?", which the graph *cannot* derive
  about itself. It is the **trigger** that says "re-derive this file's nodes." It stays. What dies is
  the *API/dependency fingerprint as a propagation proxy*.
- **Triggers / correctness floor:** a live tree-scan `FreshnessSource` (11c) streams changed files
  while the process runs; the **on-access backstop** (task-09, exists) stat/hash-checks the files a
  query actually reads. Either way a changed file is re-indexed and its cascade applied *before* the
  read returns — worst case a re-resolve, never a wrong answer (rust-analyzer/Salsa, in miniature).

## Watch what that means for the three cases, with *zero* fingerprints
- **Body edit to `Service.run()`** → re-index `Service.java`. The node `Service#run().` comes back
  with the **same moniker**; `Client`'s slice is never touched; `rev(Service#run().)` still returns
  `Client.go`. **Already correct, structurally** — no hash, no cascade, no trigram. (This is why the
  API fingerprint earns nothing here: the slice model already gives you edit-locality for free.)
- **Rename `run()`→`execute()`** → re-index `Service.java`. Now **diff `Service`'s old node set vs.
  new**: `Service#run().` was removed. Walk `rev(Service#run().)` → `{Client.go}` → re-resolve
  exactly those referrers. **Exact, structural, member-granular.** The API fingerprint was only ever
  a fuzzy way to detect "something in `Service` changed" so you could fall back to a lexical trigram
  guess at who cares. Once you keep the nodes and diff them, you have the precise answer and the
  fingerprint + trigram both vanish.
- **The supertype / negative cases** (the ones trigram missed) → these are the places we're
  *currently* undermodelled, and "model everything" names the fix exactly:
  - Model `EXTENDS / IMPLEMENTS / OVERRIDES` as real edges (owed to §6 anyway). Then `Service`'s
    *effective* surface change is a change to its modeled structure (a new `EXTENDS` edge), and its
    referrers are reachable. *(task-11a)*
  - Model the unconfirmed/failed reference as a **persistent edge to the type it was attempted
    against**, instead of the in-session `unconfirmedByName` list it is today:
    `Client.poke --(unresolved foo)--> Service`. Then when `Service` gains `foo`, the node-diff on
    `Service` walks `rev(Service)` → `Client` → re-resolve → it now binds. The hardest case becomes a
    plain reverse-edge walk. *(task-11b)*

So the cascade — node-diff + reverse-edge walk over a fully-modeled graph — is **exact**: no lexical
approximation, no hash-as-proxy. It is strictly better than the API-fingerprint+trigram scheme, and
it is the *same* graph that answers the actual queries. Reaching for the fingerprint was a way to
avoid committing to that modeling; the modeling is the right instinct.

## The three sub-tasks (recommended execution order)
The cascade (11c) must walk **all** dependency edge types uniformly, so build the modeling first.

| # | File | Goal | Depends on |
|---|---|---|---|
| 11a | [task-11a-hierarchy-edges.md](task-11a-hierarchy-edges.md) | `EXTENDS`/`IMPLEMENTS`/`OVERRIDES` as first-class edges | task-10 |
| 11b | [task-11b-unconfirmed-edges.md](task-11b-unconfirmed-edges.md) | unconfirmed refs → persistent dependency edges | task-10 |
| 11c | [task-11c-node-diff-cascade.md](task-11c-node-diff-cascade.md) | node-diff cascade + tree-scan `FreshnessSource` | 11a, 11b |

11a and 11b are independent of each other (either order). 11c is last (it consumes both).

## Settled in the discussion — do not relitigate
- **No API/dependency fingerprint, no changed-simple-name-set, no trigram cascade.** Replaced by
  node-diff + reverse-edge walk. (This overturns the prior PRD §5.1 wording — §5.1 updated to match.)
- **Content fingerprint stays** as the filesystem-change trigger (the one fact off-graph).
- **Edit-locality for body edits is structural** (member node identity + referrer-owned edges).
- **Hierarchy + unconfirmed references are modeled as edges** (11a, 11b) so the cascade is complete;
  these also serve PRD §6 (`find_subtypes`/`find_implementations`/`call_hierarchy`) — reuse, not
  speculation.
- **Value-hash early-cutoff** (stop propagating when a re-derived node is byte-identical) is an
  *optional* optimization atop the model — **deferred** until measurement asks for it.
- Residual approximation frontier (generic inference / overload corners) is accepted; the
  external-code boundary bottoms out at content fingerprints on source files + jars (+ phantom nodes).

## Protocol (every sub-task)
Per the M1 overview: **write failing tests + fixtures → STOP for review → implement → verify**
(`./gradlew test` green · `./gradlew nativeCompile` green + native CLI smoke · the sub-task's manual
CLI check). The red→pause→green checkpoint is a hard gate; do not collapse it into one turn.
