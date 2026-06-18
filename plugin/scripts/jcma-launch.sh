#!/usr/bin/env bash
# MCP server entrypoint for the plugin. Resolves the jcma binary, self-healing if the
# SessionStart bootstrap was skipped or its cache was cleared, then exec's it with the
# given args (e.g. "serve"). $JCMA_BINARY overrides everything — point it at a dev build.
set -euo pipefail

if [ -n "${JCMA_BINARY:-}" ]; then
  exec "$JCMA_BINARY" "$@"
fi

data="${CLAUDE_PLUGIN_DATA:?CLAUDE_PLUGIN_DATA not set}"
bin="$data/bin/jcma"

if [ ! -x "$bin" ]; then
  "${CLAUDE_PLUGIN_ROOT:?CLAUDE_PLUGIN_ROOT not set}/scripts/jcma-bootstrap.sh"
fi

exec "$bin" "$@"
