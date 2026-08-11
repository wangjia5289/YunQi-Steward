# Vendor Health And Timeout Audit

Status: COMPLETED

This audit describes the vendor boundary behind each binding. `BoundResource.health()` and
`ManagedResource.health()` are synchronous demand-time calls; they do not impose a timeout. Native
SDK calls must therefore use the finite timeout shown below. `close()` is also synchronous. A
managed retirement can stop waiting after its configured owner timeout, but it cannot safely
interrupt a vendor close operation.

## Refresh-Safe Bindings

| Binding | Create/start boundary | Health probe | Operation timeout | Close boundary | Action |
| --- | --- | --- | --- | --- | --- |
| ClickHouse JDBC 0 | Hikari pool is lazy; JDBC connection timeout is configured | Remote `Connection.isValid(3)` | JDBC socket timeout; Hikari acquire timeout | Hikari close, no vendor close timeout | Document SDK limitation |
| Consul API 1 | Client construction is local | Remote `getStatusLeader()` | Apache transport defaults: 10 s connect, 10 min read | SDK has no close operation | Document finite defaults and no-op close |
| ElasticJob Lite 3 | Registry init uses ZooKeeper session/connection timeouts | Remote registry read | Curator/ZooKeeper session and connection settings | Registry close has no timeout overload | Document SDK limitation |
| Elasticsearch Java 9 | Rest client construction is local/lazy | Remote ping | Finite connect, socket, connection-request, and response timeouts | Transport close has no timeout overload | Document SDK limitation |
| jetcd 0 | gRPC client construction is local/lazy | Remote KV request with fixed 5 s future wait | Configured gRPC connect timeout; per-operation deadline is not exposed by binding | Client close has no timeout overload | Document fixed health bound |
| Kafka Client 3 | Producer construction starts SDK background work | Local metrics/state check | Finite request and delivery timeouts | Binding uses SDK `close(Duration)` with a fixed 30 s upper bound; no typed close value can reach `close(T)` without wrapping the native producer | Fixed finite binding policy |
| Milvus SDK 2 | Client construction is local | Remote `checkHealth()` | Connect timeout is configured; health/RPC deadline is not exposed by binding | Client close has no timeout overload | Document SDK limitation |
| MinIO Java 8 | HTTP client construction is local | Remote `listBuckets()` | Finite connect, read, and write HTTP timeouts | MinIO/OkHttp close has no timeout overload | Document SDK limitation |
| MongoDB Sync 5 | Driver construction is local/lazy | Remote admin ping | Driver URI controls server selection/connect/socket timeouts; no typed defaults are added | Client close has no timeout overload | Document URI-owned timeout boundary |
| MySQL Connector/J 9 | Hikari pool is lazy; JDBC connection timeout is configured | Remote `Connection.isValid(3)` | JDBC socket timeout; Hikari acquire timeout | Hikari close, no vendor close timeout | Document SDK limitation |
| MariaDB 3 | Hikari pool is lazy; JDBC connection timeout is configured | Remote `Connection.isValid(3)` | JDBC socket timeout; Hikari acquire timeout | Hikari close, no vendor close timeout | Document SDK limitation |
| Nacos Client 3 | Native factory may start background connection work | Remote config/naming server status | SDK properties can carry vendor client timeout settings | `shutDown()` has no timeout overload | Document SDK limitation; focused lifecycle seam is test-only |
| Neo4j Driver 6 | Driver construction is local/lazy | Remote `verifyConnectivity()` | Driver connection and acquisition timeouts | SDK has async close but no vendor timeout parameter | Document async-close limitation |
| PostgreSQL JDBC 42 | Hikari pool is lazy; JDBC connection timeout is configured | Remote `Connection.isValid(3)` | JDBC socket timeout; Hikari acquire timeout | Hikari close, no vendor close timeout | Document SDK limitation |
| Pulsar Client 3 | Client construction starts SDK I/O threads | Local `isClosed()` state | Finite Pulsar operation timeout | SDK exposes async close but no timeout parameter | Document async-close limitation |
| RabbitMQ Client 5 | `newConnection()` opens AMQP immediately | Connection-state `isOpen()` | Connection timeout; operation-specific limits remain vendor/client controlled | Binding uses SDK `close(..., timeout)` with a fixed 30 s upper bound; no typed close value can reach `close(T)` without wrapping the native connection | Fixed finite binding policy |
| Jedis 5 standalone | Pooled client construction is local/lazy | Remote `PING` | Finite connect, command, and pool-acquire timeouts | `close()` has no timeout overload | Document SDK limitation |
| Jedis 7 standalone | Pooled client construction is local/lazy | Remote `PING` | Finite connect, command, and pool-acquire timeouts | `close()` has no timeout overload | Document SDK limitation |
| Jedis 7 cluster | Cluster discovery uses configured connect/socket timeouts | Remote `PING` | Finite connect, command, and pool-acquire timeouts | `close()` has no timeout overload | Document SDK limitation |
| Lettuce 6 standalone | Client construction is local/lazy | Remote `PING` using command timeout | Finite connect and command timeouts | Binding uses SDK shutdown with a fixed 30 s timeout; no typed close value can reach `close(T)` without wrapping the native client | Fixed finite binding policy |
| Lettuce 6 cluster | Client construction is local/lazy | Remote `PING` using command timeout | Finite connect and command timeouts | Binding uses SDK shutdown with a fixed 30 s timeout; no typed close value can reach `close(T)` without wrapping the native client | Fixed finite binding policy |
| Redisson 4 standalone | Client construction is local/lazy | Remote `pingAll()` using command timeout | Finite connect, command, subscription, and pool settings | Binding uses SDK shutdown with a fixed 30 s timeout; no typed close value can reach `close(T)` without wrapping the native client | Fixed finite binding policy |
| Redisson 4 cluster | Client construction is local/lazy | Remote `pingAll()` using command timeout | Finite connect, command, and pool settings | Binding uses SDK shutdown with a fixed 30 s timeout; no typed close value can reach `close(T)` without wrapping the native client | Fixed finite binding policy |
| Curator 5 | `start()` initiates ZooKeeper connection | Remote `checkExists('/')` | Finite ZooKeeper connection/session settings; operation waits inherit SDK behavior | Curator close has no timeout overload | Document SDK limitation |

## Startup-Only Bindings

| Binding | Create/start boundary | Health probe | Operation timeout | Close boundary | Action |
| --- | --- | --- | --- | --- | --- |
| RocketMQ Client 5 | `start()` performs producer registration | Startup-only successful start | Send timeout configured | `shutdown()` has no timeout overload | Document startup-only limitation |
| PowerJob Worker 5 | `init()` performs worker registration | Startup-only local lifecycle signal | Vendor worker configuration | `destroy()` has no timeout overload | Document startup-only limitation |
| XXL-JOB Core 2 | `start()` performs executor registration | Startup-only local lifecycle signal | Vendor executor configuration | `destroy()` has no timeout overload | Document startup-only limitation |

## Maintenance Rules

- Keep the fixed finite close policy covered by binding tests and review the 30 s bound against
  deployment shutdown budgets.
- Add mapping tests for any future binding-level timeout policy before changing a vendor close call.
- Keep the documented limitations for SDKs with no timeout overload; do not add a framework-level
  force-close, wrapper resource, identity map, or scheduler.
