# Migration From The Provider Runtime

The new design is intentionally not source-compatible with the previous middleware abstraction.
Provider discovery and cross-SDK switching were removed, not renamed.

## Dependencies

Remove:

- `middleware-*-starter` dependencies from the previous provider runtime;
- `middleware-*-api`, `middleware-*-runtime`, and `middleware-provider-*` artifacts;
- Spring integration modules;
- plugin ZIPs and plugin-directory configuration.

Add the exact native binding selected for this deployment:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-binding-redis-jedis-v7</artifactId>
    <version>0.1.0</version>
</dependency>
```

The current `steward-bom` may still be imported to align versions across selected
binding artifacts; it no longer selects or discovers providers at runtime.

The binding supplies its tested SDK dependency and lightweight `steward-lifecycle`. Add
`steward-refresh` separately only for same-binding in-process refresh. Seata instead uses the
`steward-binding-seata-tm-v2` artifact.

## API Mapping

| Previous API | Replacement |
| --- | --- |
| `RedisClient`, `KafkaClient`, `MySqlClient`, and other unified clients | The selected vendor's native SDK type |
| `RedisRuntime`, `KafkaRuntime`, and other middleware facades | `BoundResource<T>` by default |
| Dynamic facade client proxy | Direct `bound.resource()` |
| `ProviderConfiguration` and provider schemas | Binding-specific immutable configuration class with Builder |
| `ProviderConfigurationSource` | No equivalent for startup-fixed use |
| `MutableProviderConfigurationSource` | `MutableConfigurationSource<C>` only for same-binding refresh |
| `MiddlewareProvider`, factory SPI, coordinates, and capabilities | Selected binding artifact and its `ResourceBinding` implementation |
| `ManagedMiddlewareRuntime` / `SwitchingProviderRuntime` | Optional `ManagedResource<T,C>` for the same native type |
| Plugin directory and `ServiceLoader` discovery | Ordinary Maven or Gradle dependency |
| Seata provider runtime | `SeataTm2.initialize(...)` |

## Call Sites

Replace unified calls with the selected SDK's real API:

```java
// Before
redisRuntime.client().set("key", "value", ttl);

// After
bound.resource().setex("key", ttl.toSeconds(), "value");
```

Vendor exceptions, serializers, callbacks, transaction objects, and result types now remain
visible. This is intentional: the project no longer constrains applications to a lowest-common-
denominator API.

## Configuration Changes

Choose one of two explicit behaviors:

1. Restart on configuration change. Use `BoundResource`; this is the default and simplest path.
2. Refresh the same SDK type in process. Add `steward-refresh`, then use `ManagedResource` only
   when the binding implements `ResourceBinding` and overlapping native instances are safe.

Changing a provider family or incompatible version is always a dependency change followed by a
deployment. A Redis configuration update cannot turn Jedis into Lettuce or Redisson.

RocketMQ, PowerJob, and XXL-JOB are startup-only. Seata initializes once per JVM. Do not attempt to
recreate the former dynamic switching behavior around these SDKs.

Project-owned typed configurations are final immutable classes. Their full constructors are not
public; use `builder(...)` for a default snapshot and `toBuilder()` for incremental changes.
Database configurations require the database name, RocketMQ requires the producer group, and Seata
requires both process identities. PowerJob and XXL-JOB continue to accept their native SDK setup
objects; only their lifecycle ownership is adapted.

## Package Migration

Old provider-runtime packages such as:

```text
yunqi.zhibei.steward.redis.runtime
yunqi.zhibei.steward.redis.jedis7.config
yunqi.zhibei.steward.plugin
```

become responsibility-based Steward packages:

```text
yunqi.zhibei.steward.lifecycle
yunqi.zhibei.steward.refresh
yunqi.zhibei.steward.binding.redis.jedis.v7
yunqi.zhibei.steward.binding.kafka.client.v3
yunqi.zhibei.steward.binding.xxljob.core.v2
```

Every native binding package includes its implementation and SDK major line. This avoids split
packages and makes the source of a native type explicit. Configuration and observability adapters
use the parallel `yunqi.zhibei.steward.adapter` namespace.

Binding modules no longer sit below broad functional categories. Their coordinates map directly
across repository, artifact, and package names:

```text
Repository: bindings/<middleware>/<driver>/v<major>
Artifact:   steward-binding-<middleware>-<driver>-v<major>
Package:    yunqi.zhibei.steward.binding.<middleware>.<driver>.v<major>
```

For example, Redis supports `bindings/redis/jedis/v5`, `bindings/redis/jedis/v7`,
`bindings/redis/lettuce/v6`, and `bindings/redis/redisson/v4`. The version prefix is part of the
public package and artifact naming convention; use `v7`, not a bare `7` or a driver name ending in
`7`.

## Spring Migration

Replace project-specific enable annotations and runtime beans with ordinary application beans.
Construct the selected binding during startup, expose either its `BoundResource` or native client,
and close the owner during context shutdown. Existing Spring Boot SDK support may be used directly
when it already covers the chosen native library.
