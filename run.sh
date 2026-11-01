#!/bin/bash
# Writes the equipment changes of the NSA-app database to the Drive workbooks (run by cron, as www-data).
#
# The converter compares the database (db.file of $CONF_FILE) with the snapshot of the last successful run
# (snapshot.file) and uploads only what changed; a workbook that could not be updated is retried at the next
# run by the converter itself (its snapshot does not advance). Only one run at a time: a run started while
# another is in progress stops immediately.

set -u

readonly CONF_FILE="/etc/NSA/GxlsxNSAstockDB_converter.conf"
readonly JAR="/opt/NSA/GxlsxNSAstockDBconverter-latest.jar"
readonly LOCK_FILE="/var/lib/nsa/drive/converter.lock"

log() {
    echo "$(date '+%F %T') $*"
}

main() {
    cd "$(dirname "$0")" || exit 1     # relative paths of the configuration (apiKey) start from here

    # Run lock (fd 9), held until the script ends
    if ! { exec 9>"$LOCK_FILE"; } 2>/dev/null; then
        log "cannot open the lock file $LOCK_FILE (directory missing or no write access)"
        exit 1
    fi
    if ! flock -n 9; then
        log "previous run still in progress: skipped"
        exit 0
    fi

    local code=0
    java -jar "$JAR" --conf "$CONF_FILE" || code=$?
    [ "$code" -eq 0 ] || log "converter exit code $code"
    exit "$code"
}

main "$@"
