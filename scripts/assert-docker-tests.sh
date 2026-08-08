#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

reports=(
    "bindings/redis/jedis/v7/target/surefire-reports/TEST-yunqi.zhibei.steward.binding.redis.jedis.v7.Jedis7BindingIntegrationTest.xml"
    "bindings/redis/lettuce/v6/target/surefire-reports/TEST-yunqi.zhibei.steward.binding.redis.lettuce.v6.Lettuce6BindingIntegrationTest.xml"
    "bindings/redis/redisson/v4/target/surefire-reports/TEST-yunqi.zhibei.steward.binding.redis.redisson.v4.Redisson4BindingIntegrationTest.xml"
)

for relative_report in "${reports[@]}"; do
    report="$PROJECT_DIR/$relative_report"
    [[ -f "$report" ]] || { echo "missing Docker test report: $relative_report" >&2; exit 1; }
    tests=$(xmllint --xpath 'string(/testsuite/@tests)' "$report")
    failures=$(xmllint --xpath 'string(/testsuite/@failures)' "$report")
    errors=$(xmllint --xpath 'string(/testsuite/@errors)' "$report")
    skipped=$(xmllint --xpath 'string(/testsuite/@skipped)' "$report")
    if [[ "$tests" != 1 || "$failures" != 0 || "$errors" != 0 || "$skipped" != 0 ]]; then
        echo "Docker gate failed for $relative_report: tests=$tests failures=$failures errors=$errors skipped=$skipped" >&2
        exit 1
    fi
done

echo "Docker gate verified: 3 tests, 0 failures, 0 errors, 0 skips"
