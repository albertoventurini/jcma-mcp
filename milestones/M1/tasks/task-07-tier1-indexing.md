# M1 · Task 07 — Tier-1 structural indexing — **MERGED INTO TASK 06**

> **Merged 2026-06-06** into [task-06](task-06-lsm-overlay-compaction.md). A working
> `jcma index <repo>` needs both the extractor (`Indexer`) and the store (`LsmStore`) at once, so
> the Tier-1 parse-only indexing scope (`Indexer.java`, `index`/`outline` CLI, the SpikeB/SpikeA
> ports) now lives there as phase **P2**. This stub is kept so cross-references and the task
> numbering stay stable; downstream tasks (e.g. task-08) depend on "Indexer + LsmStore" = task-06.
