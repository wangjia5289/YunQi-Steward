# Secret Composition Examples

Steward configuration sources publish complete typed configurations. They do not know how a
secret is stored, and they never retain raw provider content or secret values in status, lifecycle
events, or diagnostics. Resolve secrets in the application-owned loader before returning the typed
configuration.

## A Small Resolver Boundary

Keep the provider SDK outside the configuration-management modules:

```java
@FunctionalInterface
interface SecretResolver {
    String resolve(String reference) throws Exception;
}

static Jedis7Configuration loadRedis(Properties properties, SecretResolver secrets) {
    String host = require(properties, "redis.host");
    int port = Integer.parseInt(require(properties, "redis.port"));
    String password = secrets.resolve(require(properties, "redis.password.ref"));
    if (password == null || password.isBlank()) {
        throw new IllegalArgumentException("secret resolver returned no value");
    }
    return Jedis7Configuration.builder()
            .host(host)
            .port(port)
            .password(password)
            .build();
}
```

The returned `Jedis7Configuration` must be immutable and fully validated. A missing reference,
failed lookup, or invalid value must throw before the source publishes a new revision; the active
resource then continues serving the previous complete generation.

## Vault

Construct a Vault-backed resolver in the application and inject it into the loader. The Vault
client, token, namespace, and mount path stay in application configuration and are not passed to
Steward status or lifecycle telemetry:

```java
SecretResolver vault = reference -> vaultClient.readSecret(reference).getRequired("password");
try (var source = PropertiesFileConfigurationSource.open(
        path, properties -> loadRedis(properties, vault))) {
    // Bind source to ManagedResource<JedisPooled, Jedis7Configuration>.
}
```

Use the Vault SDK's own timeout and renewal policy. Do not put a Vault client in a
`ConfigurationSnapshot`.

## KMS

For an encrypted value in a provider payload, make decryption a resolver operation owned by the
application. The payload can contain a ciphertext reference or an encrypted byte string; only the
complete plaintext value enters the typed configuration:

```java
SecretResolver kms = reference -> kmsClient.decrypt(
        Base64.getDecoder().decode(require(reference, "ciphertext")));
```

The KMS client and key identifiers remain outside Steward's source status and event contract. Use
the KMS SDK's bounded request timeout and fail the whole load when decryption fails.

## Mounted Secret Files

Kubernetes Secret projections and local mounted files can be resolved without a provider SDK:

```java
SecretResolver mounted = reference -> Files.readString(
        Path.of(reference), StandardCharsets.UTF_8).strip();

try (var source = PropertiesFileConfigurationSource.open(
        Path.of("/etc/steward/redis.properties"),
        properties -> loadRedis(properties, mounted))) {
    // The properties source watches the directory, including atomic ..data symlink swaps.
}
```

The file source treats deletion, partial writes, and invalid content as an unavailable source
state. It retains the last complete revision and recovers when a later complete file is observed;
it never publishes a partial typed configuration.

## Operational Rules

- Keep references, paths, and provider client objects out of `ConfigurationSnapshot` values.
- Return project-owned immutable configuration objects whose `toString()` redacts secrets.
- Use `ConfigurationSourceStatus` and Micrometer source meters for availability and failure counts;
  these views contain no raw error message or provider content.
- Close the resource owner first, then source subscriptions, then adapters and caller-owned secret
  clients according to their ownership contracts.
