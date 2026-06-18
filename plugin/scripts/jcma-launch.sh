#!/usr/bin/env bash
# MCP server entrypoint. Resolves the jcma binary, self-healing if the SessionStart bootstrap
# was skipped (e.g. a mid-session update) or its cache was cleared, then exec's it with the given
# args (e.g. "serve"). $JCMA_BINARY overrides everything — point it at a dev build.
#
# The plugin root/data dirs come from the env the plugin config passes in (CLAUDE_PLUGIN_ROOT and
# CLAUDE_PLUGIN_DATA, set explicitly in .mcp.json — we don't assume they're auto-exported).
set -euo pipefail

if [ -n "${JCMA_BINARY:-}" ]; then
  exec "$JCMA_BINARY" "$@"
fi

root="${CLAUDE_PLUGIN_ROOT:?CLAUDE_PLUGIN_ROOT not set}"
data="${CLAUDE_PLUGIN_DATA:?CLAUDE_PLUGIN_DATA not set}"
bin="$data/bin/jcma"

if [ ! -x "$bin" ]; then
  # Keep stdout clean — bootstrap chatter must not leak into the JSON-RPC stream.
  "$root/scripts/jcma-bootstrap.sh" "$root" "$data" 1>&2
fi

exec "$bin" "$@"
