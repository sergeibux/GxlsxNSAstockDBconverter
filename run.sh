#!/bin/bash
# Applies the pending NSA-app changes to the Drive workbooks (run by cron).
#
#   run.sh [compile]      compile: rebuild the jar (mvn package) before running it
#
# Changes file: key changes.file of $CONF_FILE (default $DEFAULT_CHANGES_FILE).
# - It is renamed before being read: changes written by the server during the run go to a new file.
#   A writer must hold an exclusive lock on "<changes file>.lock" while appending (flock).
# - Converter exit code 0: the processed changes are deleted. Any other code: they are kept in
#   "<changes file>.processing" and replayed first at the next run (applying a change twice gives the same result).
# - Only one run at a time: a run started while another is in progress stops immediately.

set -u

readonly CONF_FILE="/etc/NSA/GxlsxNSAstockDB_converter.conf"
readonly DEFAULT_CHANGES_FILE="/opt/NSA/changes.jsonl"
readonly JAR="target/GxlsxNSAstockDBconverter-1.0-SNAPSHOT.jar"

log() {
    echo "$(date '+%F %T') $*"
}

# Value of a key of the conf file (last occurrence), or the given default.
conf_value() {
    local key=$1 default=$2 value
    value=$(sed -n "s/^${key//./\\.}=//p" "$CONF_FILE" 2>/dev/null | tail -n 1)
    echo "${value:-$default}"
}

# Holds the run lock (fd 9) for the whole script; returns 1 if another run holds it.
take_run_lock() {
    exec 9>"$CHANGES.run.lock"
    flock -n 9
}

# Moves the changes written since the last run after those a failed run left in $WORK.
# The rename happens under the writers' lock, so no line is lost or cut.
collect_new_changes() {
    [ -s "$CHANGES" ] || return 0
    (
        flock 8
        mv "$CHANGES" "$CHANGES.new" && cat "$CHANGES.new" >> "$WORK" && rm "$CHANGES.new"
    ) 8>"$CHANGES.lock"
}

run_converter() {
    java -jar "$JAR" "$WORK"
}

main() {
    cd "$(dirname "$0")" || exit 1

    CHANGES=$(conf_value changes.file "$DEFAULT_CHANGES_FILE")
    WORK="$CHANGES.processing"

    if ! take_run_lock; then
        log "previous run still in progress: skipped"
        exit 0
    fi

    collect_new_changes || { log "cannot collect the new changes from $CHANGES"; exit 1; }
    if [ ! -s "$WORK" ]; then
        rm -f "$WORK"
        exit 0      # nothing to do
    fi

    if [ "${1:-}" = "compile" ]; then
        mvn package || exit 1
    fi

    local code=0
    run_converter || code=$?

    if [ "$code" -eq 0 ]; then
        rm "$WORK"
    else
        log "converter exit code $code: $(wc -l < "$WORK") change(s) kept for the next run in $WORK"
    fi
    exit "$code"
}

main "$@"
