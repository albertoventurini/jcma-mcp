# Auto-download the jcma binary from the plugin

**Status:** implemented (2026-06-18). `/plugin install jcma@jcma` now delivers a working binary
with no manual download step. This note records how it works and why the earlier blockers fell.

## How it works

The plugin lives in [`plugin/`](../../plugin) (the marketplace `source`), so its install
footprint is just that dir — not the whole repo. It ships no binary; it ships the wiring to fetch
one, using two Claude Code plugin primitives:

- **`SessionStart` hook** ([`plugin/hooks/hooks.json`](../../plugin/hooks/hooks.json)) runs
  [`scripts/jcma-bootstrap.sh`](../../plugin/scripts/jcma-bootstrap.sh) when a session begins. It
  detects OS/arch, picks the release asset (`jcma-linux-amd64.tar.gz`, `jcma-macos-arm64.tar.gz`,
  else the `jcma-jvm.zip` fallback), downloads it, **verifies it against the published
  `SHA256SUMS`**, and stages an executable at `$CLAUDE_PLUGIN_DATA/bin/jcma`. It's idempotent: a
  no-op once the marker file matches the target tag, re-running only on first install or a version
  change (the documented `node_modules` bootstrap pattern). Writes go through `*.new` + atomic
  rename so an interrupted download never leaves a half-written binary.
- **`.mcp.json`** ([`plugin/.mcp.json`](../../plugin/.mcp.json)) points the MCP server `command`
  at [`scripts/jcma-launch.sh`](../../plugin/scripts/jcma-launch.sh), which exec's the cached
  binary — self-healing by calling the bootstrap if the cache is missing, and honoring a
  `$JCMA_BINARY` override for dev builds.

`$CLAUDE_PLUGIN_DATA` is a per-plugin dir that **survives plugin updates**, so the binary is
downloaded once and reused; `$CLAUDE_PLUGIN_ROOT` (where the scripts live) is treated as ephemeral.
Both paths are passed in **explicitly** — `.mcp.json` lists them in the server's `env` block and
the hook passes them as args — because they are *not* reliably auto-exported to the subprocess
(matching the documented `node_modules` example, which also references `${CLAUDE_PLUGIN_DATA}` in
`env`). Relying on auto-export instead silently broke the launcher in every repo where it wasn't
set.

The binary release tag is **pinned in the bootstrap** (`tag="${JCMA_RELEASE_TAG:-v0.2.x}"`),
deliberately decoupled from the plugin's own `version`: a plugin-wiring fix can ship as a new
plugin version without re-releasing an identical binary. Bump the pin only when the binary itself
changes. `repository` is read from `plugin.json`; both knobs are overridable via
`JCMA_RELEASE_TAG` / `JCMA_RELEASE_REPO`.

## Why the earlier blockers fell

The original deferral (2026-06-14) named two prerequisites; both are now met:

1. **Stable public download URLs.** `release.yml` publishes per-OS/arch native tarballs + the JVM
   zip as GitHub Release assets, plus a `SHA256SUMS` over all of them (added alongside this work).
   No token or unexpired CI run needed — `albertoventurini/jcma` redirects to the renamed repo and
   `curl -L` resolves the asset.
2. **A delivery mechanism.** Claude Code's plugin platform supplies it directly (`SessionStart` +
   `${CLAUDE_PLUGIN_DATA}` + `${CLAUDE_PLUGIN_ROOT}` in `.mcp.json`), so no bespoke launcher
   protocol or `curl | sh` installer was needed.

## Known limitations

- **The pinned binary tag must point at a real release.** The bootstrap fetches the tag pinned in
  the script; when bumping it, cut the matching `v<tag>` release first, or the download 404s.
- **Windows is JVM-only and untested here.** The bash launcher covers Linux/macOS natively and the
  JVM fallback on other Unix arches. Native Windows wiring (a `.cmd`/`.ps1` companion) is not built.
