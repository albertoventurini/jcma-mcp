package jcma.resolve;

import jcma.index.Range;

import java.nio.file.Path;

/**
 * One <b>unconfirmed tail</b> entry (M0 Spike A requirement): a candidate occurrence whose simple
 * name matched the find-references target but which failed to resolve, so it can be neither confirmed
 * as a reference nor silently dropped. Carried explicitly with its {@link FailureClassifier.Cause}.
 *
 * @param fileId  the file the candidate occurs in
 * @param file    the resolved path of that file
 * @param range   the candidate use-site range
 * @param snippet the trimmed source line at the candidate
 * @param cause   why resolution failed (best-effort bucket)
 */
public record UnconfirmedRef(int fileId, Path file, Range range, String snippet, FailureClassifier.Cause cause) {}
