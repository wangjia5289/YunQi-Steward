# Optional Observation Adapters

Four optional adapters validate the vendor-neutral observation boundary with different consumption
models:

```text
secret-free status snapshots --scrape thread--> Micrometer

LifecycleEventBuffer --one adapter daemon thread--> JFR, OpenTelemetry, or SLF4J

LifecycleEventBuffer --fan-out daemon--> independent bounded adapter buffers
                                         |--> JFR correlation events
                                         |--> OpenTelemetry root spans
                                         +--> SLF4J structured records
```

No adapter is a dependency of `steward-observation`, `steward-lifecycle`, `steward-refresh`,
`steward-restart`, or any binding. Applications add only the adapter they use.

This page documents how to use the current modules. The reasons for maintaining this reference set,
the admission rules for future adapters, and the distinction between observation APIs and final
destinations are documented in
[`observation-adapter-strategy.md`](observation-adapter-strategy.md).

## Micrometer

Add the optional module:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-observability-micrometer</artifactId>
    <version>0.1.0</version>
</dependency>
```

Register one fixed meter set for each owner:

```java
LifecycleEventBuffer events = LifecycleEventBuffer.create(256);
ManagedResource<RedisClient, RedisConfiguration> redis = ManagedResource
        .builder(configurationSource, redisBinding)
        .lifecycleEvents(events)
        .build();

var metrics = MicrometerLifecycleMetrics.bind(
        meterRegistry, "orders-cache", redis, events);
```

The owner name must be a stable, non-sensitive ASCII identifier. It accepts only letters, digits,
dots, underscores, and hyphens and becomes the `owner` tag. The adapter adds only the fixed `kind`
and one-hot `state` tags; it accepts no arbitrary or event-derived tag map.

The adapter registers these bounded meter families as applicable:

| Meter | Kind | Semantics |
| --- | --- | --- |
| `middleware.lifecycle.state` | all | Fixed one-hot owner state gauges |
| `middleware.lifecycle.events.buffered` | all | Current source-buffer depth |
| `middleware.lifecycle.events.dropped` | all | Monotonic source publication losses |
| `middleware.lifecycle.active.generation` | managed | Active native generation |
| `middleware.lifecycle.active.revision` | managed | Applied configuration revision |
| `middleware.lifecycle.desired.revision` | managed/restart | Highest observed revision |
| `middleware.lifecycle.active.leases` | managed | Current leases |
| `middleware.lifecycle.replacement.active` | managed | Replacement or retirement in progress |
| `middleware.lifecycle.refresh.pending` | managed | Coalesced refresh work pending |
| `middleware.lifecycle.refresh.successes` | managed | Monotonic successful replacements |
| `middleware.lifecycle.refresh.failures` | managed | Monotonic failed replacements |
| `middleware.lifecycle.close.failures` | managed | Bounded retained close-failure count |
| `middleware.lifecycle.applied.revision` | restart | Running process revision |
| `middleware.lifecycle.restart.required` | restart | Rolling restart requirement |
| `middleware.lifecycle.observation.failure` | restart | Redacted monitor failure present |

Fan-out and adapter-delivery health use a separate fixed namespace. These meters describe whether
lifecycle telemetry itself is keeping up; they are not application or resource health signals:

| Meter | Tags | Semantics |
| --- | --- | --- |
| `middleware.lifecycle.pipeline.source.buffered` | `owner`, `kind=fanout` | Current fan-out source depth |
| `middleware.lifecycle.pipeline.source.dropped` | `owner`, `kind=fanout` | Monotonic loss before fan-out admission |
| `middleware.lifecycle.pipeline.source.drained` | `owner`, `kind=fanout` | Monotonic source events distributed |
| `middleware.lifecycle.pipeline.branch.buffered` | plus `branch` | Current branch depth |
| `middleware.lifecycle.pipeline.branch.delivered` | plus `branch` | Monotonic copies admitted to that branch |
| `middleware.lifecycle.pipeline.branch.dropped` | plus `branch` | Monotonic copies rejected by that branch |
| `middleware.lifecycle.pipeline.adapter.drained` | `owner`, `kind=adapter`, `adapter` | Monotonic adapter input events removed |
| `middleware.lifecycle.pipeline.adapter.successes` | same | Monotonic adapter conversions completed |
| `middleware.lifecycle.pipeline.adapter.failures` | same | Monotonic adapter-side failures |
| `middleware.lifecycle.pipeline.adapter.source.dropped` | same | Monotonic input-buffer losses visible to the adapter |
| `middleware.lifecycle.pipeline.adapter.closed` | same | `1` after close and final drain, otherwise `0` |

Register fan-out once and each adapter independently. No adapter module depends on Micrometer:

```java
var fanOutMetrics = MicrometerLifecycleMetrics.bind(
        meterRegistry, "orders-cache", fanOut);
var jfrMetrics = MicrometerLifecycleMetrics.bind(
        meterRegistry, "orders-cache", "jfr", jfr);
var otelMetrics = MicrometerLifecycleMetrics.bind(
        meterRegistry, "orders-cache", "otel", telemetry);
var logMetrics = MicrometerLifecycleMetrics.bind(
        meterRegistry, "orders-cache", "logs", logging);
```

`owner`, `adapter`, and fan-out `branch` values are validated startup identities with a maximum of
128 ASCII characters. Branch cardinality is exactly the immutable branch map supplied at startup.
Pipeline counters retain their last monotonic value if a weakly referenced source disappears;
gauges return no stale live state. A duplicate pipeline meter identity is rejected instead of
silently sharing meters. Closing a registration removes every meter it created and is idempotent.

A bound, managed, or restart registration creates exactly 6, 16, or 9 meters. Meter callbacks run
on the registry's scrape thread. They weakly reference the owner and read only `state()` or
`status()`; the registration does not retain a configuration or native client and starts no thread.
Each `(owner, kind)` pair must have one registration. Closing its idempotent handle removes that
fixed meter set but does not close the owner or event buffer.

## JFR

Add the JDK-only optional module:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-observability-jfr</artifactId>
    <version>0.1.0</version>
</dependency>
```

Start the adapter before the owner so the startup event can be observed. Java resource-close order
then closes metrics first, the owner second, and JFR last:

```java
LifecycleEventBuffer events = LifecycleEventBuffer.create(256);

try (var jfr = JfrLifecycleAdapter.start(events, "orders-cache");
        var redis = ManagedResource.builder(configurationSource, redisBinding)
                .lifecycleEvents(events)
                .build();
        var metrics = MicrometerLifecycleMetrics.bind(
                meterRegistry, "orders-cache", redis, events)) {
    // Use the native client through redis.
}
```

One `JfrLifecycleAdapter` has exclusive draining ownership of one enabled buffer. Its daemon thread
drains bounded batches and commits `yunqi.zhibei.steward.Lifecycle` events. The JFR payload contains only
the safe registration name plus sequence, stage, outcome, generation, revision, lifecycle start,
lifecycle duration, and failure type. It contains no configuration, native object, throwable,
message, endpoint, or credential. CPU, allocation, and lock profiling remain JFR or profiler
responsibilities; this event supplies lifecycle correlation only.

The lifecycle publisher still performs only the buffer's non-waiting write. Slow JFR processing can
increase `LifecycleEventBuffer.droppedEvents()` but cannot block startup, refresh, rollback, or
close. `JfrLifecycleAdapter.close()` first seals the buffer, wakes the worker, and waits for a final
drain. `drainedEvents`, `forwardedEvents`, `commitFailures`, and `sourceDroppedEvents` account for
adapter delivery and pre-drain losses. Calls to close are concurrent and idempotent.

## OpenTelemetry

Add the API-only adapter module; the application remains responsible for its OpenTelemetry SDK,
span processor, sampler, and exporter:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-observability-opentelemetry</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
LifecycleEventBuffer events = LifecycleEventBuffer.create(256);
Tracer tracer = openTelemetry.getTracer("middleware-lifecycle");

try (var telemetry = OpenTelemetryLifecycleAdapter.start(
        events, "orders-cache", tracer)) {
    // Construct and close the lifecycle owner inside this scope.
}
```

Each fact becomes an `INTERNAL` span named `middleware.lifecycle.<stage>`. The adapter preserves the
event's original start time and duration, sets failed outcomes to `ERROR`, and uses only these fixed
attributes:

| Attribute | Source |
| --- | --- |
| `middleware.owner` | Safe static registration name |
| `middleware.sequence` | Buffer acceptance order |
| `middleware.stage` | Neutral lifecycle stage |
| `middleware.outcome` | Success or failure |
| `middleware.generation` | Native generation |
| `middleware.revision` | Configuration revision |
| `middleware.failure.type` | Class name or synthetic type, failures only |

Lifecycle events carry no request context, so the adapter calls `setNoParent()` rather than
manufacturing a relationship with whichever context happens to be current on its worker. It never
records an exception or status description. SDK exporter failures remain SDK responsibility;
`drainedEvents`, `endedSpans`, `spanFailures`, and `sourceDroppedEvents` account for adapter work.

## SLF4J

Add the API-only logging adapter. The application chooses and configures the SLF4J provider:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-observability-slf4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
LifecycleEventBuffer events = LifecycleEventBuffer.create(256);
Logger logger = LoggerFactory.getLogger("middleware.lifecycle");

try (var logging = Slf4jLifecycleAdapter.start(
        events, "orders-cache", logger)) {
    // Construct and close the lifecycle owner inside this scope.
}
```

Success uses `INFO` and failure uses `WARN`. Both use one fixed parameterized template containing
owner, sequence, stage, outcome, generation, revision, start epoch milliseconds, duration
nanoseconds, and failure type. The adapter never calls a throwable overload and never formats an
exception message. Logger exceptions increment `logFailures`; accepted events continue to drain.

## Multiple Event Adapters

Use `LifecycleEventFanOut` when the same lifecycle facts must become JFR events, OpenTelemetry spans,
and SLF4J records in the same process:

```java
LifecycleEventFanOut fanOut = LifecycleEventFanOut.start(
        256,
        Map.of("jfr", 256, "otel", 256, "logs", 256));

var jfr = JfrLifecycleAdapter.start(fanOut.branch("jfr"), "orders-cache");
var telemetry = OpenTelemetryLifecycleAdapter.start(
        fanOut.branch("otel"), "orders-cache", tracer);
var logging = Slf4jLifecycleAdapter.start(
        fanOut.branch("logs"), "orders-cache", logger);

try (var redis = ManagedResource.builder(configurationSource, redisBinding)
        .lifecycleEvents(fanOut.source())
        .build()) {
    // Use the native client through redis.
} finally {
    fanOut.close();
    logging.close();
    telemetry.close();
    jfr.close();
}
```

The lifecycle owner performs one non-waiting publication to the source. Fan-out work runs on its
own daemon thread. Each branch has independent capacity, delivery count, and drop count; a slow
branch cannot delay another branch. Branch events retain source sequence numbers, so branch losses
remain visible as gaps.

Close the lifecycle owner first, fan-out second, and adapters last. `fanOut.close()` seals and drains
the source, distributes all retained source events, and seals the branches. Each adapter then drains
its sealed branch. Closing an adapter before fan-out is supported as a failure case, but later copies
for that branch are counted as dropped.

Micrometer registration handles may close after their observed owner, fan-out, or adapter so a final
scrape can see terminal counts. They do not participate in source draining and never change the
required owner -> fan-out -> adapters shutdown order.

## Runnable Example

[`examples/observation-e2e`](../examples/observation-e2e) is a maintained, external-service-free
composition example. It uses a safe fake native client and exercises startup, one successful
replacement, one unhealthy-candidate rollback, and close. Its smoke test records one run and proves
that Micrometer counters, SLF4J calls, OpenTelemetry spans, and JFR markers all account for the same
fan-out source sequence.

Run the example from the repository root after packaging the reactor:

```bash
mvn -pl examples/observation-e2e -am package
mvn -pl examples/observation-e2e exec:java \
  -Dexec.mainClass=yunqi.zhibei.steward.example.ObservationExample
```

The example intentionally supplies only the facade/API defaults. Production SDK configuration,
Micrometer registry export, SLF4J provider selection, OpenTelemetry sampling and exporting, and JFR
recording settings remain application concerns. The source and all three branch capacities are 128
because the scenario is low frequency; production values should cover the expected lifecycle burst
while retaining the documented drop-on-overload behavior.

## Consumer Ownership

Micrometer does not remove events and may observe a directly consumed buffer or a fan-out source.
JFR, OpenTelemetry, and SLF4J remain exclusive draining consumers of their individual input. Attach
exactly one event adapter directly to a buffer, or make fan-out the exclusive source consumer and
attach exactly one adapter to each branch.

## Shutdown Contract

Without fan-out, close lifecycle owners before their JFR, OpenTelemetry, or SLF4J adapter. With
fan-out, use owner, fan-out, adapter order. Once an event adapter closes and seals its input, later
publication or distribution to that input is intentionally rejected. The Micrometer registration
may be closed before or after its owner; close it before reusing the same `(owner, kind)` identity
in a registry.
