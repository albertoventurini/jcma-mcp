# M2 · Task 1 — Hand-rolled JSON reader/writer/value (native-clean)

> The wire foundation for the MCP server: a tiny, dependency-free JSON layer in jcma-owned types.

## Decision locked (overview)
**Hand-rolled, not the MCP SDK / Jackson** — keeps the native-image surface clean (no reflection
metadata), and our messages are tiny. This task builds the JSON; task 2 builds the protocol on it.

## Prerequisites (read first, fresh session)
- **Read:** M2 overview ; PRD §6 (MCP transport — "prefer a minimal hand-rolled … stdio layer").
- **Reference, don't extend:** `milestones/m0-spike/src/main/java/m0/SpikeC.java` —
  `jsonStringField`/`jsonRawField` are the *cautionary* example (substring hacks with no escaping/
  nesting): this task exists because `tools/call` carries a nested `arguments` object they can't parse.

## Protocol (test-first; full version in the overview)
Write failing tests + fixtures → **STOP for review** → implement → verify.

## Scope — files to create
- `src/main/java/jcma/mcp/json/JsonValue.java` — a sealed value model: object (ordered
  `LinkedHashMap<String,JsonValue>`), array, string, number, bool, null. Typed accessors
  (`asObject`, `asString`, `optString`, `asInt`, …) that fail cleanly on type mismatch.
- `src/main/java/jcma/mcp/json/JsonReader.java` — a correct recursive-descent parser: full string
  escaping (`\" \\ \/ \b \f \n \r \t \uXXXX`), nested objects/arrays, ints/decimals/exponents,
  `true`/`false`/`null`, surrogate pairs; rejects trailing garbage; bounded depth (reject pathological
  nesting). One JSON document per call (the loop splits on newlines in task 2).
- `src/main/java/jcma/mcp/json/JsonWriter.java` — minimal serializer with correct escaping; stable
  key order (insertion order) for deterministic, testable output; a `String` and an
  `Appendable`/`StringBuilder` sink. No pretty-printing (token economy).

## Tests (red-first)
- `JsonReaderTest`: round-trip a corpus incl. escapes, unicode/surrogates, nested
  objects/arrays, numbers (int/neg/decimal/exp), whitespace; **malformed inputs throw** a clear
  parse error (truncated, trailing comma, bad escape, unterminated string, trailing garbage); a deep
  but legal nest parses, a pathological one is rejected.
- `JsonWriterTest`: every escapable char round-trips; output re-parses to an equal value
  (writer↔reader fixed point); key order preserved.
- `JsonRoundTripTest`: real MCP message shapes (an `initialize` request, a `tools/call` with a
  nested `arguments` object, a tool result) survive read→write→read unchanged.

## Manual check
- A tiny `main`/test harness (or reuse a unit) printing a parsed-then-reserialized MCP message —
  eyeball that the `arguments` object survives intact.

## Done when
- tests green · `nativeCompile` green (no new reflection config needed — this is the point) ·
  reader/writer are a fixed point over the MCP message corpus.

## Decisions to settle / record
- Number representation (keep a raw `String` + lazy `asInt`/`asLong`/`asDouble`, vs. eager parse) —
  recommend lazy raw to avoid precision loss on ids; record the choice.
