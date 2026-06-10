package jcma.obs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import jcma.mcp.json.JsonValue;
import jcma.mcp.json.JsonValue.JsonObject;
import jcma.mcp.json.JsonWriter;

/**
 * A {@link CallLog} that appends one JSON object per line to a size-bounded file, rotating to a
 * single {@code <file>.1} backup when it grows past {@code maxBytes}. Dependency-free and
 * native-image clean — no logging framework — reusing {@code jcma.mcp.json} for correct escaping,
 * matching the rest of {@code jcma.obs}.
 *
 * <p>Best-effort: an {@link IOException} while writing is swallowed (the disk failing must never
 * break the MCP serve loop), and {@link #record} is {@code synchronized} so the one writer stays
 * line-atomic even if a future caller logs from several threads.
 */
public final class FileCallLog implements CallLog {

    private final Path file;
    private final Path backup;
    private final long maxBytes;
    private boolean parentReady;

    private FileCallLog(Path file, long maxBytes) {
        this.file = file;
        this.backup = file.resolveSibling(file.getFileName() + ".1");
        this.maxBytes = maxBytes;
    }

    /** Open (creating parent dirs lazily on first write) a rotating call log at {@code file}. */
    public static FileCallLog open(Path file, long maxBytes) {
        return new FileCallLog(file, maxBytes);
    }

    @Override
    public synchronized void record(String tool, String request, boolean ok,
            long latencyNanos, int responseBytes) {
        JsonObject rec = JsonObject.empty()
                .with("ts", JsonValue.of(Instant.now().toString()))
                .with("tool", JsonValue.of(tool))
                .with("request", JsonValue.of(request == null ? "" : request))
                .with("ok", JsonValue.of(ok))
                .with("latency_ms", JsonValue.of(roundMillis(latencyNanos)))
                .with("resp_bytes", JsonValue.of(responseBytes));
        byte[] line = (JsonWriter.write(rec) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            ensureParent();
            rotateIfFull();
            Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Observability is best-effort: a failed write must not break the serve loop.
        }
    }

    /** nanos → ms, rounded to 3 decimals so the line stays tidy (2_500_000ns → 2.5). */
    private static double roundMillis(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    private void ensureParent() throws IOException {
        if (!parentReady) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            parentReady = true;
        }
    }

    /** Roll the live file out to the single {@code .1} backup once it reaches the cap. */
    private void rotateIfFull() throws IOException {
        if (Files.exists(file) && Files.size(file) >= maxBytes) {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
