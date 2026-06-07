package jcma.resolve;

import jcma.index.Range;

import java.nio.file.Path;

/**
 * One confirmed reference site — a use of the target, context-bearing so no follow-up read is needed
 * (PRD §6): the file + range of the use and the source snippet at that line.
 *
 * @param fileId  the file the use occurs in (graph file id)
 * @param file    the resolved path of that file
 * @param range   the use-site source range
 * @param snippet the trimmed source line at the use (ported from M0 {@code snippetOf})
 */
public record Ref(int fileId, Path file, Range range, String snippet) {}
