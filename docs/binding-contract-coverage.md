# Binding Contract Coverage

This matrix records how each native SDK binding is checked against the refresh-safe lifecycle
contract. The shared `BindingContract` creates real SDK resources, checks owner-level idempotent
close, drives an unhealthy replacement through `ManagedResource`, verifies that the active resource
survives rollback, and checks configuration diagnostic redaction. Its health wrapper deliberately
controls the health result, so the contract does not require a reachable vendor service.

## Refresh-Safe Bindings

| Binding | Contract test | Resource setup | External service | Notes |
| --- | --- | --- | --- | --- |
| ClickHouse JDBC 0 | Shared `BindingContract` | Offline Hikari/JDBC construction | None | `ClickHouseJdbc0BindingTest` |
| Consul API 1 | Shared `BindingContract` | Offline client construction | None | `ConsulApi1BindingTest` |
| ElasticJob Lite 3 | Shared `BindingContract` | Embedded ZooKeeper `TestingServer` | No external service | The native registry client needs a live ZooKeeper protocol endpoint; `ElasticJobLite3BindingTest` owns the fixture. |
| Elasticsearch Java 9 | Shared `BindingContract` | Offline transport/client construction | None | `ElasticsearchJava9BindingTest` |
| jetcd 0 | Shared `BindingContract` | Offline client construction | None | `Jetcd0BindingTest` |
| Kafka Client 3 | Shared `BindingContract` | Offline producer construction | None | `KafkaClient3BindingTest` |
| Milvus SDK 2 | Shared `BindingContract` | In-process gRPC protocol fixture | No external service | `MilvusSdk2BindingTest` starts the minimum Connect/ListDatabases service required by the SDK. |
| MinIO Java 8 | Shared `BindingContract` | Offline client construction | None | `MinioJava8BindingTest` |
| MongoDB Sync 5 | Shared `BindingContract` | Offline client construction | None | `MongoDbSync5BindingTest` |
| MySQL Connector/J 9 | Shared `BindingContract` | Offline Hikari/JDBC construction | None | `MySqlConnectorJ9BindingTest` |
| MariaDB 3 | Shared `BindingContract` | Offline Hikari/JDBC construction | None | `MariaDb3BindingTest` |
| Neo4j Driver 6 | Shared `BindingContract` | Offline driver construction | None | `Neo4jDriver6BindingTest` |
| PostgreSQL JDBC 42 | Shared `BindingContract` | Offline Hikari/JDBC construction | None | `PostgreSqlJdbc42BindingTest` |
| Pulsar Client 3 | Shared `BindingContract` | Offline client construction | None | `PulsarClient3BindingTest` |
| Jedis 5 standalone | Shared `BindingContract` | Offline pooled client construction | None | `Jedis5BindingTest` |
| Jedis 7 standalone | Shared `BindingContract` | Offline pooled client construction | None | `Jedis7BindingTest` |
| Jedis 7 cluster | Shared `BindingContract` | Local RESP Cluster Slots fixture | No external service | `Jedis7ClusterBindingTest` uses `FakeRedisClusterServer`; cluster discovery is a real protocol exchange. |
| Lettuce 6 standalone | Shared `BindingContract` | Offline client construction | None | `Lettuce6BindingTest` |
| Lettuce 6 cluster | Shared `BindingContract` | Offline cluster client construction | None | `Lettuce6ClusterBindingTest` |
| Redisson 4 standalone | Shared `BindingContract` | Offline client construction | None | `Redisson4BindingTest` |
| Redisson 4 cluster | Shared `BindingContract` | Offline cluster client construction | None | `Redisson4ClusterBindingTest` |
| Curator 5 | Shared `BindingContract` | Offline framework construction | None | `Curator5BindingTest` |
| Nacos Client 3 | Focused lifecycle tests | Package-private `NacosServiceFactory` with ConfigService/NamingService proxies | None | The vendor factory starts background connection work during construction, so an offline shared-contract test is misleading. `NacosClient3BindingTest` covers partial creation cleanup, owner idempotence, health rollback, active-generation retention, and redaction. |
| RabbitMQ Client 5 | Focused lifecycle tests | Existing package-private `ConnectionCreator` with tracked `Connection` proxy | None | The default factory immediately opens AMQP; `RabbitMqClient5BindingTest` covers owner idempotence, unhealthy replacement cleanup, active-connection retention, and redaction. |

The two focused bindings remain refresh-safe. They use deterministic native-service seams only in
tests; no public factory or common client API is added.

## Startup-Only Bindings

These bindings are intentionally excluded from the refresh contract because their SDKs own
process-global identity, producer groups, worker registration, or other state that cannot safely
overlap during a replacement. They require restart or a rolling deployment when configuration
changes.

| Binding | Lifecycle contract | Test tracking |
| --- | --- | --- |
| RocketMQ Client 5 | `StartupBinding` | `RocketMq5BindingTest` |
| PowerJob Worker 5 | `StartupBinding` | `PowerJobWorker5BindingTest` |
| XXL-JOB Core 2 | `StartupBinding` | `XxlJobCore2BindingTest` |

Seata TM 2 currently exposes its own startup-oriented wrapper rather than a `ResourceBinding` and
is tracked outside this matrix.

## Maintenance Rules

- A new `ResourceBinding` must add either a shared-contract invocation or an equivalent focused
  test before it is considered refresh-safe.
- A test must not require a real, permanently running external service. Use a protocol fixture or a
  package-private injection seam when construction itself performs I/O.
- Changes to the shared contract require rerunning every row marked `Shared`.
- Contract coverage does not replace the vendor timeout and health audit in Phase 4.
