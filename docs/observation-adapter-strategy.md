# Observation Adapter Strategy

## Purpose

The project observes four concerns: logs, metrics, traces, and performance profiling. Those concerns
are not four APIs that the lifecycle core must implement. The core records two safe forms of fact:

```text
status snapshots
    current state, revisions, leases, and bounded counters

lifecycle events
    startup, observation, refresh, rollback, restart requirement, and close
```

Optional adapters project those facts into an observation API. The application and that API's
ecosystem decide where the resulting signal is stored, exported, or displayed:

```text
Status ---------> Micrometer adapter ---------> registry/exporter ---------> metric platform

LifecycleEvent -> SLF4J adapter --------------> logging provider ----------> log platform
               -> OpenTelemetry adapter ------> SDK/exporter --------------> trace platform
               -> JFR adapter ----------------> JFR recording -------------> JMC or tooling
```

This document governs which adapters the project should provide. It separates three decisions that
must not be conflated:

1. **Fact:** what happened in the managed lifecycle.
2. **Projection:** how that fact is represented as a metric, log record, span, or JFR event.
3. **Destination:** which product stores, queries, or visualizes the projected signal.

The neutral contract owns only the first decision. An adapter owns the second. Application
configuration and downstream tooling own the third.

These adapters project managed-resource lifecycle facts only. They do not collect an application's
general logs, request metrics, business traces, CPU samples, allocations, or heap state.

## Why The Initial Four

The four current adapters are a deliberately small reference set, not a claim that only four
adapters can ever exist.

| Adapter | Observation concern | Why it is a useful reference boundary |
| --- | --- | --- |
| Micrometer | Metrics | Polls immutable status instead of draining events; reaches the common Java metrics ecosystem |
| SLF4J | Logs | Converts discrete events to fixed records while leaving the logging provider to the application |
| OpenTelemetry | Traces | Converts events with original timing into spans while leaving SDK and exporter policy outside |
| JFR | Profiling correlation | Uses a JDK-native event stream and adds lifecycle markers without implementing a profiler |

Together they test materially different models: polling, record emission, timed spans, and JVM
events. Supporting all four without adding product objects or arbitrary attributes to
`LifecycleEvent` is evidence that the core boundary is neutral.

OpenTelemetry can also represent logs and metrics, but it is not the only observation stack used by
Java applications. Providing Micrometer and SLF4J projections avoids forcing an OpenTelemetry SDK
into applications that already standardize on those APIs. This is intentional ecosystem coverage,
not a requirement to enable every adapter.

The JFR adapter has narrower scope than the other three. It records correlation markers such as
refresh and rollback. CPU samples, allocation profiles, lock contention, and heap analysis remain
the responsibility of JFR, async-profiler, or an APM agent.

## Are Four Adapters Sufficient?

They are sufficient as the current first-party baseline for common Java deployments. They are not
an exhaustive allowlist and applications do not need to enable all four.

Do not add a project adapter for every destination already supported downstream. For example:

| Destination | Preferred route | New project adapter? |
| --- | --- | --- |
| Prometheus | Micrometer Prometheus registry | No |
| Datadog or CloudWatch metrics | Corresponding Micrometer registry | No |
| Logback or Log4j2 | SLF4J provider | No |
| Loki or an ELK pipeline | Configured logging provider or agent | No |
| Jaeger, Tempo, or an OTLP service | OpenTelemetry SDK exporter | No |
| JDK Mission Control | JFR recording | No |

A new adapter is justified only when a real integration cannot be reached through an existing
adapter and its downstream ecosystem. Plausible examples include a company-owned telemetry API,
JMX status exposure, or a lifecycle-event transport with semantics that the current projections
cannot provide.

## Adapter Admission Rules

Before adding a first-party adapter, answer all of these questions:

1. **Demand:** Is there a concrete deployment or integration that needs it?
2. **Integration gap:** Is an existing route through Micrometer, SLF4J, OpenTelemetry, JFR, an
   agent, or exporter configuration unavailable or materially insufficient?
3. **Neutral input:** Can the adapter operate on existing status and `LifecycleEvent` fields?
4. **Dependency isolation:** Can every third-party type and dependency remain in one optional
   adapter module without changing lifecycle or binding runtime graphs?
5. **Lifecycle isolation:** Can all conversion and I/O occur outside lifecycle threads with bounded
   memory and without waiting in publication paths?
6. **Safe schema:** Can the adapter use a fixed, documented, secret-free schema with bounded metric
   cardinality and no configuration, endpoint, message, throwable, or native-client capture?
7. **Ownership:** Are exclusive/shared consumption, shutdown order, final drain, and loss accounting
   explicit?
8. **Verification:** Are redaction, ordering, overflow, exporter failure, concurrent close, and API
   boundary tests practical?

If question 2 is answered "no", configure the existing ecosystem instead of adding an adapter. If
questions 3 through 8 cannot be answered "yes", the proposed adapter must not enter the core or be
published as a first-party module.

Do not expand `LifecycleEvent` merely because one product supports another label, context object, or
payload. A contract addition needs a product-independent lifecycle meaning and at least two credible
consumers. Product-specific identity and fixed labels belong at adapter registration.

## What Is Not An Adapter

The following concerns stay outside this adapter set:

- Alert rules and dashboards consume telemetry after export.
- Log formatting, rotation, and transport belong to the configured logging provider or agent.
- Sampling, batching, propagation, and export belong to the OpenTelemetry SDK or APM agent.
- CPU, allocation, lock, and heap profiling belong to profiling tools.
- Health endpoints and rollout automation expose or act on status through their own application
  integration; they must not be hidden inside an observation adapter.

## Isolated Event Fan-Out

An event buffer has one draining owner. Micrometer can poll beside it. When JFR, OpenTelemetry, and
SLF4J must run together, `LifecycleEventFanOut` owns the source and distributes each retained event
to independent branch buffers:

```text
LifecycleEventBuffer -> neutral fan-out -> bounded JFR input
                                      \-> bounded OpenTelemetry input
                                      \-> bounded SLF4J input
```

The implementation preserves these properties:

- Lifecycle publication remains one non-waiting operation and never performs adapter work.
- Each sink has independent bounded capacity and drop accounting.
- A slow or failed sink cannot delay another sink or lifecycle work.
- Each sink observes retained events in source sequence order.
- Closing is concurrent and idempotent, with a documented final-drain protocol.
- Source losses and per-sink delivery losses remain distinguishable.
- Disabled observation remains queue-free, thread-free, and allocation-free on business paths.

Fan-out is infrastructure, not a fifth observation adapter. Its contract, concurrency tests, and
three-adapter composition test are complete. Further first-party destinations still require a
concrete integration gap and must pass the admission rules above.

## Project Policy

The project will maintain Micrometer, SLF4J, OpenTelemetry, and JFR as the initial first-party
adapter set. Applications may implement other adapters directly against the public neutral
contract. New first-party adapters are demand-driven and must satisfy the admission rules above.
The core will not standardize on one telemetry product and will not accumulate destination-specific
modules when an existing ecosystem bridge already exists.
