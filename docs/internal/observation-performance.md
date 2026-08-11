# Observation Performance Baseline

This baseline verifies the disabled vendor-neutral observation path before any adapter is added. It
is a paired comparison, not a portable latency promise: compare `OMITTED` and `EXPLICIT_NOOP` from
the same run on the same host.

## Environment

| Item | Value |
| --- | --- |
| Date | 2026-08-08 |
| Hardware | Apple M4, 10 logical CPUs |
| OS | macOS 26.0.1, Darwin arm64 |
| JVM | Eclipse Temurin 21.0.10+7-LTS |
| Harness | JMH 1.37 |
| Run | 3 x 500 ms warmup, 5 x 500 ms measurement, 2 forks, GC profiler |

The checked-in raw result is
[`apple-m4-temurin-21.0.10.json`](../../benchmarks/observation/results/apple-m4-temurin-21.0.10.json).

## Reproduction

Build the executable benchmark JAR:

```bash
mvn -pl steward-benchmark-observation -am package
```

Run the canonical baseline from the repository root:

```bash
java -jar benchmarks/observation/target/steward-benchmark-observation.jar \
  -wi 3 -i 5 -w 500ms -r 500ms -f 2 -prof gc \
  -rf json \
  -rff benchmarks/observation/results/apple-m4-temurin-21.0.10.json
```

The business-path benchmark forks include `-XX:-DoEscapeAnalysis`. This keeps the pre-existing
lease allocation visible and stable in every paired trial. Without that control, HotSpot may
scalar-replace the lease in one fork but not another, obscuring the observation comparison. The
flag is benchmark-only and is not a production JVM requirement.

## Method

`ManagedResourceBusinessPathBenchmark` uses one benchmark method per operation and a JMH `@Param`
for the two setup modes. `OMITTED` leaves builder observation at its default; `EXPLICIT_NOOP`
selects `LifecycleEventBuffer.noop()`. `DisabledObservationPathTest` independently verifies that
both modes store the same singleton. The benchmark covers `acquire` plus close, synchronous
`execute`, and `executeAsync`; none of these methods emits lifecycle events.

`ManagedResourceLifecycleBenchmark` compares construction plus close and a complete manual
reconciliation. `LifecycleEventBufferBenchmark` measures no-op publication, accepted
publication/drain, a full-buffer drop, and three-producer contention. All allocation values below
come from JMH's GC profiler.

`LifecycleEventFanOutBenchmark` is the enabled-path complement. It publishes and fully drains 256
events with `1`, `2`, or `4` fixed branches and distributor batches of `1`, `16`, or `64`. This
benchmark intentionally includes fan-out startup, final distribution, and shutdown; it is reported
separately and never replaces the disabled business-path pairs.

The checked-in Phase 13 characterization used two 300 ms warmups, three 300 ms measurements, and
one fork. Raw JSON is
[`apple-m4-temurin-21.0.10-fanout.json`](../../benchmarks/observation/results/apple-m4-temurin-21.0.10-fanout.json).

| Distributor batch | 1 branch | 2 branches | 4 branches |
| ---: | ---: | ---: | ---: |
| 1 | 83.465 ns/event | 95.268 ns/event | 123.383 ns/event |
| 16 | 81.462 ns/event | 90.210 ns/event | 107.282 ns/event |
| 64 | 79.956 ns/event | 86.661 ns/event | 104.841 ns/event |

These values include fan-out construction, 256 source publications, complete distribution and
branch draining, and idempotent shutdown, normalized by event. They show the expected bounded cost
increase with branch count; they do not measure exporter work or promise production latency.

## Results

Business-path pairs:

| Operation | Omitted | Explicit NOOP | Omitted allocation | NOOP allocation |
| --- | ---: | ---: | ---: | ---: |
| Acquire and close | 30.693 ns/op | 30.432 ns/op | 16.002 B/op | 16.002 B/op |
| Execute | 31.594 ns/op | 32.782 ns/op | 16.002 B/op | 16.003 B/op |
| Execute async | 43.251 ns/op | 43.552 ns/op | 56.003 B/op | 56.003 B/op |

All three latency confidence intervals overlap. Allocation is equal within profiler resolution;
the allocations shown are the existing lease and asynchronous completion machinery, not events or
queues.

Lifecycle pairs:

| Operation | Omitted | Explicit NOOP | Omitted allocation | NOOP allocation |
| --- | ---: | ---: | ---: | ---: |
| Construct and close | 9.543 us/op | 9.955 us/op | 1951.470 B/op | 1939.387 B/op |
| Reconcile | 7.789 us/op | 7.945 us/op | 946.651 B/op | 947.146 B/op |

The lifecycle confidence intervals overlap. These operations include owner, source, scheduler, and
generation lifecycle work; the small allocation variation is within the reported run uncertainty
and is not on the native client business path.

Buffer reference costs:

| Operation | Time | Allocation |
| --- | ---: | ---: |
| NOOP publish | 0.485 ns/op | approximately 0 B/op |
| Full-buffer drop | 7.513 ns/op | approximately 0 B/op |
| Accepted publish and drain | 11.450 ns/op | 68.375 B/op |
| Contended producer | 69.656 ns/op | 0.110 B/op across the grouped run |

## Bytecode Audit

Inspect the compiled business methods with:

```bash
javap -classpath yunqi-steward-control-plane/resource-management/refresh/target/classes -c -p \
  yunqi.zhibei.steward.control.resource.refresh.ManagedResource \
  | rg -n -A45 -B3 'execute\(|executeAsync\(|acquire\('
```

`acquire`, `execute`, and `executeAsync` contain no reference to `LifecycleEvent`,
`LifecycleEventBuffer`, timing, or publication. Observation branches remain confined to lifecycle
transitions. Disabled observation therefore adds no work to each native client operation.

## Regression Policy

- Compare paired modes from one invocation; do not gate on absolute nanoseconds from another host.
- Business-path normalized allocation must match for each pair within profiler resolution.
- Business-path latency confidence intervals should overlap. Investigate a non-overlap before
  accepting a change, using more forks or longer iterations when host noise is suspected.
- Keep JMH and its transitive dependencies confined to `steward-benchmark-observation`.
- Record enabled fan-out measurements by branch count and batch size; do not compare them to the
  no-op publication number as though they represented the same work.
- Any observation reference added to `acquire`, `execute`, or `executeAsync` fails the bytecode
  boundary even if a particular microbenchmark run appears fast.
