# Vendor-Neutral Observation Contract

Lifecycle ownership exposes two different kinds of safe observation:

```text
BoundResource state / ManagedResourceStatus / RestartRequiredStatus
    current state, revisions, leases, and bounded counters
                         |
                         +-- polled by metric adapters

LifecycleEventBuffer -> LifecycleEvent
    discrete startup, observation, refresh, rollback, requirement, and close facts
                         |
                         +-- drained by one event adapter
                         |
                         +-- or LifecycleEventFanOut -> independent adapter branches

LifecycleEventDelivery
    monotonic adapter drain/success/failure/loss counts plus terminal closed state
                         |
                         +-- polled by optional pipeline-health adapters
```

`steward-observation` has no third-party runtime dependency. Lifecycle modules depend only on this
project-owned contract. A Micrometer, OpenTelemetry, SLF4J, JFR, or platform-specific dependency is
allowed only in its optional adapter module.

## Event Shape

`LifecycleEvent` is final and engine-created. It contains exactly:

| Field | Meaning |
| --- | --- |
| `sequence` | Buffer-local acceptance order, starting at 1 |
| `stage` | `START`, `OBSERVE`, `REFRESH`, `ROLLBACK`, `RESTART_REQUIRED`, or `CLOSE` |
| `outcome` | `SUCCESS` or `FAILURE` |
| `generation` | Managed native generation; `0` when unavailable or not applicable |
| `revision` | Source-local configuration revision; `0` when unavailable or not applicable |
| `startedAt` | Wall-clock start instant for correlation |
| `duration` | Non-negative monotonic elapsed time |
| `failureType` | Exception class name or documented synthetic value; present only for failure |

The contract deliberately has no resource name, arbitrary tag map, configuration value, endpoint,
native client reference, throwable, exception message, trace/span object, logger, or vendor event.
An application which observes multiple owners associates one buffer or adapter registration with
each safe external resource identity; that identity does not enter the core event.

## Lifecycle Audit

| Owner | Lifecycle point | Event |
| --- | --- | --- |
| `BoundResource` | Native create and mandatory startup probe complete | `START/SUCCESS` |
| `BoundResource` | Create or startup probe fails after candidate cleanup | `START/FAILURE` |
| `BoundResource` | The single native close attempt completes or fails | `CLOSE` |
| `ManagedResource` | Initial snapshot, candidate probe, and publication settle | `START` |
| `ManagedResource` | A later reconciliation publishes or rejects a candidate | `REFRESH` |
| `ManagedResource` | An unpublished candidate is closed | `ROLLBACK` |
| `ManagedResource` | A published generation is closed after drain | `CLOSE` |
| `RestartRequiredMonitor` | Subscription ownership is installed | `START/SUCCESS` |
| `RestartRequiredMonitor` | A source read fails | `OBSERVE/FAILURE` |
| `RestartRequiredMonitor` | Desired revision first passes applied revision | `RESTART_REQUIRED/SUCCESS` |
| `RestartRequiredMonitor` | Its one subscription-close attempt settles | `CLOSE` |

Duplicate and stale source observations do not emit requirement or refresh-success events. A restart
requirement emits once because the old process cannot return to `CURRENT`. Idempotent owner close
does not emit duplicate close events. Managed generation closes can run asynchronously, so events
from causally independent generation cleanup reflect the order in which the buffer accepted them.
Within one buffer, accepted `sequence` values and drain order are identical.

An unhealthy candidate uses the synthetic failure type `unhealthy`. All other core-produced failure
types come from `Throwable.getClass().getName()`. Core never reads or stores `getMessage()` and never
places a `Throwable` into an event.

## Buffer And Backpressure

`LifecycleEventBuffer.noop()` is the default. It has no queue, thread, timestamp capture, event
allocation, or drop accounting. Owners check it before observation-only state reads or timing. No
event is emitted from `ManagedResource.acquire`, `execute`, `executeAsync`, or a native SDK business
operation.

`LifecycleEventBuffer.create(capacity)` owns a fixed-size in-memory queue but no consumer thread.
Publication uses a non-waiting lock attempt. Full capacity or momentary producer/consumer contention
drops the new event and increments `droppedEvents`; lifecycle work continues normally. Sequence is
assigned only after source admission, so events retained by one directly published buffer are
gap-free even when other publication attempts were dropped. Fan-out branches preserve the source
sequence instead of renumbering; a branch-specific loss is therefore visible as a gap.

Adapters may call `poll()` or bounded `drain(maximum)` from their own thread. Closing the buffer is
idempotent, stops publication as a no-op, and preserves accepted events for final draining. Owners
do not own or close a supplied buffer, allowing an adapter to drain final events after owner close.

## Fan-Out And Branch Isolation

`LifecycleEventFanOut` is the neutral composition mechanism for several event adapters. It owns one
bounded source buffer, one daemon distribution thread, and a fixed set of independently bounded
branch buffers. Lifecycle owners publish only to `source()`, so publication cost remains one
non-waiting buffer offer regardless of branch count.

The distributor drains source events in sequence order and performs one non-waiting offer per
branch outside lifecycle threads. A full, contended, or prematurely closed branch loses only its
own copy. `deliveredEvents(branch)` and `droppedEvents(branch)` account for each branch, while
`sourceDroppedEvents()` remains distinct. The same immutable, already-redacted event is shared
between accepting branches; fan-out does not copy configuration or add fields.

Fan-out configuration is fixed at startup. Branch names use the same bounded safe identifier shape
as adapter owner names and are control-plane identities only; they are not added to events. Disabled
applications continue to use `LifecycleEventBuffer.noop()` and create no fan-out queue or thread.

The shutdown order is lifecycle owner, fan-out, then branch adapters. Fan-out close seals and fully
drains the source, finishes branch delivery accounting, and seals every branch. Adapter close then
performs each branch's final drain. Every close operation is concurrent and idempotent.

## Delivery Health

Asynchronous event adapters implement the project-owned `LifecycleEventDelivery` interface. It
contains only `drainedEvents`, `successfulEvents`, `failedEvents`, `sourceDroppedEvents`, and
`isClosed`. The counts are monotonic and the state becomes terminal only after final draining.

This interface deliberately does not expose a logger, tracer, meter registry, exporter, JFR event,
throwable, message, endpoint, configuration, or native client. Micrometer can therefore poll any
conforming adapter without depending on JFR, OpenTelemetry, or SLF4J modules, and those adapters do
not acquire a Micrometer dependency. Adapter-specific aliases such as `endedSpans`, `loggedEvents`,
or `commitFailures` remain convenience diagnostics; the neutral interface is the cross-adapter
health boundary.

## Adapter Rules

Metric adapters should primarily poll immutable status. Event adapters must perform logging,
export, span construction, or JFR commit after draining, never on the lifecycle thread. They must
publish their own exporter failures and queue-loss policy outside the lifecycle core. Product-
specific labels and resource identity belong to adapter registration, not `LifecycleEvent`.

The disabled-path bytecode, allocation, and latency baseline is recorded in
[`observation-performance.md`](observation-performance.md).
The optional Micrometer, JFR, OpenTelemetry, and SLF4J implementations and their distinct ownership
models are documented in [`observability-adapters.md`](observability-adapters.md).
The policy for selecting, rejecting, and extending adapters is documented in
[`observation-adapter-strategy.md`](observation-adapter-strategy.md).
