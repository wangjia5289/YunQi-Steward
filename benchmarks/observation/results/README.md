# Baseline Results

Files in this directory are raw JMH JSON captured on named hardware and JVM versions. They are
reference measurements, not portable absolute pass/fail thresholds. Compare paired omitted/no-op
benchmarks from the same run and environment.

`apple-m4-temurin-21.0.10.json` is the Phase 7 canonical baseline. Its environment, commands,
methodology, summary, and regression policy are documented in
[`docs/observation-performance.md`](../../docs/observation-performance.md).

`apple-m4-temurin-21.0.10-fanout.json` is the Phase 13 enabled fan-out matrix for one, two, and
four branches with distributor batches of 1, 16, and 64. It is a short characterization run, not a
portable release threshold.
