package jcma.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jcma.mcp.json.JsonReader;
import jcma.mcp.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link FileCallLog} contract: one parseable JSON line per MCP call, a size-bounded file that
 * rotates to a single backup, and a zero-work {@link CallLog#noop()} path.
 */
class CallLogTest {

    @Test
    void writesOneParseableJsonLinePerCall(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("serve.log");
        CallLog cl = FileCallLog.open(log, 1 << 20);
        cl.record("find_references", "{\"target\":\"Foo\"}", true, 2_500_000L, 128);
        cl.record("search_symbols", "{\"query\":\"bar\"}", false, 1_000_000L, 0);

        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), "one line per call");

        JsonValue first = JsonReader.parse(lines.get(0));
        assertEquals("find_references", first.get("tool").asString());
        assertTrue(first.get("ok").asBoolean());
        assertEquals(128, first.get("resp_bytes").asInt());
        assertEquals(2.5, first.get("latency_ms").asDouble(), 1e-6, "nanos rendered as ms");
        assertNotNull(first.get("request"), "the request summary is recorded");
        assertNotNull(first.get("ts"), "each record is timestamped");

        JsonValue second = JsonReader.parse(lines.get(1));
        assertEquals("search_symbols", second.get("tool").asString());
        assertFalse(second.get("ok").asBoolean(), "a tool failure logs ok=false");
    }

    @Test
    void rotatesWhenTheFileExceedsItsMax(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("serve.log");
        CallLog cl = FileCallLog.open(log, 512); // tiny cap to force rotation
        for (int i = 0; i < 200; i++) {
            cl.record("find_definition", "{\"n\":" + i + "}", true, 1_000_000L, 64);
        }
        assertTrue(Files.size(log) <= 1024,
                "the live log stays near its max, not grown without bound (was " + Files.size(log) + ")");
        assertTrue(Files.exists(dir.resolve("serve.log.1")),
                "the rolled-out segment is kept as a .1 backup");
    }

    @Test
    void noopWritesNothingAndNeverThrows() {
        CallLog.noop().record("health", "{}", true, 0L, 2);
        // no file, no throw — the disabled path is a no-op
    }
}
