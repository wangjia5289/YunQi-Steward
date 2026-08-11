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

## Binary Baseline

The first `0.1.0` build has no older published artifact and therefore allows japicmp's missing-old-
version condition. After `0.1.0` is published to the configured artifact repository, development
must move to a later version and run:

```bash
mvn -Pbaseline-compatibility clean verify
```

That profile sets `ignoreMissingOldVersion=false`; an unavailable baseline or an unapproved binary
incompatibility fails the build. External repository publication, signing, and credentials are
release-operator responsibilities and cannot be proven by this source checkout.

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
