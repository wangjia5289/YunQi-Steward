# Release And Compatibility Policy

## Versioning

The project follows semantic versioning from `0.1.0`. Public contracts listed in
[`public-api.md`](public-api.md) are compatibility-controlled. During the `0.x` line, incompatible
changes require an explicitly documented minor-version migration; patch releases are additive or
corrective. Package-private implementation, the runnable example, and JMH benchmarks are not
compatibility contracts.

`LifecycleEvent` is intentionally fixed and engine-created. Adding telemetry products does not add
fields or dependencies to it. New optional adapters may implement `LifecycleEventDelivery` and use
status/event facts under the adapter admission rules.

## Artifact Set

The `yunqi.zhibei:steward-bom:0.1.0` BOM manages all 39 published JAR modules. The root
parent POM and BOM are also published. `steward-example-observation-e2e` and `steward-benchmark-observation` compile
in the reactor but set `maven.deploy.skip=true` and are not consumer artifacts.

Every published Java module must stage its main, source, and Javadoc JAR. Published POMs retain the
module-local vendor SDK graph; importing the BOM does not add Micrometer, OpenTelemetry, SLF4J, or
JFR adapters. Applications select each optional adapter explicitly.

## Staging

From a clean source tree with JDK 21 and Maven 3.9 or newer:

```bash
MVN_BIN=/path/to/mvn scripts/stage-release.sh
```

The script accepts only a staging directory below this checkout's `target` directory, refuses a
non-empty destination, runs the complete `release-staging` deploy lifecycle, and verifies the exact
artifact inventory. This is a local repository staging gate, not publication to Maven Central.

## Private Maven Publication

Published artifacts use the shared private GitHub Packages repository
`wangjia5289/YunQi-Maven-Packages`. The repository is deliberately separate from this public
source repository because GitHub's Maven registry is repository-scoped and inherits the target
repository's visibility. Future YunQi frameworks reuse this same artifact repository; their Maven
coordinates, rather than separate package repositories, distinguish the products.

The root POM declares the distribution endpoint:

```text
https://maven.pkg.github.com/wangjia5289/YunQi-Maven-Packages
```

Create `YunQi-Maven-Packages` as a private repository before the first publication. The publishing
workflow in [`.github/workflows/publish-github-packages.yml`](../.github/workflows/publish-github-packages.yml)
must receive these repository secrets:

- `PACKAGES_USERNAME`: GitHub account that owns the package token.
- `PACKAGES_TOKEN`: classic GitHub PAT with `repo`, `read:packages`, and `write:packages` scopes.

The token is used only by Maven authentication to the package repository. The workflow's separate
`GITHUB_TOKEN` is limited to creating the source-repository tag and GitHub Release. The workflow is
manually dispatched from the default branch with version `0.1.0`; it runs local staging first,
publishes the complete reactor, downloads the BOM and a core artifact through an isolated Maven
repository, and creates the annotated tag only after that smoke test passes.

Consumers need a matching `github` server in `settings.xml` and the package repository in their
POM or repository settings. A consumer token needs only `read:packages`; no token is committed to
the project or passed as a Maven command-line argument. A minimal consumer setup is:

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_PACKAGES_USERNAME}</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/wangjia5289/YunQi-Maven-Packages</url>
  </repository>
</repositories>
```

## Binary Baseline

The first `0.1.0` build had no older published artifact and therefore used japicmp's
missing-old-version condition. Development now continues on `0.1.1-SNAPSHOT`; the strict profile
resolves `0.1.0` from the private Packages repository and runs:

```bash
mvn -Pbaseline-compatibility clean verify
```

That profile sets `ignoreMissingOldVersion=false` and activates the private Packages repository.
The local command needs a `github` server in `settings.xml` with a token that has `read:packages`.
The `Release Gate` workflow runs this job on `main` pushes and manual runs, while same-repository
pull requests also receive the job. An unavailable baseline or an unapproved binary incompatibility
fails the build.

## Docker Verification

The release gate runs the full reactor and then requires all eight Docker-backed test methods with
zero skips. These cover the three Redis library clients, static and dynamic Spring/Jedis
integration, and the real Nacos 3 configuration-source path:

```bash
mvn --batch-mode --no-transfer-progress clean verify
scripts/assert-docker-tests.sh
```

The parent POM pins Testcontainers' Docker API client to `1.40`, which is accepted by current
Docker engines and avoids a false "Docker is not available" skip with Docker 29. On macOS, start
Docker Desktop or OrbStack before running the gate; Testcontainers can use `/var/run/docker.sock`,
or `DOCKER_HOST` may point at the active context socket.

If dependency or image downloads require the workstation proxy, first verify that
`127.0.0.1:7891` is listening. Maven/Testcontainers processes may use
`HTTP_PROXY=http://127.0.0.1:7891` and `HTTPS_PROXY=http://127.0.0.1:7891`; image pulls use the
Docker daemon's proxy configuration, so configure Docker Desktop or OrbStack to reach the same
host proxy. Do not enable the proxy when the port is unavailable.

## Upgrade Expectations

Applications close observation components in owner -> fan-out -> event-adapter order. Micrometer
registration handles may close after final status collection. A configuration or secret is never a
supported event, metric tag, span attribute, log argument, or JFR field. Release notes call out any
new stage/outcome, meter identity, status field, adapter, or shutdown-contract change.
