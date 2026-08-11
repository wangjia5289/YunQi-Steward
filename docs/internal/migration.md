# Migration From The Provider Runtime

The new design is intentionally not source-compatible with the previous middleware abstraction.
Provider discovery and cross-SDK switching were removed, not renamed.

## Dependencies

Remove:

- `middleware-*-starter` dependencies from the previous provider runtime;
- `middleware-*-api`, `middleware-*-runtime`, and `middleware-provider-*` artifacts;
- old Spring integration modules;
- plugin ZIPs and plugin-directory configuration.

Add the exact native binding selected for this deployment:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-interaction-redis-library-client-jedis-v7</artifactId>
    <version>0.1.0</version>
</dependency>
```

The current `steward-bom` may still be imported to align versions across selected
binding artifacts; it no longer selects or discovers providers at runtime.

The binding supplies its tested SDK dependency and lightweight `steward-control-resource-management-core`. Add
`steward-control-resource-management-refresh` separately only for same-binding in-process refresh. Seata instead uses the
`steward-interaction-seata-library-client-tm-v2` artifact.

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
2. Refresh the same SDK type in process. Add `steward-control-resource-management-refresh`, then use `ManagedResource` only
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
yunqi.zhibei.steward.control.resource
yunqi.zhibei.steward.control.resource.refresh
yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7
yunqi.zhibei.steward.interaction.kafka.library.client.kafka.clients.v3
yunqi.zhibei.steward.interaction.xxljob.library.client.core.v2
```

Every library-client package includes its middleware, client form, library name, and SDK major
line. This avoids split packages and makes the source of a native type explicit. Control and
telemetry integrations use their respective plane namespaces.

Binding modules no longer sit below broad functional categories. Their coordinates map directly
across repository, artifact, and package names:

```text
Repository: yunqi-steward-interaction-plane/<middleware>/library-client/<library>/v<library-major>
Artifact:   steward-interaction-<middleware>-library-client-<library>-v<library-major>
Package:    yunqi.zhibei.steward.interaction.<middleware>.library.client.<library>.v<library-major>
```

For example, Redis supports `redis/library-client/jedis/v5`, `redis/library-client/jedis/v7`,
`redis/library-client/lettuce/v6`, and `redis/library-client/redisson/v4` below the interaction
plane. The version prefix is part of the public package and artifact naming convention; use `v7`,
not a bare `7` or an implementation name ending in `7`.

Framework-client coordinates additionally name the framework and the concrete access library:

```text
Repository: yunqi-steward-interaction-plane/<middleware>/framework-client/<framework>/v<framework-major>/<library>/v<library-major>
Artifact:   steward-interaction-<middleware>-framework-client-<framework>-v<framework-major>-<library>-v<library-major>
Package:    yunqi.zhibei.steward.interaction.<middleware>.framework.client.<framework>.v<framework-major>.<library>.v<library-major>
```

## Spring Migration

For a plain Spring Framework 6 application using Jedis 7, replace the old integration with the
concrete framework-client artifact:

```xml
<dependency>
    <groupId>yunqi.zhibei</groupId>
    <artifactId>steward-interaction-redis-framework-client-spring-framework-v6-jedis-v7</artifactId>
    <version>0.1.0</version>
</dependency>
```

For startup-fixed use, register `Jedis7SpringFactoryBean` as an ordinary bean. Spring initialization
creates and checks the Jedis client, callers inject the native `JedisPooled` object, and context
destruction closes the owned resource.

For dynamic same-type replacement, register `Jedis7ManagedResourceFactoryBean` with a
`ConfigurationSource<Jedis7Configuration>`. Callers inject
`ManagedResource<JedisPooled, Jedis7Configuration>` and perform work through scoped operations.
Do not inject `JedisPooled` in this mode because an injected native reference remains tied to its
original generation. The module is plain Spring Framework integration, not Spring Boot
auto-configuration, and it does not introduce a project-owned Redis command API.
