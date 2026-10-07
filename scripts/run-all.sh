#!/usr/bin/env bash
# Runs the test plan. Each scenario: 60 s ramp-up + 600 s measurement.
# Scenario 4 and 5 use the protocol given as $BEST (HTTP_1_1 or HTTP_2), chosen from 2 and 3.
set -euo pipefail
cd "$(dirname "$0")/.."
run() { scripts/run-scenario.py "$@" 2>&1 | tee "results/$2.log"; }

case "${1:-all}" in
  base)
    run --name s1-baseline-http1 --profile fast --http-version HTTP_1_1
    run --name s2-target-http1 --profile full --http-version HTTP_1_1
    run --name s3-target-http2 --profile full --http-version HTTP_2
    ;;
  stress)
    for rate in ${RATES:-600 800 1000}; do
      run --name "s4-stress-${rate}" --profile full --http-version "${BEST:?}" --rate "$rate"
    done
    ;;
  db)
    run --name "s5-db-${BEST:?}" --profile full --http-version "$BEST" --db
    ;;
  jdk)
    # Optional comparison: the JDK java.net.http client instead of the Quarkus REST client.
    run --name c2-target-http1-jdk --client jdk --profile full --http-version HTTP_1_1
    run --name c3-target-http2-jdk --client jdk --profile full --http-version HTTP_2
    ;;
  timeout10)
    # Follow-up: the target scenarios with a 10 s instead of a 30 s timeout per attempt.
    run --name t2-target-http1-timeout10 --profile full --http-version HTTP_1_1 --timeout 10
    run --name t3-target-http2-timeout10 --profile full --http-version HTTP_2 --timeout 10
    ;;
  all)
    "$0" base
    BEST="${BEST:?}" "$0" stress
    BEST="$BEST" "$0" db
    ;;
esac
