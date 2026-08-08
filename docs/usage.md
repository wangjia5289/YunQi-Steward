# Usage

## Select A Binding

Select one exact binding artifact at build time. Do not add several alternatives merely to make
them available at runtime.

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-binding-redis-jedis-v7</artifactId>
    <version>0.1.0</version>
</dependency>
```

To move to Lettuce, replace this artifact with `steward-binding-redis-lettuce-v6`, update native call sites,
and deploy. No configuration value can perform that change inside a running JVM.

## Build Typed Configuration

All project-owned configuration types are immutable final classes with the same incremental
construction convention. Their full constructors are not public:

- `builder(...)` starts with tested technical defaults.
- `toBuilder()` copies an existing immutable configuration for a focused change.
- `build()` validates the complete snapshot, including related fields and duration ranges.
- Credentials are configured and cleared as a group where the SDK requires a pair.
- Secret values and extension-property values are omitted or redacted by `toString()`.

Most configurations need no builder argument. Values that identify application-owned data or a
process identity deliberately have no made-up default:

| Configuration family | Entry point | Required application choice |
| --- | --- | --- |
| MySQL, MariaDB, PostgreSQL, ClickHouse | `builder(database)` | Database name |
| RocketMQ producer | `builder(producerGroup)` | Stable producer group |
| Seata TM | `builder(applicationId, transactionServiceGroup)` | Process and transaction identities |
| All other project-owned configurations | `builder()` | None; replace the local endpoint as needed |

For example:

```java
var initial = PostgreSqlJdbc42Configuration.builder("orders")
        .host("postgres.internal")
        .credentials("orders_app", loadDatabasePassword())
        .build();

var rotated = initial.toBuilder()
        .credentials("orders_app", loadRotatedSecret())
        .build();
```

Do not treat the local defaults as production policy. They make a binding runnable with minimal
changes; production endpoints, credentials, TLS policy, capacity, and timeouts still belong to the
application deployment.

## Load Secrets At Runtime

Do not commit passwords, access keys, tokens, or private material to source code or ordinary
application configuration. Resolve them in the application composition layer before building the
typed binding configuration. Prefer, in order:

1. SDK-native workload identity or a default credential provider when the selected SDK supports it.
2. A read-only secret file mounted from Kubernetes Secrets, Docker secrets, or a tmpfs-backed
   deployment secret.
3. A dedicated Vault, KMS, or cloud Secret Manager client owned by an application integration
   module.
4. Environment variables only when the deployment platform cannot provide a safer mechanism.

For a startup-fixed resource, read the secret during startup and pass it directly to the builder:

```java
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

String password = Files.readString(
        Path.of("/run/secrets/orders-database-password"),
        StandardCharsets.UTF_8);

var configuration = PostgreSqlJdbc42Configuration.builder("orders")
        .host("postgres.internal")
        .credentials("orders_app", password)
        .build();
```

Do not call `trim()` or `strip()` unless the secret format explicitly excludes leading, trailing,
or newline characters. Do not place secret-manager references such as `${vault:...}` into a
binding: bindings consume resolved native values and do not interpret external secret syntax.

For managed refresh, the configuration-source adapter must resolve both ordinary settings and
secret material before publishing one complete `ConfigurationSnapshot`. It increments the local
revision only after every required value is available and validated. If secret lookup or renewal
fails, that snapshot read fails instead of returning a partial configuration; `ManagedResource`
records the source failure and keeps its current active generation. Secret rotation publishes a
complete new snapshot with a greater revision, which uses the normal candidate, health-check,
publication, rollback, and retirement path.

Project-owned configuration objects redact secrets from `toString()`, and lifecycle failures never
retain native exception messages. The resolved credential still has to exist in process memory
because vendor SDKs require it to create a client. Many SDKs accept only `String`, so Java cannot
reliably erase every copy; protect heap dumps, diagnostic endpoints, serialization, and application
logs accordingly. Core deliberately does not define a universal secret-provider SPI because
provider authentication, leasing, renewal, and failure semantics are not interchangeable.

## Observe Lifecycle Facts

Status is the primary source for current state and counters. When an application also needs
discrete lifecycle facts, create a bounded caller-owned buffer and pass it at owner construction:

```java
var lifecycleEvents = LifecycleEventBuffer.create(256);

try (var redis = ManagedResource
        .builder(source, new Jedis7Binding())
        .lifecycleEvents(lifecycleEvents)
        .build()) {
    // An optional adapter polls status and drains lifecycleEvents on its own thread.
}

for (LifecycleEvent event : lifecycleEvents.drain(64)) {
    exportThroughApplicationAdapter(event);
}
lifecycleEvents.close();
```

Omit `lifecycleEvents(...)` to select the shared no-op. The buffer creates no consumer thread and
owners never close it. A full or contended buffer drops the new fact and increments
`droppedEvents()`; it never waits for an exporter. Events contain no configuration, endpoint,
native client, throwable, or exception message. Do not put those values back into adapter labels.
The full contract and lifecycle audit are in
[`observation-contract.md`](observation-contract.md).

## Choose A Redis Topology

The artifact chooses the client library; the binding class chooses the topology. Do not use one
generic Redis configuration because the SDKs expose different native client types:

| SDK artifact | Standalone | Cluster |
| --- | --- | --- |
| `steward-binding-redis-jedis-v7` | `Jedis7Configuration` / `Jedis7Binding` / `JedisPooled` | `Jedis7ClusterConfiguration` / `Jedis7ClusterBinding` / `JedisCluster` |
| `steward-binding-redis-lettuce-v6` | `Lettuce6Configuration` / `Lettuce6Binding` / `RedisClient` | `Lettuce6ClusterConfiguration` / `Lettuce6ClusterBinding` / `RedisClusterClient` |
| `steward-binding-redis-redisson-v4` | `Redisson4Configuration` / `Redisson4Binding` / `RedissonClient` | `Redisson4ClusterConfiguration` / `Redisson4ClusterBinding` / `RedissonClient` |

Cluster configurations accept multiple seed nodes and expose topology-specific controls such as
redirect limits, topology refresh intervals, and master/replica pool sizes where the selected SDK
supports them. The binding still returns the native SDK type:

```java
var configuration = Lettuce6ClusterConfiguration.builder()
        .node("redis-0.internal", 6379)
        .addNode("redis-1.internal", 6379)
        .topologyRefreshPeriod(Duration.ofSeconds(30))
        .build();

try (BoundResource<RedisClusterClient> redis =
        Lettuce6ClusterBinding.start(configuration)) {
    RedisClusterClient client = redis.resource();
}
```

Sentinel is intentionally not represented by either topology. It needs its own contract for the
Sentinel endpoints, master name, data-node authentication, and optional Sentinel authentication.
These credentials can differ, so adding a single `sentinel=true` flag would produce an incomplete
and error-prone API.

## Start One Native Resource

`BoundResource` is the default. It starts one native client and exposes it directly:

```java
var configuration = Jedis7Configuration.builder()
        .host("redis.internal")
        .password(loadRedisPassword())
        .build();

try (BoundResource<JedisPooled> redis = Jedis7Binding.start(configuration)) {
    JedisPooled client = redis.resource();
    client.set("answer", "42");
    String answer = client.get("answer");
}
```

Startup fails if construction or health checking fails. The candidate is closed on failure.
`resource()` fails once close begins. `state()` reports `OPEN`, `CLOSING`, `CLOSED`, or
`CLOSE_FAILED`. Close is idempotent from the owner's perspective: the native closer is invoked at
most once, and a failed close is not retried by a later `close()` call.

The same pattern applies to every `ResourceBinding` and `StartupBinding`. Some startup-only modules
also use the same `start(...)` method:

```java
try (BoundResource<DefaultMQProducer> producer = RocketMq5Binding.start(configuration)) {
    producer.resource().send(message);
}
```

## Refresh One SDK Type

Use managed refresh only when the selected binding implements `ResourceBinding`, and only when the
application actually needs in-process endpoint, credential, or pool replacement. Add the optional
engine explicitly in addition to the selected binding:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-refresh</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
var source = new MutableConfigurationSource<>(loadJedisConfiguration());

try (ManagedResource<JedisPooled, Jedis7Configuration> redis =
        ManagedResource.bind(source, new Jedis7Binding())) {
    redis.execute(client -> client.set("answer", "42"));

    source.update(loadUpdatedJedisConfiguration());

    String answer = redis.execute(client -> client.get("answer"));
}
```

The selected binding remains Jedis 7 for the full lifetime of this object. `source.update(...)`
changes its configuration, not its SDK implementation. The update callback returns after signaling
work; a single virtual-thread worker reads the newest snapshot. Intermediate updates may be
coalesced, and no new candidate is created while the prior generation is still draining. At most
the active resource and one candidate or retiring resource coexist.

Prefer `execute(...)` for synchronous work. Its return value must not be the client or a native
object whose lifetime depends on that client. For multi-step work, use one explicit lease:

```java
try (var lease = redis.acquire()) {
    lease.execute(client -> {
        firstStep(client);
        secondStep(client);
        return null;
    });
}
```

For asynchronous SDK calls, use `executeAsync(...)`; it releases the generation when the returned
stage completes:

```java
CompletionStage<Result> stage = managed.executeAsync(client -> client.callAsync());
```

Do not use `managed.execute(client -> client.callAsync())`; it releases the lease before the
asynchronous work necessarily completes. Keep an explicit lease only when the lifetime cannot be
represented by one completion stage.

Control-plane status is available without vendor messages or throwable retention:

```java
managed.status();
managed.lastRefreshFailure();
managed.closeFailures();
managed.health();
managed.refresh();
managed.awaitIdle(Duration.ofSeconds(30));
managed.awaitTermination(Duration.ofSeconds(30));
```

Fields returned by one `status()` call form one internally consistent lifecycle snapshot. The
snapshot can become stale immediately; use `awaitIdle(...)` or `awaitTermination(...)` when a
completion barrier is required. `awaitIdle(...) == true` means lifecycle work is currently idle, not
that the desired revision was applied. Compare `activeRevision()` with `desiredRevision()` and check
`lastRefreshFailure()` when convergence matters.

`refresh()` retries the current desired configuration after a transient creation or health failure,
even when the source does not emit a second signal. It is synchronous, unlike a source notification.
`awaitIdle(...)` is the explicit barrier for callers that must know candidate creation,
reconciliation, deferred refresh, and generation draining have reached a stable idle point. If an
old generation was delaying a coalesced latest-snapshot refresh, the barrier includes that refresh
and its resulting retirement. A configuration change published after the stable observation starts
new work and is not covered by the completed call.

Shutdown rejects new operations and manual refreshes. A refresh admitted before shutdown may finish
candidate cleanup but cannot publish after close begins. A candidate which loses publication to
shutdown is still an unpublished candidate and is closed exactly once. `close()` waits only for its
configured timeout; `awaitTermination(...)` can be used later to observe completion of admitted
refreshes, subscription closure, and resource retirement. If the JVM cannot start a cleanup thread,
the cleanup runs synchronously to avoid a permanently stuck generation; that exceptional fallback
can exceed the configured wait timeout.

Transient refresh failures are recorded and leave the active generation usable. A fatal `Error`
disables further replacement and appears as `REFRESH_DISABLED` in `status().lifecycle()`; the active
generation can still serve operations until the owner is closed. An otherwise unclassified runtime
failure escaping the coordinator is recorded as `REFRESH_ENGINE` and disables replacement as well.

`health()` is an immediate probe of the active generation. Its result or exception is returned to
the caller and is not stored in `lastRefreshFailure()`, which is reserved for lifecycle refresh.
Lifecycle failures expose `generation()` and `revision()` for correlation. Either is `0` when it
does not apply or cannot be identified safely, such as a subscription failure; resource creation
failures have a revision but no generation because allocation has not completed.

## Startup-Only Bindings

The following bindings intentionally do not implement `ResourceBinding`:

- `RocketMq5Binding`
- `PowerJobWorker5Binding`
- `XxlJobCore2Binding`

They accept only startup ownership through `BoundResource`. Their native clients may bind ports,
worker identities, producer groups, or process-global state that makes candidate overlap unsafe.

PowerJob and XXL-JOB are intentional configuration-shape exceptions. They accept the native
`PowerJobWorkerConfig` and `XxlJobSimpleExecutor` because handlers, ports, and framework objects are
part of those SDK startup objects and cannot be faithfully represented by a small project-owned
value object.
The binding adds ownership, startup rollback, and shutdown; it does not replace the SDK's setup API.

When one of these startup-only settings changes, roll the application instances: start instances
with the new configuration, wait for service readiness and traffic/task registration, then drain
and stop instances using the old configuration. Rolling deployment belongs to the deployment
platform (for example Kubernetes, a VM orchestrator, or a CI/CD system), not to this Java library.

Add the optional restart-requirement control plane when the application must detect that gap:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-restart</artifactId>
    <version>0.1.0</version>
</dependency>
```

Read the startup snapshot explicitly and pass that exact source-local revision to the monitor after
the native resource starts:

```java
ConfigurationSnapshot<RocketMq5Configuration> applied = source.snapshot();

try (var producer = RocketMq5Binding.start(applied.configuration());
        var restart = RestartRequiredMonitor.watch(source, applied.revision())) {
    RestartRequiredStatus status = restart.status();
    if (status.restartRequired()) {
        publishRestartRequirement(status.appliedRevision(), status.desiredRevision());
    }

    producer.resource().send(message);
}
```

`source` must be the same source instance which supplied `applied`. The monitor subscribes before
re-reading it, so an update published while `RocketMq5Binding.start(...)` was running is detected
immediately. Only a newer complete typed snapshot produces `RESTART_REQUIRED`; parse, validation,
or secret-resolution failure leaves the last requirement unchanged and records only a redacted
failure type. The monitor never retains the typed configuration.

Poll or export `status()` for a deployment controller, operator, or CI/CD workflow. The monitor does
not stop the resource, exit the JVM, or call Kubernetes. Do not make `RESTART_REQUIRED` a liveness
failure: it can restart every replica without rollout ordering, readiness checks, or graceful drain
coordination. After rollout, each new process starts from the newer snapshot and creates a new
monitor whose state is `CURRENT`.

## Seata Initialization

Seata has no owned native client to close:

```java
SeataTm2.initialize(SeataTm2Configuration.builder("orders", "orders_tx_group").build());
```

The first call initializes Seata TM. The same configuration may be submitted again; a different
configuration fails. Reconfiguration requires another JVM.

## Spring And Other Frameworks

There is no framework module. Register a normal `BoundResource` or native client bean and attach
`close()` to the framework lifecycle. For startup-only bindings, keep `BoundResource` itself as the
owned bean. For Seata, call `initialize(...)` during application startup.

## Nacos 3 Configuration Source

Add the provider adapter in addition to the selected refresh-safe binding:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-configuration-nacos3</artifactId>
    <version>0.1.0</version>
</dependency>
```

`Nacos3ConfigurationSource` watches one data ID and group. Its `Loader` is the application-owned
boundary which parses provider content, validates every field, resolves every required secret, and
returns one complete immutable binding configuration:

```java
ConfigService nacos = createNacosConfigService();
try {
    try (var source = Nacos3ConfigurationSource.open(
                    nacos,
                    "redis.yaml",
                    "PROD",
                    Duration.ofSeconds(3),
                    content -> {
                        RedisSettings settings = parseAndValidate(content);
                        String password = readMountedSecret(settings.passwordFile());
                        return Jedis7Configuration.builder()
                                .host(settings.host())
                                .port(settings.port())
                                .password(password)
                                .build();
                    });
            var redis = ManagedResource.bind(source, new Jedis7Binding())) {
        redis.execute(client -> client.set("answer", "42"));
    }
} finally {
    nacos.shutDown();
}
```

Opening the source installs the Nacos listener and loads the initial content synchronously. It
returns only when the loader has produced revision 1; an initial read, parse, validation, or secret
failure aborts startup and removes the installed listener. Later valid content is loaded on one
virtual-thread worker and receives a source-local monotonic revision. Duplicate content is ignored,
and callbacks which arrive while loading may be coalesced to the latest content.

If a later load fails, `snapshot()` reports a redacted unavailable error. `ManagedResource` records
that refresh failure and keeps the current generation serving. No revision and no partially built
configuration are published. A later valid Nacos update clears the source failure and can replace
the active generation normally.

The caller continues to own `ConfigService`. Closing `Nacos3ConfigurationSource` removes only this
adapter's listener and stops its loader executor; it does not call `ConfigService.shutDown()`. Close
the managed resource before the source, then shut down the Nacos client as shown above. Neither
adapter diagnostics nor lifecycle failure snapshots contain the data ID, group, raw provider
content, typed configuration, loader exception message, or resolved secret.

The adapter supports only same-type replacement through `ResourceBinding`. Supplying a Nacos source
does not make `StartupBinding` resources refreshable; those changes still require a restart or
rolling deployment.

## Custom Configuration Services

`MutableConfigurationSource<C>` is useful for tests and programmatic refresh. An external
configuration service may implement `ConfigurationSource<C>` and publish complete immutable typed
snapshots. Subscription callbacks are change signals only; consumers read the latest value from
`snapshot()`. Each `ConfigurationSnapshot<C>` has a positive revision local to that source instance.
Assign a strictly greater revision only after a new complete desired state is available. Repeated
revisions are ignored as duplicates and lower revisions are ignored as out of order; do not expose
provider-specific revision strings through the core SPI.

Initial `snapshot()` failure aborts managed-resource startup and closes the subscription. A later
failure is recorded while the active generation remains usable. `Subscription.close()` must be
idempotent, and no new callback may begin after it returns. These types live in the
`yunqi.zhibei.steward.refresh` package of `steward-refresh` and are consumed by `ManagedResource` or the
optional startup-only `RestartRequiredMonitor`. The Nacos 3 adapter follows this rule in
`steward-configuration-nacos3`; future provider adapters such as Apollo also belong in separate connector artifacts
rather than individual client bindings.

## Health Check Semantics

`Health` contains a binary `status()` and an explicit `scope()`. It answers whether the binding's
available probe succeeds; it is not a promise that every SDK offers the same depth of readiness
check.

| `ProbeScope` | Bindings | Meaning |
| --- | --- | --- |
| `REMOTE` | Redis, JDBC, Elasticsearch, MinIO, MongoDB, Neo4j, Milvus, etcd, Consul, Nacos, ZooKeeper/ElasticJob | A server round trip or native connectivity check succeeded |
| `CONNECTION_STATE` | RabbitMQ | The SDK connection reports open |
| `LOCAL` | Kafka, Pulsar | The producer/client is locally usable; broker reachability is not proven |
| `STARTUP_ONLY` | RocketMQ, PowerJob, XXL-JOB | Native startup completed; real sends, registration, and execution need operational monitoring |

In particular, Kafka's `metrics()` and Pulsar's `isClosed()` do not force a broker round trip.
Combine them with send failures, acknowledgements, lag/backlog, worker registration, and external
service monitoring when deciding production readiness.

## Integration Tests

The Jedis 7, Lettuce 6, and Redisson 4 modules start Redis 7.4.2 with Testcontainers and verify
startup health, real reads and writes, and resource closure. A Docker-compatible runtime is needed
to execute these tests. When it is unavailable, JUnit reports them as skipped instead of silently
pretending that an integration test ran.
