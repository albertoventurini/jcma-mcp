package jcma.index;

import java.nio.file.Path;

/**
 * A source root directory paired with the {@link SourceSet} its files belong to — the tagged unit
 * produced by workspace discovery and consumed by {@link Indexer#indexRepo}. Lives in {@code
 * jcma.index} (not {@code jcma.workspace}) so the indexer needn't depend on the workspace package.
 *
 * @param dir the root directory whose {@code .java} files are indexed
 * @param set whether those files are production ({@link SourceSet#MAIN}) or test ({@link SourceSet#TEST})
 */
public record SourceRoot(Path dir, SourceSet set) {}
