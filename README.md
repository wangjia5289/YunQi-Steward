# Yunqi Steward

Yunqi Steward manages the lifecycle of native middleware clients without replacing their business
APIs. Applications keep using types such as `JedisPooled`, `KafkaProducer`, `HikariDataSource`, or
`MongoClient`; Steward coordinates creation, health checks, configuration-driven replacement,
draining, closure, and lifecycle telemetry.

## Architecture

The repository has three responsibility planes:

```text
yunqi-steward-interaction-plane/  native library clients and framework clients
yunqi-steward-control-plane/      resource management and configuration management
yunqi-steward-telemetry-plane/    lifecycle events, logs, metrics, traces, and JFR markers
```

Interaction modules remain independently selectable. Their layout is:

```text
<middleware>/library-client/<library>/v<library-major>
<middleware>/framework-client/<framework>/v<framework-major>/<library>/v<library-major>
```

For example, the Spring Framework 6 integration backed by Jedis 7 is published from
`redis/framework-client/spring-framework/v6/jedis/v7`. Both version lines are explicit because the
module integrates one concrete framework line with one concrete access-library line. It provides a
static factory which exposes `JedisPooled` and a dynamic factory which exposes
`ManagedResource<JedisPooled, Jedis7Configuration>`; dynamic applications use scoped operations
instead of retaining a replaceable native client reference.

The control plane manages whole client instances. Connections, pools, sessions, and worker threads
inside a client remain owned by that client library.

## Start A Native Client

```java
var configuration = Jedis7Configuration.builder()
        .host("redis.internal")
        .build();

try (BoundResource<JedisPooled> redis = Jedis7Binding.start(configuration)) {
    redis.resource().set("answer", "42");
}
```

Use `ManagedResource` when the selected `ResourceBinding` supports overlapping generations and the
application needs in-process configuration replacement.

See [architecture](docs/architecture.md), [usage](docs/usage.md), and
[public API](docs/public-api.md) for the complete contracts.

Secret lookup remains application-owned. See [secret composition examples](docs/secret-composition.md)
for Vault, KMS, and mounted Secret patterns.

For a plain Java deployment which owns configuration in a local `.properties` file, add
`steward-control-configuration-management-file-properties` and use
`PropertiesFileConfigurationSource.open(path, loader)`. The loader maps validated properties to a
project-owned immutable configuration such as `Jedis7Configuration`; it does not parse YAML,
perform Spring Boot binding, or resolve secrets implicitly.

## Build

The build requires JDK 21 and Maven 3.9 or newer:

```bash
mvn clean verify
```
