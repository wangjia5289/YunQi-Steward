#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
STAGING_DIR=${1:-"$PROJECT_DIR/target/release-staging"}
MVN_BIN=${MVN_BIN:-mvn}

if [[ "$STAGING_DIR" != /* ]]; then
    STAGING_DIR="$PROJECT_DIR/$STAGING_DIR"
fi

case "/$STAGING_DIR/" in
    *"/../"*|*"/./"*)
        echo "staging directory must not contain . or .. path segments" >&2
        exit 2
        ;;
esac

case "$STAGING_DIR" in
    "$PROJECT_DIR"/target/*) ;;
    *)
        echo "staging directory must be below $PROJECT_DIR/target" >&2
        exit 2
        ;;
esac

if [[ -L "$PROJECT_DIR/target" ]]; then
    echo "project target directory must not be a symbolic link" >&2
    exit 2
fi
mkdir -p "$PROJECT_DIR/target"

relative_staging_dir=${STAGING_DIR#"$PROJECT_DIR/target/"}
current_dir="$PROJECT_DIR/target"
IFS='/' read -r -a path_segments <<< "$relative_staging_dir"
for segment in "${path_segments[@]}"; do
    current_dir="$current_dir/$segment"
    if [[ -L "$current_dir" ]]; then
        echo "staging directory must not traverse a symbolic link: $current_dir" >&2
        exit 2
    fi
    [[ -e "$current_dir" ]] || break
done

if [[ -d "$STAGING_DIR" ]] && find "$STAGING_DIR" -mindepth 1 -print -quit | grep -q .; then
    echo "staging directory must be absent or empty: $STAGING_DIR" >&2
    exit 2
fi
mkdir -p "$STAGING_DIR"
STAGING_DIR=$(cd "$STAGING_DIR" && pwd -P)

"$MVN_BIN" -f "$PROJECT_DIR/pom.xml" \
    -Prelease-staging \
    -DaltDeploymentRepository="local-staging::file://$STAGING_DIR" \
    clean deploy

REPOSITORY_ROOT="$STAGING_DIR/yunqi/zhibei"
pom_count=$(find "$REPOSITORY_ROOT" -path '*/0.1.0/*.pom' | wc -l | tr -d ' ')
main_jar_count=$(find "$REPOSITORY_ROOT" -path '*/0.1.0/*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | wc -l | tr -d ' ')
source_jar_count=$(find "$REPOSITORY_ROOT" -path '*/0.1.0/*-sources.jar' | wc -l | tr -d ' ')
javadoc_jar_count=$(find "$REPOSITORY_ROOT" -path '*/0.1.0/*-javadoc.jar' | wc -l | tr -d ' ')

[[ "$pom_count" == 41 ]] || { echo "expected 41 POMs, found $pom_count" >&2; exit 1; }
[[ "$main_jar_count" == 39 ]] || { echo "expected 39 main JARs, found $main_jar_count" >&2; exit 1; }
[[ "$source_jar_count" == 39 ]] || { echo "expected 39 source JARs, found $source_jar_count" >&2; exit 1; }
[[ "$javadoc_jar_count" == 39 ]] || { echo "expected 39 Javadoc JARs, found $javadoc_jar_count" >&2; exit 1; }
[[ -f "$REPOSITORY_ROOT/steward-bom/0.1.0/steward-bom-0.1.0.pom" ]]
java "$SCRIPT_DIR/VerifyStagedRelease.java" "$PROJECT_DIR" "$STAGING_DIR"

echo "Release staging verified at $STAGING_DIR"
echo "41 POMs, 39 main JARs, 39 source JARs, 39 Javadoc JARs"
