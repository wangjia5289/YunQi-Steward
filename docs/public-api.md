# Public API Audit

This document records the intended `0.1.0` public surface. A public type is retained only when an
application, a binding author, or a configuration-source adapter must name or implement it. Package
private implementation types are not extension points. The project does not expose protected
extension points.

## Steward Observation

| Type | Audience | Contract decision |
| --- | --- | --- |
| `LifecycleEvent`, `Stage`, and `Outcome` | Applications and optional adapters | Engine-created immutable lifecycle fact with a fixed secret-free field set |
| `LifecycleEventBuffer` | Applications and optional adapters | Final bounded channel; lifecycle publication never waits and adapters poll or drain |
| `LifecycleEventFanOut` | Applications composing adapters | Fixed bounded source-to-branch distributor with isolated loss accounting |
| `LifecycleEventDelivery` | Event adapters and health integrations | Product-neutral monotonic delivery counters and terminal state |

`LifecycleEvent` has no public constructor. Only `LifecycleEventBuffer` creates it and assigns a
buffer-local sequence after admission. `LifecycleEventBuffer.noop()` is the shared disabled value;
`create(capacity)` creates a caller-owned enabled buffer. Closing a buffer does not discard accepted
events. No listener, executor, exporter, arbitrary tags, or third-party telemetry type is exposed.

## Steward Lifecycle

| Type | Audience | Contract decision |
| --- | --- | --- |
| `BoundResource<T>` and `State` | Applications | Stable owner for one startup-fixed native resource |
| `Health`, `Health.Status`, and `ProbeScope` | Applications and binding authors | Stable, detail-free probe result; callers cannot construct arbitrary instances |
| `StartupBinding<C,T>` | Binding authors | Stable create/check/close contract without an overlap promise |
| `ResourceBinding<C,T>` | Binding authors and refresh users | Stable marker that old and candidate instances may overlap |
| `ResourceFactory<C,T>`, `HealthCheck<T>`, and `ResourceCloser<T>` | Binding authors | Stable functional parts inherited by the two binding contracts |

`BoundResource` and `Health` are final and have no public constructor. Their factory methods define
valid instances. No core type has a protected member.

The additive `BoundResource.start(configuration, binding, lifecycleEvents)` overload publishes
startup and the one close attempt. The original overload selects the no-op buffer.

`BoundResource.health()` and `close()` are synchronous and mutually exclusive. A native health
probe which is already running delays close. A health call waiting behind close does not probe a
closed resource; it fails after close releases the lifecycle lock. Neither operation has a core-
level timeout, so bindings must configure finite vendor probe and shutdown timeouts wherever the
SDK exposes them.

## Optional Observation Adapters

`MicrometerLifecycleMetrics` and its idempotent `Registration` are the public lifecycle metric
bridge. `MicrometerConfigurationSourceMetrics` is the independent source-health bridge; it exposes
only bounded state, revision, failure, recovery, and failure-stage meters.
`JfrLifecycleAdapter`, `OpenTelemetryLifecycleAdapter`, and `Slf4jLifecycleAdapter` are final,
caller-owned asynchronous consumers implementing `LifecycleEventDelivery`. Adapter-specific
counter aliases remain public convenience diagnostics. No adapter class is an extension base, and
no adapter implementation type enters a lifecycle owner or binding signature.

## Framework Clients

| Type | Audience | Contract decision |
| --- | --- | --- |
| `Jedis7SpringFactoryBean` | Plain Spring Framework 6 applications using Jedis 7 | Final Spring-owned factory exposing the native `JedisPooled` singleton |
| `Jedis7ManagedResourceFactoryBean` | Plain Spring Framework 6 applications requiring same-type Jedis 7 replacement | Final Spring-owned factory exposing `ManagedResource<JedisPooled, Jedis7Configuration>` |

The factory requires one complete `Jedis7Configuration`. `afterPropertiesSet()` creates and checks
the client through `Jedis7Binding`; `destroy()` idempotently closes the owned `BoundResource`.
`getObject()` is valid only between successful initialization and destruction. The module exposes
Spring's standard `FactoryBean`, `InitializingBean`, and `DisposableBean` contracts, but no Spring
Boot auto-configuration, project-owned Redis command API, or protected extension point.

The dynamic factory requires a caller-owned `ConfigurationSource<Jedis7Configuration>` and owns
only the resulting `ManagedResource`. Its one-argument constructor selects the no-op event buffer
and a 30-second close wait; the additive three-argument constructor accepts a caller-owned
`LifecycleEventBuffer` and positive close wait duration. The factory never closes that buffer.
Spring destruction stops refresh work and retires every Jedis generation; source closure remains the
responsibility of the source bean. Dynamic consumers inject
the managed resource and call `execute(...)`, `executeAsync(...)`, or acquire an explicit lease.
They never inject a `JedisPooled` reference which would become stale after replacement.

## Configuration Management

| Type | Audience | Contract decision |
| --- | --- | --- |
| `ConfigurationSource<C>` and `Subscription` | Configuration-source implementations | Minimal pull-snapshot and change-signal SPI |
| `ConfigurationSourceStatus` | Applications and observability adapters | Secret-free state, revision, failure-stage, and monotonic failure/recovery counters |
| `ConfigurationSnapshot<C>` | Configuration-source implementations | Complete typed configuration with source-local monotonic revision |
| `MutableConfigurationSource<C>` | Tests and programmatic applications | Concrete in-memory source, not a base class |

These types are published by `steward-control-configuration-management-core` under
`yunqi.zhibei.steward.control.configuration`. They do not depend on the resource replacement
engine, so startup-only monitoring and future configuration adapters share the same contract.

`PropertiesFileConfigurationSource<C>` is an optional source adapter in
`steward-control-configuration-management-file-properties`. It reads and watches one Java
`.properties` file, invokes an application-owned `Loader<C>`, and publishes complete typed
snapshots. It does not parse YAML, bind Spring Boot properties, or resolve secrets on its own.
Its status reports `AVAILABLE`, `UNAVAILABLE`, or `CLOSED` and never includes a path, raw content,
typed configuration, throwable, or exception message. The Nacos 3 source follows the same status
contract. `steward-configuration-source-testkit` contains the reusable source contract checks for
binding authors.

## Resource Refresh

| Type | Audience | Contract decision |
| --- | --- | --- |
| `ManagedResource<T,C>` | Applications | Stable same-native-type owner and reconciliation entry point |
| `ManagedResource.Builder<T,C>` | Applications | Requires a `ConfigurationSource` and `ResourceBinding` at creation |
| `ManagedResource.Lease<T>` and `ResourceOperation<T,R,E>` | Applications | Stable scoped access without exposing a native reference directly |
| `ManagedResourceStatus` and `Lifecycle` | Applications and observability adapters | Engine-created, secret-free control-plane snapshot |
| `FailureSnapshot` and `Stage` | Applications and observability adapters | Engine-created redacted failure value; no public constructor |

The Builder may override only the health probe, close wait timeout, and caller-owned lifecycle event
buffer. Creation and closure always
come from the required `ResourceBinding`; accepting independent callbacks would allow a
startup-only binding to bypass the overlap-safety marker.

`ManagedResourceStatus` and `FailureSnapshot` are final classes with non-public constructors. This
keeps creation under engine control and permits compatible additive diagnostics later. They are not
records because a record exposes its canonical constructor and fixes its component list as public
API. One status instance is an internally consistent lifecycle snapshot: active identity and lease
count, desired revision, replacement state, and refresh outcome are committed together. It may be
stale immediately after return and is not a synchronization barrier.

`ManagedResource.awaitIdle(timeout)` is the stable completion barrier. Its name reflects that it
waits for candidate work, reconciliation, deferred refresh, and generation retirement rather than
only resource closure. `ManagedResourceStatus.replacementInProgress()` covers that same lifecycle
work without claiming that a second native resource has already been created. Idle does not mean
converged: after a failed replacement it may be true while the desired revision is greater than the
active revision.

`ManagedResource` deliberately does not return the active typed configuration. Configuration may
contain resolved credentials, and active/desired revision identifiers are sufficient for lifecycle
coordination without exposing or duplicating that secret-bearing object in a generation.

Lifecycle task dispatch and time capture are internal engine details. Production uses named virtual
threads and the system clock; no executor, scheduler, or clock customization is exposed by the
public Builder contract.

Failure stages describe lifecycle work only. `CANDIDATE_HEALTH_CHECK` is the mandatory
pre-publication probe, while `REFRESH_ENGINE` is reserved for otherwise unclassified fatal
coordination errors. A demand-time `health()` result or exception is returned directly and is not
retained as refresh state. `FailureSnapshot.generation()` and `revision()` correlate failures
without retaining native objects or configuration. Their value is `0` when that identity was not
assigned or cannot be determined reliably; resource creation failures therefore have a revision
but no generation.

`ConfigurationSnapshot` is final, uses a static factory, and deliberately omits configuration data
from `toString()`. Its positive `long` revision is local to one source instance. New desired states
increase it strictly; an equal revision is a duplicate observation and a lower revision is stale.
`ManagedResource` ignores both without consulting configuration `equals()`. This keeps ordering in
the core while leaving Nacos, Apollo, or another provider's opaque revision representation inside
its adapter.

`ConfigurationSource.snapshot()` may throw a runtime exception when no complete typed snapshot is
available. Failure during initial reconciliation aborts startup and closes the installed
subscription; a later failure leaves the active resource serving. A notification is only a hint to
re-read `snapshot()` and may be coalesced, concurrent, or reentrant. `Subscription.close()` is
idempotent; after it returns no new callback invocation may begin, though one already running may
finish. Provider adapters belong in independent integration modules, never in a binding.

## Steward Restart

| Type | Audience | Contract decision |
| --- | --- | --- |
| `RestartRequiredMonitor<C>` | Startup-only applications | Observes whether a newer complete revision requires process replacement |
| `RestartRequiredStatus` and `State` | Applications and deployment control planes | Engine-created, secret-free `CURRENT`, `RESTART_REQUIRED`, or `CLOSED` snapshot |
| `RestartRequiredFailure` and `Stage` | Applications and observability adapters | Engine-created redacted source-read or subscription-close failure |

The monitor exposes `watch(source, appliedRevision)`, an additive overload accepting a lifecycle
event buffer, `status()`, and idempotent `close()`.
`appliedRevision` must come from the snapshot used to start the resource and from the same source
instance. The monitor subscribes before its first read, so an update during native resource startup
is not lost. Equal and lower revisions are ignored without comparing configuration objects.

`RestartRequiredStatus` and `RestartRequiredFailure` are final, engine-created values with no public
constructors. One status object is internally consistent and retains only revisions and redacted
failure metadata. The monitor reads a typed snapshot only long enough to obtain its revision; it does
not retain the configuration. A source failure preserves the last observed requirement, and a
successful complete read clears that failure.

There is deliberately no acknowledge, restart, exit, scheduler, listener, Kubernetes, or deployment
API. Only a newly started process which actually applies the newer snapshot creates a `CURRENT`
monitor for that revision. Deployment systems may poll or export the status, but the Java library
does not claim that status observation itself performs a rollout.

## Binding Testkit

`steward-binding-testkit` is a test-scope artifact for binding authors, not an application runtime API.
`BindingContract.verify(...)` accepts a real `ResourceBinding`, two offline-safe complete
configurations, and the exact secret values which their diagnostic strings must omit. It verifies
resource creation, failed candidate cleanup, owner-level idempotent close, configuration redaction,
and rollback of an unhealthy refresh candidate. The testkit replaces only the health probe so
contract tests do not require an external middleware service; resource creation and closure still
run through the real binding.
