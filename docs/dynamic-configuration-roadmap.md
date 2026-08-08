# Dynamic Configuration Delivery Roadmap

This document is the ordered implementation checklist for turning the completed lifecycle engine
into a production-usable dynamic-configuration path. Work proceeds in order. A later phase starts
only after the current phase meets its acceptance criteria.

## Fixed Boundaries

- Applications continue to call vendor clients directly through `BoundResource` or scoped
  `ManagedResource` operations. No common Redis, database, messaging, or storage API is introduced.
- A refresh replaces one native client generation with another instance of the same Java type. It
  does not mutate arbitrary fields inside a published vendor client.
- Configuration-center adapters publish only complete immutable typed configurations with a
  source-local monotonic revision.
- Secret lookup remains outside bindings and lifecycle core. The adapter-facing loader may parse
  provider content and resolve secrets before returning a complete typed configuration.
- A failed provider read, parse, validation, or secret lookup must not publish a partial snapshot.
- Startup-only bindings continue to require restart or rolling deployment.

## Phase 1: Nacos 3 Configuration Source

Status: COMPLETED

Create an independent `steward-configuration-nacos3` module which implements `ConfigurationSource<C>` using the
Nacos 3 configuration client. It must:

- install the provider listener before exposing the source;
- load and validate the initial complete typed configuration synchronously;
- assign positive source-local revisions instead of exposing provider-specific versions;
- serialize provider callbacks so one source never publishes revisions out of order;
- publish a revision only after the caller-supplied loader returns a complete configuration;
- retain the previous snapshot when loading fails and surface the failure on `snapshot()`;
- make subscription closure idempotent and prevent new callbacks after close;
- avoid logging or retaining raw provider content in diagnostic values.

Acceptance: unit tests cover initial success/failure, update, duplicate content, loader/secret
failure, callback coalescing, subscription closure, and redacted diagnostics. JAR, sources,
Javadoc, Enforcer, and japicmp gates pass.

## Phase 2: End-To-End Refresh Scenarios

Status: COMPLETED

Exercise the Nacos adapter together with `ManagedResource` and a fake Nacos client boundary. Cover:

- initial configuration failure prevents startup;
- a valid update publishes a new generation;
- parse or secret failure keeps the active generation serving;
- a later valid update recovers without restarting;
- rapid updates converge on the latest complete snapshot;
- an unhealthy candidate rolls back and is closed once;
- adapter and managed-resource shutdown do not publish late work.

Acceptance: all scenarios are deterministic and require no Docker service or thread timing races.

## Phase 3: Refresh-Safe Binding Contract Rollout

Status: COMPLETED

Migrate remaining offline-safe `ResourceBinding` implementations to `steward-binding-testkit` in small
batches. Each binding must prove real creation and closure, unhealthy-candidate cleanup,
owner-level idempotent close, configuration redaction, and refresh rollback. A binding whose native
factory necessarily contacts an external service receives a focused integration fixture instead of
weakening the shared contract.

Acceptance: every refresh-safe binding is either covered by the shared contract or has a documented
reason and an equivalent focused test. Startup-only bindings are tracked separately.

Completion record: 22 refresh-safe bindings use `steward-binding-testkit`; Nacos Client 3 and RabbitMQ
Client 5 use equivalent deterministic focused lifecycle tests because their default native factories
perform connection work during construction. Three startup-only bindings remain outside refresh
(`RocketMQ Client 5`, `PowerJob Worker 5`, and `XXL-JOB Core 2`). See
[`docs/binding-contract-coverage.md`](binding-contract-coverage.md) for the per-binding matrix.

## Phase 4: Vendor Timeout And Health Audit

Status: COMPLETED

Audit every binding's create, health, operation, and close timeout behavior. Record whether the SDK
offers a remote, connection-state, local, or startup-only probe. Add missing finite vendor timeouts
to typed configuration only when the SDK exposes a supported setting.

Acceptance: no binding silently relies on an avoidable infinite probe or shutdown wait; unavoidable
SDK limitations are documented next to the binding and in the health matrix.

Completion record: [`docs/vendor-health-timeout-matrix.md`](vendor-health-timeout-matrix.md) records
all refresh-safe and startup-only probes and timeout boundaries. Kafka, RabbitMQ, Lettuce, and
Redisson now use their SDK finite-close overloads with a 30-second binding limit. A per-generation
typed close value is intentionally not added because `ResourceCloser.close(T)` receives only the
native resource; carrying configuration would require a wrapper, identity map, or common client API.

## Phase 5: Startup-Only Restart Requirement

Status: COMPLETED

Add an independent `steward-restart` module which observes the same complete typed snapshots used
to start a startup-only resource. It must report `RESTART_REQUIRED` when the desired revision moves
past the applied revision without making `StartupBinding` overlap-safe, exiting the JVM, or invoking
a deployment platform. It retains only revisions and redacted failure metadata, never typed
configuration or throwables.

Acceptance: subscription-before-read prevents missed startup-window updates; duplicate and stale
revisions cannot move state backward; source failures retain the last requirement; close is
idempotent; a callback racing close cannot republish live state; public API, sources, Javadoc, and
japicmp gates pass.

Completion record: `RestartRequiredMonitor` exposes only `watch`, `status`, and `close`.
`RestartRequiredStatus` is one immutable control-plane snapshot with `CURRENT`, `RESTART_REQUIRED`,
or `CLOSED` state. Eleven module tests cover the monitor contract and public API, and two Nacos 3
end-to-end tests cover valid update plus failure/recovery delivery. Rollout execution remains owned
by Kubernetes, another orchestrator, or CI/CD.

## Phase 6: Vendor-Neutral Observation Infrastructure

Status: COMPLETED

Add an independent `steward-observation` contract shared by startup, refresh, and restart-required
owners. Keep current state in the existing secret-free status snapshots and represent discrete
lifecycle transitions as `LifecycleEvent`. The contract must contain only stage, outcome,
generation, revision, start time, duration, and failure type. It must not depend on Micrometer,
OpenTelemetry, SLF4J, JFR, or a profiling agent.

Acceptance: a disabled singleton creates no queue or thread; an enabled bounded buffer never waits
on the lifecycle publication path; capacity or lock contention drops telemetry and increments
`droppedEvents`; close makes publication a no-op while preserving accepted events for draining;
configuration, native clients, throwable objects, exception messages, endpoints, and credentials
cannot enter core-produced events.

Completion record: [`docs/observation-contract.md`](observation-contract.md) records the event audit,
field semantics, ordering, redaction, and adapter boundary. `BoundResource`, `ManagedResource`, and
`RestartRequiredMonitor` retain their old no-observation entry points and accept a caller-owned
`LifecycleEventBuffer` only through additive overloads or builder configuration. Buffer and owner
tests cover redaction, order, concurrent idempotent close, overflow, drop counting, and closed/no-op
behavior.

## Phase 7: Observation Performance Baseline

Status: COMPLETED

Add JMH or an equivalently reproducible benchmark around disabled owner construction, lifecycle
reconciliation, and the `acquire`/`execute` business path. The disabled path must not capture time,
allocate an event, create a queue, start a thread, or add work to each native client operation.

Acceptance: benchmark sources, commands, environment metadata, and baseline results are checked in;
the business operation path is bytecode- and allocation-equivalent whether observation is omitted
or the shared no-op buffer is selected explicitly.

Completion record: [`docs/observation-performance.md`](observation-performance.md) documents the
JMH methodology, Apple M4/Temurin 21 baseline, bytecode audit, and same-run regression policy. The
paired business-path trials use identical benchmark methods and deliberately keep the existing
lease allocation visible; omitted and explicit no-op modes have matching normalized allocation and
overlapping latency confidence intervals. JMH remains isolated in `steward-benchmark-observation`.

## Phase 8: Micrometer And JFR Validation Adapters

Status: COMPLETED

Build two independent optional adapters with materially different consumption models. Micrometer
polls status and exports bounded counters and gauges; JFR drains lifecycle events into correlation
markers. Neither adapter may retain configuration, native clients, throwable objects, or messages,
and neither dependency may enter lifecycle or binding runtime graphs.

Acceptance: both adapters consume the same contract without adding product-specific fields or
callbacks to core; slow adapter work cannot block publication; adapter shutdown drains or accounts
for remaining events according to its documented policy.

Completion record: [`docs/observability-adapters.md`](observability-adapters.md) documents the fixed
Micrometer meter sets, weak owner references, JFR event mapping, exclusive buffer ownership, and
shutdown accounting. Micrometer 1.17 polls only existing secret-free status and buffer counters on
the registry thread. The JDK-only JFR adapter drains on its own daemon thread and seals plus fully
drains the buffer during concurrent idempotent close. Recording, cardinality, redaction, ownership,
source-drop, and public API tests pass without changing the neutral contract or lifecycle modules.

## Phase 9: OpenTelemetry And Optional Logging

Status: COMPLETED

After the contract has survived Micrometer and JFR, add optional OpenTelemetry tracing and logging
adapters. Profiling remains the responsibility of JFR, async-profiler, or an APM agent; the JFR
adapter supplies lifecycle correlation markers rather than implementing a sampler.

Acceptance: adapters require no contract expansion for product-specific labels, span objects,
logger instances, messages, endpoints, or credentials.

Completion record: `steward-observability-opentelemetry` maps neutral facts to independent, correctly timed
`INTERNAL` root spans with fixed attributes and no recorded exception. `steward-observability-slf4j` emits a
fixed parameterized record at `INFO` or `WARN` without a throwable argument. Both drain and close on
their own daemon thread, account adapter and source losses, validate safe static owner names, and
leave SDK/exporter/provider configuration to the application. SDK-backed span tests and proxy
logger tests verify timing, root context, redaction, thread isolation, concurrent close, final drain,
and failure accounting without changing `LifecycleEvent`.

## Phase 10: Isolated Lifecycle Event Fan-Out

Status: COMPLETED

Add a neutral fan-out layer so one accepted lifecycle event can reach JFR, OpenTelemetry, and SLF4J
through independent bounded inputs. This is observation infrastructure, not another product
adapter. Adding further first-party adapters is deferred until fan-out is complete and a concrete
integration passes the admission rules in
[`docs/observation-adapter-strategy.md`](observation-adapter-strategy.md).

Acceptance: lifecycle publication remains non-waiting; a slow or failed sink cannot delay lifecycle
work or another sink; retained source order is preserved independently per sink; source and per-sink
losses are distinguishable; shutdown is concurrent, idempotent, and accounts for final draining;
the disabled path remains queue-free, thread-free, and allocation-free on business operations.

Completion record: `LifecycleEventFanOut` owns one bounded source, one daemon distributor, and a
fixed set of independently bounded branches. Lifecycle owners still perform one non-waiting source
publication. Branch delivery reuses the immutable redacted event and preserves its source sequence;
full, contended, or prematurely closed branches have independent delivery and drop counters. Close
seals and drains the source before sealing branches and is concurrent and idempotent. Contract tests
cover ordering, full-branch isolation, final distribution, invalid definitions, and concurrent close;
an integration test proves one source reaches JFR, OpenTelemetry, and SLF4J together. The complete
38-module reactor passes 247 tests with 3 environment-dependent Docker skips and no change to
`LifecycleEvent` or lifecycle/binding dependency graphs.

## Phase 11: Observation Pipeline Health Metrics

Status: COMPLETED

Expose the health of observation delivery itself through the optional Micrometer integration. Cover
source buffering and publication loss, fan-out drain and per-branch delivery loss, and JFR,
OpenTelemetry, and SLF4J adapter drain, success, and failure accounting. These are telemetry-pipeline
health signals, not managed-resource business metrics.

Constraints: `steward-observation` and the event adapters must not depend on Micrometer. Adding these
meters must not force an application using one event adapter to bring in the other adapters. Any
shared status contract must remain project-owned, immutable, secret-free, and product-neutral; any
bridge with several optional dependencies must be isolated so it does not change existing runtime
graphs. Branch and owner identities remain validated, static, and bounded.

Acceptance: meter names and tags are fixed and documented; all counters are monotonic; branch
cardinality is bounded by startup configuration; registrations weakly reference owners where
appropriate and remove every meter on idempotent close; scrapes perform only safe status reads;
there is no additional lifecycle or business-path work. Tests cover exact meter sets, direct-buffer
and fan-out modes, per-branch loss, adapter failure, unsafe identities, registration collisions,
and concurrent close.

Completion record: `LifecycleEventDelivery` defines the product-neutral adapter health view. The
Micrometer adapter registers fixed source, branch, and adapter pipeline meters without depending on
the JFR, OpenTelemetry, or SLF4J modules. Weak views retain monotonic terminal counts, reject
identity collisions, and remove all meters on concurrent idempotent close. Exact meter, loss,
failure, cardinality, identity, and close tests pass; names and tags are documented in
[`observability-adapters.md`](observability-adapters.md).

## Phase 12: Runnable End-To-End Observation Example

Status: COMPLETED

Add one maintained example that wires a managed resource to Micrometer, `LifecycleEventFanOut`, JFR,
OpenTelemetry, and SLF4J. It must show which facts are produced, how branch capacities are selected,
where SDK/provider/exporter configuration belongs, and the required owner -> fan-out -> adapter
shutdown order. The example is lifecycle observation only and must not imply that the framework
collects general application logs, request metrics, business traces, or CPU profiles.

Acceptance: the example compiles in the reactor, uses only safe placeholder identities and no
credentials, exercises startup, successful refresh, failed refresh or rollback, and close, and has
an automated smoke test proving that metrics, logs, spans, and JFR markers are all produced from the
same source events. Documentation links to the example from the README and adapter guide.

Completion record: [`examples/observation-e2e`](../examples/observation-e2e) is a non-published
reactor module with a safe fake native client. Its smoke test records one startup, successful
replacement, unhealthy-candidate rollback, and close run, then proves identical source sequences
reach JFR, OpenTelemetry, and SLF4J while Micrometer observes owner and pipeline status. A second
test concurrently closes the owner, fan-out, and adapters and verifies terminal state and thread
exit.

## Phase 13: Observation Stress And Fault Validation

Status: COMPLETED

Exercise observation infrastructure under burst publication, rapid configuration revisions,
source overflow, one saturated or failing branch, concurrent owner/fan-out/adapter close, and
long-running drain cycles. Quantify enabled fan-out throughput and drop behavior separately from the
existing disabled-path benchmark. The objective is bounded behavior and failure isolation, not zero
telemetry loss under overload.

Acceptance: stress scenarios prove queue bounds, retained sequence order, source-versus-branch loss
accounting, healthy-branch progress, final-drain accounting, idempotent concurrent close, and absence
of leaked non-daemon threads. Repeatable JMH or equivalent measurements record fan-out cost by
branch count and batch size without weakening the existing no-op business-path baseline. Tests use
deterministic barriers and bounded deadlines rather than timing-dependent sleeps.

Completion record: deterministic stress tests cover 20,000 burst attempts, source overflow,
saturated and closed branch isolation, 4,096-event final draining, 1,000 rapid configuration
updates, concurrent close, retained sequence order, accounting, and worker exit. The parameterized
enabled fan-out JMH matrix is recorded in
[`observation-performance.md`](observation-performance.md); the existing disabled business-path
baseline remains unchanged.

## Phase 14: First Release And Compatibility Baseline

Status: LOCAL GATES COMPLETED; EXTERNAL PUBLICATION PENDING

Prepare the first consumable release and make it the compatibility baseline for subsequent changes.
Finalize the public API inventory, versioning policy, BOM coordinates, source and Javadoc artifacts,
release notes, and upgrade expectations. Distinguish experimental APIs from contracts intended to
remain binary compatible.

Acceptance: a clean checkout can build and stage every intended artifact; the BOM resolves all
project modules without leaking optional observation dependencies; published POMs preserve binding
dependency graphs; source and Javadoc artifacts are present; release notes document observation
ownership and shutdown rules. After the baseline artifact is available, `japicmp` resolves it and
fails the build on unapproved binary incompatibility instead of warning that the old artifact is
missing.

Completion record: the reactor is versioned `0.1.0`; public API, versioning, release notes, BOM,
adapter ownership, and upgrade rules are documented. `scripts/stage-release.sh` ran the full
release profile and verified 37 POMs plus 35 main, source, and Javadoc JAR sets. A structured verifier
matched every staged JAR to the 35 BOM entries and confirmed the example and benchmarks were not
published. External `0.1.0` publication cannot be performed from this checkout without repository
coordinates, signing material, and credentials. Once published, the documented
`baseline-compatibility` profile makes a missing old version fatal.

## Phase 15: Docker-Backed CI Verification

Status: COMPLETED

Run the environment-dependent integration tests in a CI job with Docker available. Keep ordinary
local builds usable without Docker, but make the release gate exercise the real container-backed
paths rather than accepting skips.

Acceptance: the CI job starts pinned service versions, applies explicit readiness and test
timeouts, captures container logs on failure, and always cleans up. All three current Docker tests
execute with zero skips in the release gate; the full 39-project reactor, dependency-enforcement
rules, and observation tests run in the same release workflow or in required prerequisite jobs.

Completion record: `.github/workflows/release.yml` provisions JDK 21 on an Ubuntu Docker host and
runs the full reactor. All Redis tests pin `redis:7.4.2-alpine`, use explicit readiness, 60-second
startup and two-minute test timeouts, and forward container logs. The workflow uploads Surefire
reports, captures remaining container logs on failure, and always removes containers.
`scripts/assert-docker-tests.sh` parses all three XML reports and requires exactly three executed
tests with zero skips. The parent POM fixes the Testcontainers Docker API at `1.40` so Docker 29
does not reject the older client default and cause a false skip. A local OrbStack execution ran all
three pinned Redis containers successfully with zero failures, errors, or skips, and the assertion
script passed. The hosted workflow remains the repeatable release gate.
