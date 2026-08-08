package yunqi.zhibei.steward.binding.nacos.client.v3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

public final class NacosClient3Configuration {

    private final String serverAddress;

    private final Optional<String> username;

    private final Optional<String> password;

    private final Optional<String> namespace;

    private final Map<String, String> clientProperties;

    NacosClient3Configuration(String serverAddress, Optional<String> username, Optional<String> password, Optional<String> namespace, Map<String, String> clientProperties) {
        serverAddress = requireText(serverAddress, "serverAddress");
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        namespace = Objects.requireNonNull(namespace, "namespace");
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("username and password must either both be present or both be absent");
        }
        Objects.requireNonNull(clientProperties, "clientProperties");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        clientProperties.forEach((key, value) -> copy.put(requireText(key, "client property key"), Objects.requireNonNull(value, "client property value")));
        clientProperties = Collections.unmodifiableMap(copy);
        this.serverAddress = serverAddress;
        this.username = username;
        this.password = password;
        this.namespace = namespace;
        this.clientProperties = clientProperties;
    }

    public String serverAddress() {
        return serverAddress;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> password() {
        return password;
    }

    public Optional<String> namespace() {
        return namespace;
    }

    public Map<String, String> clientProperties() {
        return clientProperties;
    }

    static NacosClient3Configuration defaults() {
        return new NacosClient3Configuration("127.0.0.1:8848", Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private String serverAddress;

        private Optional<String> username;

        private Optional<String> password;

        private Optional<String> namespace;

        private final Map<String, String> clientProperties = new LinkedHashMap<>();

        private Builder(NacosClient3Configuration source) {
            serverAddress = source.serverAddress();
            username = source.username();
            password = source.password();
            namespace = source.namespace();
            clientProperties.putAll(source.clientProperties());
        }

        public Builder serverAddress(String value) {
            serverAddress = value;
            return this;
        }

        public Builder credentials(String user, String secret) {
            username = Optional.of(user);
            password = Optional.of(secret);
            return this;
        }

        public Builder clearCredentials() {
            username = Optional.empty();
            password = Optional.empty();
            return this;
        }

        public Builder namespace(String value) {
            namespace = Optional.of(value);
            return this;
        }

        public Builder clearNamespace() {
            namespace = Optional.empty();
            return this;
        }

        public Builder clientProperties(Map<String, String> values) {
            clientProperties.clear();
            clientProperties.putAll(values);
            return this;
        }

        public Builder clientProperty(String key, String value) {
            clientProperties.put(key, value);
            return this;
        }

        public NacosClient3Configuration build() {
            return new NacosClient3Configuration(serverAddress, username, password, namespace, clientProperties);
        }
    }

    public Properties properties() {
        Properties result = new Properties();
        clientProperties.forEach(result::setProperty);
        result.setProperty("serverAddr", serverAddress);
        username.ifPresent(value -> result.setProperty("username", value));
        password.ifPresent(value -> result.setProperty("password", value));
        namespace.ifPresent(value -> result.setProperty("namespace", value));
        return result;
    }

    @Override
    public String toString() {
        return "NacosClient3Configuration[serverAddress=" + serverAddress + ", username=" + username + ", password=" + (password.isPresent() ? "[REDACTED]" : "empty") + ", namespace=" + namespace + ", clientPropertyKeys=" + clientProperties.keySet() + "]";
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NacosClient3Configuration that))
            return false;
        return java.util.Objects.equals(serverAddress, that.serverAddress) && java.util.Objects.equals(username, that.username) && java.util.Objects.equals(password, that.password) && java.util.Objects.equals(namespace, that.namespace) && java.util.Objects.equals(clientProperties, that.clientProperties);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(serverAddress, username, password, namespace, clientProperties);
    }
}
