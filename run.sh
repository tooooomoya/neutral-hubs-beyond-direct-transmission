#!/bin/bash
#
# Usage:
#   ./run.sh                                   # defaults: seeds 0..29, parallel 10
#   ./run.sh --seeds "0 1 2" --parallel 4
#   ./run.sh --seeds "0 1" -- p_u=0.05 bc_dec=0.995   # tokens after -- go to the simulator
#
# Existing result folders are NEVER deleted by default; a run whose target folder already exists
# aborts with a message (tag()-based folder names make collisions meaningful). To force a redo,
# pass force=true as a simulator token: ./run.sh --seeds "0 1" -- force=true
set -euo pipefail

cleanup() {
    echo "Interrupted. Cleaning up..."
    pkill -f OpinionDynamics || true
    exit 1
}
trap cleanup SIGINT SIGTERM

# ---- defaults ----
TARGET_SEEDS="0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29"
MAX_PARALLEL=10
JAVA_HEAP="2g"
LOGDIR="logs"
CONFIG_TOKENS=""

# ---- arg parsing ----
while [ $# -gt 0 ]; do
    case "$1" in
        --seeds)    TARGET_SEEDS="$2"; shift 2 ;;
        --parallel) MAX_PARALLEL="$2"; shift 2 ;;
        --heap)     JAVA_HEAP="$2"; shift 2 ;;
        --)         shift; CONFIG_TOKENS="$*"; break ;;
        *) echo "Unknown option: $1 (use --seeds, --parallel, --heap, -- key=value...)"; exit 1 ;;
    esac
done

# ---- compile (shell-portable: no globstar) ----
echo "Compiling Java sources..."
LIBCP="$(find lib -name '*.jar' 2>/dev/null | tr '\n' ':')bin"
rm -rf bin
mkdir -p bin
find src -name '*.java' > .javafiles.txt
if [ -n "$(find lib -name '*.jar' 2>/dev/null)" ]; then
    javac -cp "$LIBCP" -d bin @.javafiles.txt
else
    javac -d bin @.javafiles.txt
fi
rm -f .javafiles.txt
echo "Compilation finished."

mkdir -p "$LOGDIR"

SEED_COUNT=$(echo $TARGET_SEEDS | wc -w)
echo "Starting simulations for seeds: [ $TARGET_SEEDS ]"
[ -n "$CONFIG_TOKENS" ] && echo "Config tokens: $CONFIG_TOKENS"
echo "Total runs: $SEED_COUNT (parallel: $MAX_PARALLEL)"

run_one() {
    local seed=$1

    local logfile="${LOGDIR}/run_${seed}.log"
    echo "[START] seed=$seed $(date)" > "$logfile"

    java -Xmx${JAVA_HEAP} \
         -XX:+ExitOnOutOfMemoryError \
         -cp "${LIBCP}:bin" \
         dynamics.OpinionDynamics seed="$seed" $CONFIG_TOKENS \
         >> "$logfile" 2>&1

    echo "[END]   seed=$seed $(date)" >> "$logfile"
}

export -f run_one
export LIBCP JAVA_HEAP LOGDIR CONFIG_TOKENS

for seed in $TARGET_SEEDS; do
    echo "$seed"
done | xargs -n 1 -P "$MAX_PARALLEL" bash -c 'run_one "$1"' _

echo "All simulations completed!"
