#!/usr/bin/env bash
#
# qa-deps-accuracy.sh <repo-path>
#
# Measure jcma's dependency-resolution accuracy + performance on any compilable Maven Java repo,
# against a javac AST oracle. For every declared type it compares two relations — supertypes
# (extends/implements) and outgoing type references — resolved by `jcma resolve-file` vs the oracle,
# and writes a markdown report + a per-type diff TSV under qa/out/.
#
# Idempotent and deterministic: a fresh temp XDG_CACHE_HOME per run (no live-server index-lock
# conflict), re-running yields identical results. First cut targets Maven; Gradle is a follow-up.
#
# Env: JCMA_BINARY (default: build/native/nativeCompile/jcma). Needs `javac`/`java` 25+ and `mvn`
# only transitively (jcma resolves + persists the classpath at index time; the oracle reuses it).
set -euo pipefail
export LC_ALL=C   # stable number formatting in jcma's output (comma grouping, dot decimal)

# --- locate self / repo-under-test ------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JCMA_REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
QA_OUT="$JCMA_REPO/qa/out"
JCMA_BINARY="${JCMA_BINARY:-$JCMA_REPO/build/native/nativeCompile/jcma}"

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <repo-path>" >&2
  exit 2
fi
REPO="$(cd "$1" && pwd)"
NAME="$(basename "$REPO")"
if [[ ! -x "$JCMA_BINARY" ]]; then
  echo "qa: jcma binary not found/executable at $JCMA_BINARY (build it: ./gradlew nativeCompile)" >&2
  exit 1
fi

# --- source roots (main + test) ---------------------------------------------------------------
ROOTS=()
for r in src/main/java src/test/java; do
  [[ -d "$REPO/$r" ]] && ROOTS+=("$REPO/$r")
done
if [[ ${#ROOTS[@]} -eq 0 ]]; then
  echo "qa: no src/main/java or src/test/java under $REPO" >&2
  exit 1
fi

WORK="$(mktemp -d "/tmp/qa-${NAME}.XXXXXX")"
export XDG_CACHE_HOME="$WORK/cache"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$QA_OUT"
ORACLE_TSV="$QA_OUT/$NAME-oracle.tsv"
JCMA_TSV="$QA_OUT/$NAME-jcma.tsv"
REPO_TYPES="$WORK/repo-types.txt"
PERF="$WORK/perf.properties"
REPORT="$QA_OUT/$NAME-report.md"
DIFFS="$QA_OUT/$NAME-diffs.tsv"

echo "qa: repo=$REPO  binary=$JCMA_BINARY  roots=${ROOTS[*]}"

# --- 1. cold index (this is where jcma's resolution-time classpath + symbols are produced) -----
echo "qa: [1/5] indexing (cold) …"
INDEX_T0=$(date +%s.%N)
INDEX_OUT="$("$JCMA_BINARY" -C "$REPO" index 2>&1)"
INDEX_T1=$(date +%s.%N)
echo "$INDEX_OUT" | sed 's/^/    /'
INDEX_WALL=$(awk "BEGIN{printf \"%.2f\", $INDEX_T1-$INDEX_T0}")
IDX="$(ls -d "$XDG_CACHE_HOME"/jcma/index/* | head -1)"
CLASSPATH="$(cat "$IDX/classpath.txt" 2>/dev/null || echo "")"

# Parse jcma's own reported numbers (LC_ALL=C → "12,485 LOC in 0.37s", "… 1438 symbols, …").
SYMBOLS=$(sed -n 's/.*, \([0-9,]*\) symbols.*/\1/p' <<<"$INDEX_OUT" | tr -d ',')
LOC=$(sed -n 's/.* \([0-9,]*\) LOC in .*/\1/p' <<<"$INDEX_OUT" | tr -d ',')
INDEX_INTERNAL=$(sed -n 's/.* LOC in \([0-9.]*\)s .*/\1/p' <<<"$INDEX_OUT")
CP_JARS=$(sed -n 's/classpath: resolved \([0-9]*\) jar.*/\1/p' <<<"$INDEX_OUT")
LOC_PER_S=$(awk "BEGIN{ if (${INDEX_INTERNAL:-0}>0) printf \"%d\", ${LOC:-0}/${INDEX_INTERNAL}; else print 0 }")

# --- 2. repo-declared type FQNs (authoritative intra-repo partition) ---------------------------
echo "qa: [2/5] enumerating repo types …"
"$JCMA_BINARY" index-dump --symbols "$IDX" \
  | awk '$2 ~ /^(CLASS|INTERFACE|ENUM|RECORD|ANNOTATION)$/ { print $NF }' \
  | sort -u > "$REPO_TYPES"
echo "    $(wc -l < "$REPO_TYPES") repo type(s)"

# --- 3. javac AST oracle ----------------------------------------------------------------------
echo "qa: [3/5] running javac oracle …"
java "$JCMA_REPO/qa/oracle/Oracle.java" "$ORACLE_TSV" "$CLASSPATH" "${ROOTS[@]}"

# --- 4. jcma resolve-file over every source file (timed per file) -----------------------------
echo "qa: [4/5] resolving with jcma (per file) …"
: > "$JCMA_TSV"
TIMES_FILE="$WORK/times.txt"; : > "$TIMES_FILE"
FILE_COUNT=0
RESOLVE_T0=$(date +%s.%N)
while IFS= read -r -d '' f; do
  T0=$(date +%s.%N)
  "$JCMA_BINARY" resolve-file "$f" >> "$JCMA_TSV" 2>/dev/null || true
  T1=$(date +%s.%N)
  awk "BEGIN{printf \"%.1f\n\", ($T1-$T0)*1000}" >> "$TIMES_FILE"
  FILE_COUNT=$((FILE_COUNT+1))
done < <(find "${ROOTS[@]}" -name '*.java' -print0 | sort -z)
RESOLVE_T1=$(date +%s.%N)
RESOLVE_TOTAL=$(awk "BEGIN{printf \"%.1f\", $RESOLVE_T1-$RESOLVE_T0}")

read -r RES_MEAN RES_P95 RES_MAX < <(sort -n "$TIMES_FILE" | awk '
  { a[NR]=$1; sum+=$1 }
  END {
    if (NR==0) { print "0 0 0"; exit }
    p95 = a[int(NR*0.95) > 0 ? int(NR*0.95) : 1];
    printf "%.1f %.1f %.1f\n", sum/NR, p95, a[NR]
  }')

# --- 5. compare + report ----------------------------------------------------------------------
echo "qa: [5/5] comparing + writing report …"
cat > "$PERF" <<EOF
repo=$NAME
symbols=$SYMBOLS
loc_total=$LOC
loc_per_s=$LOC_PER_S
index_wall_s=$INDEX_WALL
index_internal_s=$INDEX_INTERNAL
classpath_jars=$CP_JARS
classpath_resolve_s=$(awk "BEGIN{printf \"%.2f\", $INDEX_WALL-${INDEX_INTERNAL:-0}}")
resolve_total_s=$RESOLVE_TOTAL
resolve_files=$FILE_COUNT
resolve_mean_ms=$RES_MEAN
resolve_p95_ms=$RES_P95
resolve_max_ms=$RES_MAX
EOF

java "$JCMA_REPO/qa/compare/Compare.java" \
  "$ORACLE_TSV" "$JCMA_TSV" "$REPO_TYPES" "$PERF" "$REPORT" "$DIFFS" "$NAME"

echo
echo "qa: done → $REPORT"
echo "qa:        $DIFFS  (per-type divergences)"
