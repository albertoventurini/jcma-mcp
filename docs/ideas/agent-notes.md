# Idea: agent-authored notes on symbols

**Status:** candidate feature, under consideration — *not* a settled decision or scheduled
milestone. Captured 2026-06-09. Does **not** touch M0 (the current gate). Would be its own
milestone, post-M1.

## Problem

When an agent reads a Java file to understand it (exploration mode), it derives genuinely
valuable understanding — "this class is the retry coordinator," "`flush()` is not idempotent,"
"`lock` guards `pending`, not `inflight`." That understanding is thrown away at the next context
window and must be re-derived from scratch. Re-deriving understanding is a dominant cost of agent
codebase work.

The idea: give the agent a tool to attach arbitrary free-form **notes** to code symbols, and to
recall them later when it navigates back to (or near) those symbols.

## Why this belongs in jcma specifically

A flat agent-memory store (e.g. a scratch `MEMORY.md`) can remember facts, but only a
*code-intelligence engine* can offer the thing that makes notes safe: **symbol-anchored memory
with automatic freshness riding the same filesystem-driven invalidation pipeline** the navigation
index already has. The engine knows the moment an anchored symbol drifts; a flat store does not.
That is the entire justification for putting notes in jcma rather than the harness.

## The key distinction: notes are not navigation facts

Navigation facts (find_references, definitions, the edge graph) are *derived from* the code. When
code changes you re-derive them — the code is ground truth, the index is a rebuildable cache.

A note is **not** derived from code. It is the agent's *judgment about* code. When the code
changes you can *detect* that the anchored symbol changed, but you **cannot re-derive the note**.

A stale note that is confidently wrong is worse than no note: "this method is thread-safe" → the
synchronization is later stripped → the note now actively misleads the next agent, with the
authority of "I figured this out last time." Navigation self-corrects on re-read; a stale note
launders a false belief forward.

## Freshness model: delete-on-change (not staleness-flagging)

Rather than build content-hash/provenance/"trust this stale note?" machinery, **delete a note
when its anchored symbol changes.** This sidesteps the trust problem entirely and reuses the
existing pipeline verbatim:

> changed symbol → node-diff → reverse-edge walk → tombstone any note node hit

(Same validate-on-read / fs-freshness + node-diff + reverse-edge-walk used for navigation
invalidation.)

Refinements so deletion is a scalpel, not a shotgun:

1. **Symbol granularity, not file.** Deleting all notes when the *file* changes is too coarse — a
   one-line tweak to one method would nuke the class-purpose note and every other method's note.
   Delete only notes whose *anchored symbols* actually changed. A note survives any edit that
   doesn't touch its own anchors.
2. **A cluster note dies if *any one* anchor changes.** The note body is one free-form blob
   describing A+B+C as a system; you can't surgically excise the sentences about B when B changes,
   and you can't tell which sentences those are. So a cluster note is only as fresh as its
   most-changed anchor → delete whole.
3. **The lossiness is a feature.** Objection: agents edit constantly, so notes die fast. But notice
   *which* notes die — the ones on code in active flux, i.e. exactly the code whose cached
   understanding you shouldn't trust mid-edit. The notes that survive are on **stable symbols**,
   which is exactly where cached understanding is worth keeping. Delete-on-change is a free natural
   filter retaining notes only where they're trustworthy. The aggression is the point.

## Graph model: notes as reified nodes, clustered

- A note is **its own graph node**, with edges to **one or more** anchor symbols (monikers).
- **Clusters, not one-note-per-symbol.** Understanding spans multiple types ("the auth flow is
  these three classes + this interface"); one-note-per-type is too rigid and fights how
  understanding actually clusters.
- Navigable **both directions**: symbol → its notes (recall) and note → its symbols (the deletion
  walk). Drops straight into the existing both-edge-directions / walk-the-graph index design. The
  deletion check *is* the reverse-edge walk already done for invalidation.
- Usage convention (not engine-enforced): cluster by *conceptual* cohesion. A note spanning many
  volatile symbols will die constantly; keep behavioral claims narrow, purpose claims broad. This
  doesn't need to be settled until the tool exists and real clustering behavior can be observed.

## Open concern: notes make the index authoritative

Notes are the first **write traffic from the agent** into an otherwise rebuildable, read-only
index. They cannot be `rm -rf`'d and re-derived. So the note store probably wants to be a
physically **separate, durable segment** with its own durability story, not interleaved with the
regenerable index segments. Flag now so the LSM layout doesn't bake in "everything is rebuildable."

## Sequencing / scope guard

- Post-M1, its own milestone. Does not affect M0.
- A separate subsystem that **reuses** symbol identity + freshness but does **not** contaminate the
  navigation core — consistent with the settled "no opinions in core" stance. The core still states
  only ground truth; notes are explicitly the agent's own assertions, labeled as such.
