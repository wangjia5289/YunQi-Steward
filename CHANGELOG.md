# Changelog

## 0.1.0 - 2026-08-08

- Established the Yunqi Steward (云起-管家) identity with Maven group `yunqi.zhibei`,
  Java root package `yunqi.zhibei.steward`, and interaction, control, and telemetry responsibility
  planes alongside support, examples, benchmarks, and the BOM.
- Organized the codebase into interaction, control, and telemetry planes. Library clients use
  `yunqi-steward-interaction-plane/<middleware>/library-client/<library>/v<library-major>`;
  framework integrations use `<middleware>/framework-client/<framework>/v<framework-major>/<library>/v<library-major>`.
- Added the first framework client: plain Spring Framework 6 lifecycle integration backed by the
  concrete Jedis 7 library client. Static mode exposes native `JedisPooled`; dynamic mode exposes
  `ManagedResource<JedisPooled, Jedis7Configuration>` for lease-safe replacement.
- Added startup-fixed `BoundResource`, overlap-safe `ManagedResource`, and restart-required
  monitoring for startup-only SDKs.
- Added typed monotonic dynamic configuration and the Nacos 3 configuration adapter.
- Added a framework-neutral Java properties-file configuration source with automatic file watching,
  complete typed loading, duplicate suppression, failure recovery, and managed-resource refresh
  coverage.
- Added the shared configuration-source contract testkit, secret-free source status with failure and
  recovery counters, Kubernetes projected-volume symlink coverage, and independent Micrometer
  source-health meters.
- Added Vault, KMS, and mounted-Secret composition examples without adding a provider dependency to
  configuration core, plus a CI-gated real Nacos 3 deployment test.
- Extended the dynamic Spring Jedis 7 factory with optional caller-owned lifecycle events and a
  bounded close-wait duration while preserving the original no-observation constructor.
- Added the vendor-neutral `LifecycleEvent`, bounded non-blocking buffer, fan-out, and delivery
  health contract without third-party telemetry dependencies in lifecycle modules.
- Added optional Micrometer, JFR, OpenTelemetry, and SLF4J adapters plus a runnable end-to-end
  example. Event adapters are exclusive branch consumers and close after owner and fan-out.
- Added binding contract coverage, vendor health/timeout documentation, deterministic stress and
  fault tests, disabled/enabled observation benchmarks, local release staging, and Docker CI gates.

This is the first compatibility baseline. There is no earlier release to migrate from.
