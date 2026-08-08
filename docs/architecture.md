# Architecture

## Boundary

The project has a small startup lifecycle module, optional refresh and restart-requirement control
planes, and one module per tested native SDK line:

```text
core/
    observation/       # neutral facts and bounded non-waiting delivery
    lifecycle/         # startup ownership and binding contracts
    refresh/           # optional same-type replacement engine
    restart/           # optional rollout-required monitor
adapters/
    configuration/     # external typed snapshot sources
        nacos3/
    observability/     # optional projections of neutral facts
        micrometer/
        jfr/
        opentelemetry/
        slf4j/
bindings/
    redis/
        jedis/
            v5/
            v7/
        lettuce/v6/
        redisson/v4/
    mysql/
        connectorj/v9/
        mariadb/v3/
    postgresql/jdbc/v42/
    clickhouse/jdbc/v0/
    mongodb/sync/v5/
    neo4j/driver/v6/
    elasticsearch/java/v9/
    milvus/sdk/v2/
    minio/java/v8/
    nacos/client/v3/
    etcd/jetcd/v0/
    zookeeper/curator/v5/
    consul/api/v1/
    kafka/client/v3/
    pulsar/client/v3/
    rabbitmq/client/v5/
    rocketmq/client/v5/
    elasticjob/lite/v3/
    powerjob/worker/v5/
    xxljob/core/v2/
    seata/tm/v2/
support/               # test-only shared support
examples/              # runnable examples; not published
benchmarks/            # performance evidence; not published
bom/                   # published dependency management
```

The module boundary is also the provider-selection boundary. The application chooses a binding in
Maven or Gradle; the runtime never searches for, downloads, or switches providers.

Binding layout is structural, not taxonomic:

```text
bindings/<middleware>/<driver>/v<major>
steward-binding-<middleware>-<driver>-v<major>
yunqi.zhibei.steward.binding.<middleware>.<driver>.v<major>
```

Middleware names are direct children of `bindings`; broad functional categories are deliberately
absent. A middleware can expose multiple drivers, and a driver can retain multiple incompatible
major lines side by side. The `v` prefix keeps the version segment legal in Java packages.

Packages encode their architectural responsibility:

```text
yunqi.zhibei.steward.observation
yunqi.zhibei.steward.lifecycle
yunqi.zhibei.steward.refresh
yunqi.zhibei.steward.restart
yunqi.zhibei.steward.adapter.configuration.nacos3
yunqi.zhibei.steward.adapter.observability.micrometer
yunqi.zhibei.steward.binding.redis.jedis.v7
yunqi.zhibei.steward.binding.kafka.client.v3
yunqi.zhibei.steward.support.testing
```

Core packages remain product-owned and vendor-neutral. Adapter packages identify the external
integration. Binding packages include the middleware, implementation, and SDK major line; this
prevents split packages when two binding artifacts are present during migration or testing.

Dependencies point toward core. Core does not import native SDKs, Micrometer, OpenTelemetry,
SLF4J, JFR adapters, or configuration-center clients. `support` is test scope only, and examples
and benchmarks are excluded from publication.

## Observation Boundary

Current state remains available through `BoundResource.state()`, `ManagedResourceStatus`, and
`RestartRequiredStatus`. Discrete low-frequency transitions use the independent
`steward-observation` contract. [`observation-contract.md`](observation-contract.md) defines the
complete field set, audited emission points, redaction rules, ordering, and overflow behavior.

Lifecycle and binding modules contain no Micrometer, OpenTelemetry, SLF4J, JFR, or profiling-tool
API. Observation is disabled by default. When enabled, owners publish once with a non-waiting offer;
full or contended buffers drop telemetry rather than delaying startup, refresh, drain, or closure.
Native business operations are never instrumented by this lifecycle contract.

## Contracts

`StartupBinding<C,T>` defines three lifecycle operations:

```text
create C -> native T
check T  -> Health(status, probe scope)
close T
```

It makes no claim that two instances can coexist. `BoundResource.start(configuration, binding)`
accepts any `StartupBinding`, creates and checks one native resource, and exposes that exact object
through `resource()` until close.

`ResourceBinding<C,T>` is a narrower promise: old and candidate resources may safely overlap while
health checking and draining occur. It extends `StartupBinding`, so it can be used in either mode.
The marker contract remains in lightweight `steward-lifecycle`; the implementation that consumes it is
packaged separately in `steward-refresh` under `yunqi.zhibei.steward.refresh`. Both
`ManagedResource.bind(source, binding)` and `ManagedResource.builder(source, binding)` require a
`ResourceBinding`; the Builder cannot replace its factory or closer independently.

The shipped binding exposes the vendor's real type as `T`, such as `JedisPooled`, `Driver`,
`KafkaProducer<byte[],byte[]>`, `HikariDataSource`, or `CuratorFramework`. It does not translate
commands, results, callbacks, exceptions, or serialization formats into project-owned equivalents.

## Startup Path

The default lifecycle is deliberately short:

```text
typed configuration
    -> binding.create
    -> binding.check
    -> BoundResource.resource
    -> native SDK calls
    -> binding.close at shutdown
```

An unhealthy startup candidate is closed before startup fails. Once published, the object is never
replaced, so application code can safely retain and use the native reference during the
`BoundResource` lifetime. The owner exposes `OPEN`, `CLOSING`, `CLOSED`, and `CLOSE_FAILED` states.
Closing makes the native object inaccessible before invoking the native closer, and that closer is
called at most once. A close failure is terminal and observable rather than silently retried.
Because this close is synchronous, unchecked failures propagate directly, checked failures are
wrapped, and interruption restores the caller's interrupt status before propagation.

Demand-time health and close share the `BoundResource` lifecycle lock. A running vendor probe
therefore delays close, and a health call queued behind close fails without probing after closure.
The core does not interrupt either vendor call or impose a deadline. Each binding must configure
finite native probe and shutdown timeouts when its SDK supports them; otherwise application
shutdown can remain blocked inside vendor code.

## Same-Type Refresh

In-process refresh is opt-in. Applications must add `steward-refresh`; selecting a binding alone
does not pull the refresh engine into the runtime classpath. `ManagedResource<T,C>` is available
only for a `ResourceBinding<C,T>` whose SDK permits overlapping instances. Startup reads and
reconciles the first configuration synchronously. Later source callbacks only mark work pending and
schedule one virtual-thread worker; they do not create or close a client on the notification thread.
The worker reads the latest complete snapshot and performs:

1. Create a candidate of the same compile-time type `T`.
2. Run the binding's health check.
3. Atomically publish the healthy candidate for new leases. A candidate which loses this compare-
   and-set race to shutdown remains unpublished.
4. Retire the previous generation without blocking current operations.
5. Close it after its leases finish.

Intermediate notifications are coalesced with latest-revision-wins semantics. Each complete typed
snapshot has a positive source-local monotonic revision. Equal revisions are duplicate observations
and lower revisions are stale; neither replaces a generation. Provider-native versions remain in
the source adapter. The owner holds at most two native resources: the active generation plus one
candidate or retiring generation. While an old generation still has leases and is draining,
creation of another candidate is deferred.

Refresh does not wait for retirement. Use `awaitIdle(timeout)` when a caller needs an explicit
barrier. It waits for candidate work, reconciliation, deferred refresh, and generation retirement to
reach a stable idle point. If draining completion releases a coalesced latest-snapshot refresh, that
refresh and its resulting retirement are included. A source change after the stable observation is
a subsequent operation. Idle is not convergence: a failed replacement can leave no work running
while the active revision remains behind the desired revision. `ManagedResourceStatus` exposes
lifecycle, active generation, active and
desired revisions, lease count, pending work, refresh counters and timestamps, and redacted failure
state without retaining native throwables. A desired revision greater than the active revision
means the latest replacement has not yet been published. Fields in one status object come from one
short control-plane state transaction; status reads never wait for resource creation, probing, or
closure and are not completion barriers.

Manual refresh admission and shutdown are coordinated. Once close starts, new manual refreshes are
rejected. An already-admitted refresh may finish cleanup but cannot publish a candidate, and it is
included in termination. After cleanup tasks start, the configured close-wait timeout bounds how
long `ManagedResource.close()` waits; it never force-closes a resource that still has a lease.

Managed retirement and candidate cleanup may run on background virtual threads, so native close
failures are captured as redacted `FailureSnapshot` values instead of being rethrown from a later
application call. If a cleanup thread itself cannot be started, that cleanup runs synchronously so
the generation or subscription cannot remain permanently stuck in shutdown. This exceptional
fallback can exceed the close-wait timeout when a native closer or subscription blocks. This differs
intentionally from synchronous `BoundResource.close()` while both owners retain the same
at-most-once close and interrupt-restoration rules.

Virtual-thread dispatch and wall-clock capture sit behind a package-private lifecycle runtime. The
production runtime retains the behavior above, while core tests can deterministically advance queued
refresh and retirement tasks and use fixed timestamps. This is not a public scheduling extension
point and cannot change a binding's lifecycle capabilities.

An ordinary creation, health, or source exception is recorded as a redacted failure and leaves the
current active generation available. A fatal `Error` is allowed to reach the thread's
uncaught-error handling and moves the lifecycle to `REFRESH_DISABLED`; automatic and manual refresh
then stop, but the last active generation remains usable until normal shutdown. An otherwise
unclassified runtime exception which escapes reconciliation is treated as `REFRESH_ENGINE` and
also disables replacement instead of silently terminating a worker.

Failure stages describe where lifecycle reconciliation failed. `CANDIDATE_HEALTH_CHECK` applies
only to the mandatory probe before publication. An explicit `health()` call is a demand-time query:
its result or exception is returned to the caller and is not retained as refresh state. Redacted
failure snapshots include the related generation and source revision when known. Zero means the
identity was not assigned or cannot be attributed reliably; configuration-source and subscription
failures therefore do not invent a revision.

Calls use `execute(...)`, `executeAsync(...)`, or `Lease.execute(...)`; a lease never exposes the
resource directly. Java cannot prevent an operation from returning the client or a dependent native
object, so doing that is an explicit contract violation. `executeAsync(...)` keeps the lease until
its completion stage finishes; use an explicit lease for any other asynchronous lifetime.

The type `T` never changes. A `ManagedResource<JedisPooled, Jedis7Configuration>` can rebuild a
Jedis 7 client, but cannot publish Lettuce, Redisson, or Jedis 5.

Nacos, Apollo, or another configuration center is only a source of complete configuration
snapshots and change signals. It does not determine whether a native SDK can apply a change. A
connector may implement `ConfigurationSource<C>` in a separate integration module; bindings and
the refresh engine do not depend directly on a configuration-center SDK. The connector resolves
provider-specific ordering before assigning a source-local revision.

## Startup-Only SDKs

RocketMQ, PowerJob, and XXL-JOB bind process-level identities, ports, groups, or static state and
use `StartupBinding`. They can be owned by `BoundResource`, but cannot be passed to the managed
refresh API. Reconfiguration requires an application restart or rolling deployment.

The optional `steward-restart` module closes the detection gap without changing that ownership
rule. The application starts a resource from one `ConfigurationSnapshot`, then calls
`RestartRequiredMonitor.watch(source, snapshot.revision())`. The monitor installs its source
subscription before checking the latest snapshot, so a complete update which arrived during native
startup is observed. A revision above the applied revision publishes `RESTART_REQUIRED`; equal or
lower observations do not move state backward. It keeps no typed configuration or throwable.

The monitor is a control-plane signal, not a deployment engine. It does not mutate the native
client, close the running resource, exit the JVM, call Kubernetes, or clear the requirement inside
the old process. A deployment controller, operator, or CI/CD workflow may consume the status and
perform a normal rollout. The replacement process applies the newer snapshot and begins with a new
`CURRENT` monitor. The status should not be wired directly to a Kubernetes liveness probe, which can
restart all replicas without rollout ordering, readiness checks, or graceful drain coordination.

Seata TM exposes process-global static initialization rather than a closeable resource.
`SeataTm2.initialize(configuration)` initializes it once per JVM. Repeating the same configuration
is idempotent; a different configuration is rejected. Seata does not use `BoundResource` or
`ManagedResource`; an application which initializes it from a configuration snapshot may use the
same restart-requirement monitor.

## Configuration Scope

Binding configuration contains only fields required to construct and own the native resource:
endpoints, credentials, TLS, pools, connection settings, worker identity, and similar lifecycle
state. Per-call requests, transaction options, Neo4j session database choices, RPC deadlines, and
operation payloads stay in native SDK calls when the selected SDK defines them per operation.

Secrets may be retained for authentication, but typed configuration `toString()` methods redact
them. Maps of native extension properties expose keys rather than values in diagnostic strings, as
those values may contain vendor-specific credentials. Health is detail-free, and managed failure
snapshots retain only stage, failure type, time, and safe generation/revision identifiers.

Secret acquisition is outside bindings and the lifecycle core. Applications or independent
integration modules resolve mounted secrets, Vault/KMS values, or cloud secret-manager values and
then build a complete typed configuration. A refresh adapter publishes a greater local revision
only after both ordinary configuration and all required secrets are available. Lookup or renewal
failure makes that source read fail so the current managed generation remains active; it must not
publish a partial snapshot. No core `SecretResolver` abstraction is provided: provider
authentication, leases, renewal, versioning, and failure behavior are materially different, while
vendor SDKs ultimately require their native credential representation in memory.

Every project-owned configuration is an immutable final class. Full constructors are not public;
the stable construction surface is `builder(...)` and `toBuilder()`. Technical fields have tested
defaults. Application data identity and process identity do not: database names, producer groups,
and Seata identities remain required builder arguments. PowerJob and XXL-JOB accept native startup
objects as explicit exceptions because their handlers and framework state are not simple
configuration values.

## Health Semantics

`StartupBinding.check` returns a binary status and a `ProbeScope`; it is a binding-native probe, not
a cross-product service-level objective. Bindings use the strongest side-effect-free operation that
the selected SDK exposes:

1. A remote ping, metadata request, or validated connection where available.
2. Native connection state when a remote probe is not exposed.
3. Local lifecycle state, or successful startup, for SDKs where probing would send business data,
   mutate registration, or rely on deprecated internals.

The corresponding scopes are `REMOTE`, `CONNECTION_STATE`, `LOCAL`, and `STARTUP_ONLY`.
Consequently, Kafka `metrics()` and Pulsar `isClosed()` are local signals. RocketMQ, PowerJob, and
XXL-JOB startup checks only confirm that native initialization returned successfully. Application
observability must cover sends, acknowledgements, registration, and task execution separately.

## Dependency And Release Contract

Each binding pins one tested direct SDK line. Maven Enforcer requires Maven 3.9+, JDK 21, and a
resolved upper-bound dependency graph. Transitive conflicts are aligned in the binding that owns
the vendor SDK; there is intentionally no global Netty, SLF4J, Kotlin, or Error Prone override that
could move unrelated SDK families onto an incompatible line.

Runtime major-version checks are retained only when an SDK exposes a reliable official version
API. Package `pom.properties` is not a universal runtime contract because shading and application
packaging may remove it. Build-time exact versions and dependency convergence are the primary
version-line protection.

The reactor attaches sources and Javadocs for Java artifacts. `mvn clean verify` is therefore the
release gate: it compiles with `-Xlint:all -Werror`, runs unit and available integration tests,
checks dependency upper bounds, verifies that documentation artifacts can be produced, and runs
japicmp against the newest older non-SNAPSHOT release of each JAR module. Binary-incompatible
changes to public or protected API fail the build. POM modules and the first release, for which no
older artifact exists, are skipped.

Binding authors may add `steward-binding-testkit` with test scope and run `BindingContract.verify(...)`
against offline-safe configurations. The shared checks exercise real factory and closer calls while
substituting deterministic health outcomes. The testkit stays out of application runtime graphs and
does not add a common client API.

## Non-Goals

- A common business or middleware data API.
- Runtime switching between SDKs or incompatible major lines.
- Provider coordinates, capabilities, SPI discovery, or plugin class loaders.
- Runtime dependency download or classpath isolation.
- Framework-specific starters, Spring configuration, retry, tracing, or service-mesh policy.
