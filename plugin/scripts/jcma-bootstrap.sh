#!/usr/bin/env bash
# SessionStart hook: ensure the jcma binary is installed in the plugin's persistent
# data dir ($CLAUDE_PLUGIN_DATA), downloading it from GitHub Releases on first run or
# whenever the plugin version changes. Idempotent — a no-op once the right tag is in
# place. Mirrors the documented node_modules SessionStart bootstrap pattern.
set -euo pipefail

# Claude Code exports these to hook + MCP subprocesses (see plugins-reference).
root="${CLAUDE_PLUGIN_ROOT:?CLAUDE_PLUGIN_ROOT not set}"
data="${CLAUDE_PLUGIN_DATA:?CLAUDE_PLUGIN_DATA not set}"

manifest="$root/.claude-plugin/plugin.json"

# Single source of truth for which release to fetch: the plugin's own version + repo.
field() { sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$manifest" | head -n1; }
version="$(field version)"
repo_url="$(field repository)"
repo="${repo_url#https://github.com/}"; repo="${repo%.git}"

# Both overridable for testing / forks / dev builds.
tag="${JCMA_RELEASE_TAG:-v$version}"
repo="${JCMA_RELEASE_REPO:-$repo}"
base="https://github.com/$repo/releases/download/$tag"

marker="$data/installed-tag"
bin="$data/bin/jcma"

# Already installed for this tag? Done.
if [ -x "$bin" ] && [ "$(cat "$marker" 2>/dev/null || true)" = "$tag" ]; then
  exit 0
fi

# Pick the release asset for this platform. Native targets get the instant-start binary;
# everything else falls back to the portable JVM dist (needs a JDK 25+ on PATH).
os="$(uname -s)"; arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64|Linux/amd64)     asset="jcma-linux-amd64.tar.gz"; kind=native ;;
  Darwin/arm64|Darwin/aarch64)  asset="jcma-macos-arm64.tar.gz"; kind=native ;;
  *)                            asset="jcma-jvm.zip";            kind=jvm    ;;
esac

echo "jcma: installing $tag for $os/$arch ($asset)…" >&2

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Download the asset, then verify it against the published SHA256SUMS.
curl -fsSL -o "$tmp/$asset" "$base/$asset"
if curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" 2>/dev/null; then
  expected="$(awk -v a="$asset" '$2==a || $2=="*"a {print $1}' "$tmp/SHA256SUMS" | head -n1)"
  if [ -n "$expected" ]; then
    actual="$(sha256 "$tmp/$asset" | awk '{print $1}')"
    if [ "$expected" != "$actual" ]; then
      echo "jcma: checksum mismatch for $asset (expected $expected, got $actual)" >&2
      exit 1
    fi
  else
    echo "jcma: warning — $asset absent from SHA256SUMS; skipping integrity check" >&2
  fi
else
  echo "jcma: warning — no SHA256SUMS published for $tag; skipping integrity check" >&2
fi

# Stage an executable at $data/bin/jcma. Writes go to *.new then atomically rename so a
# crashed download never leaves a half-written binary the launcher would exec.
mkdir -p "$data/bin"
if [ "$kind" = native ]; then
  tar -xzf "$tmp/$asset" -C "$tmp"
  install -m 0755 "$tmp/jcma" "$bin.new"
  mv -f "$bin.new" "$bin"
else
  command -v java >/dev/null 2>&1 || {
    echo "jcma: the JVM fallback needs 'java' (JDK 25+) on PATH; none found" >&2; exit 1; }
  rm -rf "$data/jvm.new"
  unzip -q "$tmp/$asset" -d "$data/jvm.new"
  rm -rf "$data/jvm"; mv "$data/jvm.new" "$data/jvm"
  distbin="$(ls -d "$data"/jvm/jcma-*/bin/jcma 2>/dev/null | head -n1 || true)"
  [ -n "$distbin" ] || { echo "jcma: could not locate launcher inside $asset" >&2; exit 1; }
  printf '#!/usr/bin/env bash\nexec "%s" "$@"\n' "$distbin" > "$bin.new"
  chmod 0755 "$bin.new"; mv -f "$bin.new" "$bin"
fi

echo "$tag" > "$marker"
echo "jcma: installed $tag → $bin" >&2
