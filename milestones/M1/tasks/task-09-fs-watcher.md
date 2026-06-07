# M1 · Task 09 — Freshness: in-session freshness (on-access backstop + change-detection options)
> Keeping the index fresh during a session when the protocol gives us no change notifications — MCP
> has no client push channel at all, and even LSP's `didChange` would only cover in-client edits
> (PRD §5.1 freshness). Originally scoped as a *live FS
> watcher*; the design discussion below **demotes the watcher to an optional, swappable producer** and
> picks the minimal **on-access backstop** for M1. The watcher options are recorded in full so the
> upgrade is a measured, drop-in decision later — not a rewrite.

## Prerequisites (read first, fresh session)
- **Done before this:** task-08 (`Fingerprint` + `FileTable` + `Reconciler` supply the freshness primitives).
- **Read:** PRD §5.1 (freshness pipeline) ; M1 overview.
- **Port from M0 (reference, don't extend):** none (new code).

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

---

## Settled primitive — `reindexOne(path)` (every option needs it)
A per-file reconcile, factored out of `Reconciler`'s existing NEW/SUSPECT branches (`Reconciler.java`
lines ~99–122): stat/hash `path` vs its `FileTable` row → on change, parse + `store.applyEdit` and
refresh the row; on match, no-op; compaction left to the existing `CompactionPolicy` (no compact per
edit). This is the structural (Tier-1) per-file swap PRD §5.1 calls for ("re-parse the one file, swap
its slice"). It is **not** task-11's job: task-11 owns the *resolved-edge* cache invalidation
(edit-locality + validate-on-read across files); this is just the structural re-parse below it.

Built **first, test-first**. Every option below funnels through it.

## Design frame — the freshness *trigger* is a swappable producer
MCP is request/response: no `didChange`. The correctness floor is **validate-on-read** — before
answering, make the files a query depends on fresh. *How we learn a file changed* is a separate,
swappable concern behind a **`FreshnessSource` / producer contract** (an in-memory dirty set, or a
`.jcma/dirty.log` journal drained at query time). This is what makes the watcher non-blocking: any
detector below can be added later as a drop-in producer feeding the same contract, with **no change to
the query core**. The contract is the stable thing; the producer is the swappable thing.

---

## Options considered (full pros/cons from the design discussion)

> Two failure modes frame every option: **O(tree)-per-query latency** (bad for the common *exploratory*
> query that changed nothing) and **freshness windows** (intervals where the index silently lags the
> disk → risks the banned *silent-wrong* answer). The correctness floor under all of them is a stat
> comparison against the `FileTable`; the options differ in *how cheaply they learn what to re-check*.

### Option 1 — On-access validate-on-read, scoped to the files a query reads  ✅ **CHOSEN for M1**
Before serving, `stat`/hash only the file(s) the query actually touches; `reindexOne` any that are
stale. No background thread, no tree walk in the common path.
- **Pros:** minimal code; **no background process** and **no freshness window for files actually read**
  (they are validated at read time) — avoids *both* of the failure modes above and both of your stated
  dislikes; no dependency; no native-image risk; single binary; literally the "stat/hash-on-access
  backstop on any queried file" already in this task's scope; reuses `reindexOne`.
- **Cons:** it only knows about files a query *reads*. A query that must discover **newly-changed files
  it didn't already know to read** is not covered — chiefly **find-references** when a *new* referrer
  appears (the file isn't in the reverse-edge list yet, so it's never stat'd). That cross-file
  completeness is **task-11's** job (trigram-pruned candidate set + validate-on-read), not this task's.
  Cold / first-query-of-session freshness is covered by the task-08 warm-reopen reconcile, which still
  runs once on open.

### Option 2 — Query-time whole-tree stat-walk reconcile (lazy `Reconciler.reindex` per query)
- **Pros:** simple; correct; **universal** (catches every edit path — agent, human/IDE, git, any
  tool); reuses `Reconciler` as-is; no dependency; no background thread; native-image trivial; single
  binary. (Note: the fast path *stats* size+mtime and hashes only *suspects* — it is **not** a
  whole-tree rehash.)
- **Cons:** **O(tree) on every query**, wasted on the common exploratory (nothing-changed) query;
  ~100–250 ms on a 50k-file repo → can blow the <200 ms query budget before touching the index. (You
  objected to this: expensive per query.)

### Option 3 — Background pure-Java poller (Apache Commons IO `FileAlterationMonitor`, or home-grown)
A timer thread walks the tree every N seconds, diffs stat snapshots, maintains the dirty set; the query
just drains it.
- **Pros:** **native-image trivial** (pure Java, no JNI, no reflection) — precisely *because* it is
  polling; single binary; **universal** (all edits, any harness); moves the O(tree) cost **off the
  query hot path** → per-query O(changes); no new dependency if home-grown over `Reconciler`'s walk.
- **Cons:** it *is* polling → **continuous O(tree) background CPU even when idle**; scales poorly on
  huge repos; **freshness lag up to the poll interval** → small windows where the index isn't fresh.
  Commons IO adds a dependency for something we can write in ~100 lines over the existing walk.
  **(Rejected for M1:** you dislike both the O(tree) churn and the non-fresh windows.)

### Option 4 — JDK `WatchService` (in-process inotify / FSEvents / ReadDirectoryChangesW binding)
- **Pros:** event-driven on **Linux/Windows** → O(changes), near-zero idle cost; built into the JDK,
  no dependency; universal; single binary.
- **Cons:** **native-image support unverified** (the load-bearing risk this task originally carried);
  on **macOS the JDK impl is polling** (≤10 s latency), not events; **not recursive** — must register
  every directory and race new-dir creation; **watch-limit exhaustion** (`max_user_watches`) on huge
  trees; queue **overflow** drops events → must handle `OVERFLOW` and resync with a walk (safe only if
  handled — *not* silent-wrong if handled).

### Option 5 — FFM inotify (our own binding via the Foreign Function & Memory API)
Bind `inotify_init1` / `inotify_add_watch` / `read` / `close` directly via FFM (the same zero-JNI
mechanism the mmap index store already uses, M0-proven native).
- **Pros:** **native-image clean by construction** (FFM downcalls to libc, no JNI, no extracted `.so`);
  **true events**, near-zero idle cost; single binary; **no external process**; consistent with the
  codebase's "FFM, zero JNI" identity; universal.
- **Cons:** ~300–600 LOC of `struct inotify_event` parsing + recursive watch management (one watch per
  dir, register new dirs on `IN_CREATE|IN_ISDIR`) + `IN_Q_OVERFLOW` handling; **Linux-only** — macOS
  FSEvents via FFM is genuinely hard (CoreFoundation run loop + FFM upcalls), Windows is a third
  binding → needs a fallback (Option 1/3) elsewhere; more effort than M1 needs now.

### Option 6 — External watcher process (any language: watchman / fswatch / Rust `notify`) → journal/IPC
A tiny separate process watches the tree and streams change events to jcma (journal file, pipe, or
socket — transport is a detail).
- **Pros:** **sidesteps native-image entirely** (jcma never links a watcher); can use best-in-class
  libs → real **cross-platform events incl. macOS FSEvents**, proper recursion, and graceful overflow
  (watchman's "fresh instance → re-crawl" handshake); universal; decoupled lifecycle if via a journal
  file (producer and jcma needn't be up simultaneously).
- **Cons:** **breaks the "single self-contained binary" settled decision** (CLAUDE.md) — either depend
  on an installed tool (watchman/fswatch) or ship a **second per-platform binary**; orchestration
  (who starts/stops it, dies-with-parent); an IPC contract to define; release/distribution complexity.

### Option 7 — Git as diff source at query (+ whole-tree stat-walk fallback)
Store the indexed rev in the index header; ask git for the changed set.
- **Pros:** `git diff --name-only <indexedRev> HEAD` compares **tree objects (SHAs)** → genuinely
  **O(changes)** for *committed* changes, no working-tree stat; "changed set for free" when applicable.
- **Cons (incl. your question — *does git just do an O(tree) scan too?*):** **mostly yes.** The
  committed-diff above is cheap **but misses uncommitted working-tree edits — the common agent case.**
  To catch those you need `git status` / working-tree diff, which **stats every tracked file = O(tree),
  the same scan we'd do ourselves**, *unless* the repo has `core.fsmonitor`/Watchman — in which case
  git is merely delegating to a watcher we could integrate directly. So git is **dominated**: either
  incomplete (committed-only), or no cheaper than our own walk (working-tree), or a proxy for a watcher.
  Plus: git dependency + subprocess on the hot path; not-a-git-repo / submodule / worktree / detached
  edge cases; files outside the source roots. **Conclusion: not worth relying on git.**

### Option 8 — Agent hook as edit source (e.g. Claude Code `PostToolUse` on `Edit|Write` → journal)
The agent reports the file it just edited; the hook appends the path to the dirty journal.
- **Pros:** exact, semantic signal (the tool wrote file X), no syscalls, no tree walk; **no
  native-image risk** (runs in the agent process); naturally lazy (journal drained at query);
  exploratory queries ≈ free; agent-native.
- **Cons (weaknesses to record):** sees **only agent edits via recognized tools** — misses
  external/human/IDE edits, `git checkout`/`pull` run through the agent's Bash tool, and **custom MCPs
  or any mutator we don't recognize**; **harness-specific** (Claude Code hooks ≠ OpenCode / Codex /
  Pi) → a portability/compatibility problem; opt-in and fragile to set up. **Cannot be trusted as the
  correctness source** → must be **coupled with another approach (the on-access backstop, or a
  universal producer) to guarantee eventual consistency.** Best as a precision *accelerator* on top of
  a universal floor, never alone.

### Option 9 — `gmethvin/directory-watcher` (maintained library: `WatchService` + macOS FSEvents)
A maintained library giving accurate, recursive, **non-polling** watching on Linux/macOS/Windows. It
uses the JDK `WatchService` on Linux/Windows and a **JNA-based FSEvents** `WatchService` on macOS to
replace the JDK's polling mac impl. Small footprint: `slf4j-api` (logging) + `jna` (macOS only).
- **Pros:** maintained, cross-platform, **recursive**, event-driven everywhere; the genuinely hard
  part — the Java-side file-hashing in `MacOSXListeningWatchService` that turns FSEvents' coarse,
  directory-level notifications into precise create/modify/delete events — is **done for you and is
  platform-agnostic** (kept untouched); in-process → **single binary preserved** (unlike Option 6);
  effectively subsumes Option 4's good case while adding recursion.
- **Native-image:**
  - **Linux/Windows — a non-issue:** that path is just the JDK `WatchService`, already native-clean.
  - **macOS — workable:** it reduces to "does JNA work under native-image," now **solved with
    metadata** — official JNA reachability metadata lives in GraalVM's metadata repo
    (`net.java.dev.jna/jna`), pulled automatically by the Gradle/Maven native plugins, with working
    JNA + native-image examples. **But** the JNA + native-image + FSEvents-callback-thread combination
    is the one with moving parts (loading JNA's bundled native stub, metadata registration, the
    callback thread) — **test this combination before committing.**
- **Cons:** a third-party dependency (+ `slf4j-api`, `jna`); the macOS native-image path, while
  workable, is the part to actually verify; `slf4j-api` pulls a logging facade into the binary.

**Variant 9a — vendor the macOS backend, rewrite JNA → FFM.** A separate analysis found this feasible:
the JNA surface is a single `CarbonAPI` interface + a few pointer/struct wrappers, and the native calls
are a short, enumerable list (~9–11 functions: `FSEventStreamCreate`, `CFArrayCreate`,
`CFRunLoopGetCurrent`/`Run`/`Stop`, `CFStringCreateWithCharacters`,
`FSEventStreamScheduleWithRunLoop`/`Start`/`Stop`, `FSEventStreamInvalidate`/`Release`) plus exactly
one callback type, `FSEventStreamCallback`.
- **Pros:** drops JNA entirely → **fully native-clean via FFM**, consistent with the codebase's
  "FFM, zero JNI" identity; you **keep all the platform-agnostic event-precision Java** untouched; far
  less work than rolling our own from scratch (Option 5), since the hard logic is reused.
- **Cons:** you fork/vendor and maintain the macOS backend; the **FFM upcall stub** for
  `FSEventStreamCallback` (`(streamRef, info, size_t numEvents, char** paths, uint32 flags[],
  uint64 ids[]) -> void` — walk the `char**` + parallel arrays as `MemorySegment`s) is the one
  conceptually new piece and where debugging time goes; struct/pointer layouts
  (`FSEventStreamContext`, the variable-length path array) are fiddly; the **`Arena` lifetime must span
  the stream + run loop** or the callback crashes (a different model from JNA's keep-refs-alive).

---

## Decision (M1) — pick the easiest, defer the rest behind the seam
**Ship Option 1 (on-access validate-on-read) + the `reindexOne` primitive, behind the `FreshnessSource`
producer seam. No watcher in M1.**

- **Why:** it's the minimal correct mechanism, it's already in this task's scope, and it avoids *both*
  failure modes and both of your stated dislikes — **no O(tree)-per-query** and **no freshness window
  for files actually read**. No dependency, no native-image risk, single binary.
- **Deferred (recorded above, drop-in later via the seam):** cross-file find-references completeness →
  **task-11** (trigram-pruned validate-on-read). Proactive/low-latency change detection at scale →
  **Option 9 (`directory-watcher`)** is now the leading watcher candidate — maintained, recursive,
  cross-platform, in-process (single binary), with the hard event-precision logic already written;
  prefer **Variant 9a** (JNA→FFM macOS backend) for a fully native-clean build, else verify Option 9's
  JNA-on-macOS native-image path first. Option 5 (own FFM inotify) and Option 6 (external process)
  remain fallbacks; Options 3/4/7/8 documented but not preferred.
- **Doc sync (follow-up):** update PRD §5.1 (the "during a session: a filesystem watcher" line) and the
  M1 index entry for task-09 to match this reframe (watcher = optional producer, not M1 core).

## Scope — files to create/modify
- `src/main/java/jcma/workspace/Reconciler.java` (or a new `FreshnessGuard`) — factor out
  **`reindexOne(path)`** from the existing NEW/SUSPECT logic.
- **On-access backstop** — `ensureFresh(path)` (stat/hash vs `FileTable` → `reindexOne` on mismatch),
  to be called by the query read path (the real wiring lands with the Tier-2 query path, task-10).
- `FreshnessSource` seam (thin) — the producer contract the backstop reads; M1 impl = direct
  on-access; leaves a clean slot for a later watcher/journal producer.
- *(No `watch` subcommand in M1 — deferred with the watcher.)*

## Tests (red-first)
- Unit: `reindexOne` on an unchanged file is a no-op (no store edit); on a changed file re-parses and
  the new symbols are queryable; on a mtime-lie (size+mtime differ, hash same) is a no-op.
- Unit: `ensureFresh` detects an **out-of-band edit** (size/mtime/hash changed behind the index) and
  refreshes before a read returns — the case a missed proactive signal would otherwise lose.

## Manual CLI check
- Index a repo; edit one file out of band; a subsequent query that reads that file reflects the edit
  (on-access), while an unrelated query does **no** tree walk.

## Done when
- tests green · native green · `reindexOne` + on-access backstop both observe an out-of-band edit, with
  no whole-tree scan on the common query path.

## Outcome (built)
Shipped as scoped. Choices made:
- **`SourceSet` now lives on the `FileTable` row** (Entry gains the tag; format `VERSION` 1→2) — not
  only denormalized onto each `Symbol.flags`. This lets `reindexOne(path)` recover a file's MAIN/TEST
  tag from the file row alone (the parse *input*), instead of reverse-engineering it from already-indexed
  symbols. The symbol-flags copy stays (queries are symbol-centric and self-contained); the two can't
  drift (a MAIN↔TEST move is also a path change → delete+add → fresh row). *No migration: the single
  local `.jcma` is dropped & rebuilt.*
- **New `FreshnessGuard`** owns the in-session `FileTable` (a check is a map lookup, not a disk reload) +
  open store + indexer; `reindexOne` persists the table only when a row actually changed (the common
  nothing-changed query writes nothing). Branches mirror `Reconciler` NEW/SUSPECT, plus
  **tombstone-on-missing** (a queried file deleted out of band → tombstone its id + drop the row).
- **`FreshnessSource`** = thin producer seam (`drainChanged()` + `none()`); M1 ships `none()` (on-access
  only). Drop-in slot for a later watcher/journal — `ensureFresh` drains it then validates the read file.
- Lightweight metrics counters (`freshness.checked/reindexed/mtimeLie/tombstoned`) per the observability
  convention.
- **Not wired into a query CLI yet** — there is no Tier-2 query path in M1; `ensureFresh` is consumed by
  task-10. The manual CLI check (query reflects an out-of-band edit) is therefore exercised by the
  `FreshnessGuardTest` unit tests, not a CLI command, until task-10 wires it in.
