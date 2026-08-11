#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

reports=(
    "yunqi-steward-interaction-plane/redis/library-client/jedis/v7/target/surefire-reports/TEST-yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7.Jedis7BindingIntegrationTest.xml|1"
    "yunqi-steward-interaction-plane/redis/library-client/lettuce/v6/target/surefire-reports/TEST-yunqi.zhibei.steward.interaction.redis.library.client.lettuce.v6.Lettuce6BindingIntegrationTest.xml|1"
    "yunqi-steward-interaction-plane/redis/library-client/redisson/v4/target/surefire-reports/TEST-yunqi.zhibei.steward.interaction.redis.library.client.redisson.v4.Redisson4BindingIntegrationTest.xml|1"
    "yunqi-steward-interaction-plane/redis/framework-client/spring-framework/v6/jedis/v7/target/surefire-reports/TEST-yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7.Jedis7SpringFactoryBeanIntegrationTest.xml|1"
    "yunqi-steward-interaction-plane/redis/framework-client/spring-framework/v6/jedis/v7/target/surefire-reports/TEST-yunqi.zhibei.steward.interaction.redis.framework.client.spring.framework.v6.jedis.v7.Jedis7ManagedResourceFactoryBeanIntegrationTest.xml|3"
    "yunqi-steward-control-plane/configuration-management/nacos/v3/target/surefire-reports/TEST-yunqi.zhibei.steward.control.configuration.nacos.v3.Nacos3ConfigurationSourceIntegrationTest.xml|1"
)

total=0
for report_spec in "${reports[@]}"; do
    relative_report=${report_spec%|*}
    expected_tests=${report_spec##*|}
    report="$PROJECT_DIR/$relative_report"
    [[ -f "$report" ]] || { echo "missing Docker test report: $relative_report" >&2; exit 1; }
    tests=$(xmllint --xpath 'string(/testsuite/@tests)' "$report")
    failures=$(xmllint --xpath 'string(/testsuite/@failures)' "$report")
    errors=$(xmllint --xpath 'string(/testsuite/@errors)' "$report")
    skipped=$(xmllint --xpath 'string(/testsuite/@skipped)' "$report")
    if [[ "$tests" != "$expected_tests" || "$failures" != 0 || "$errors" != 0 || "$skipped" != 0 ]]; then
        echo "Docker gate failed for $relative_report: tests=$tests failures=$failures errors=$errors skipped=$skipped" >&2
        exit 1
    fi
    total=$((total + tests))
done

echo "Docker gate verified: $total tests, 0 failures, 0 errors, 0 skips"
