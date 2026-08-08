# Changelog

## 0.1.0 - 2026-08-08

- Established the Yunqi Steward (云起-管家) identity with Maven group `yunqi.zhibei`,
  Java root package `yunqi.zhibei.steward`, and responsibility-based repository layers for core,
  adapters, bindings, support, examples, benchmarks, and the BOM.
- Organized every native binding as `bindings/<middleware>/<driver>/v<major>`, with middleware
  names directly below `bindings` and no broad cache, database, or messaging directory layer.
- Added startup-fixed `BoundResource`, overlap-safe `ManagedResource`, and restart-required
  monitoring for startup-only SDKs.
- Added typed monotonic dynamic configuration and the Nacos 3 configuration adapter.
- Added the vendor-neutral `LifecycleEvent`, bounded non-blocking buffer, fan-out, and delivery
  health contract without third-party telemetry dependencies in lifecycle modules.
- Added optional Micrometer, JFR, OpenTelemetry, and SLF4J adapters plus a runnable end-to-end
  example. Event adapters are exclusive branch consumers and close after owner and fan-out.
- Added binding contract coverage, vendor health/timeout documentation, deterministic stress and
  fault tests, disabled/enabled observation benchmarks, local release staging, and Docker CI gates.

This is the first compatibility baseline. There is no earlier release to migrate from.
