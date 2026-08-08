package yunqi.zhibei.steward.binding.consul.api.v1;

import com.ecwid.consul.v1.ConsulClient;
import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/** Binds managed lifecycle to a native Consul API 1 client. */
public final class ConsulApi1Binding
        implements ResourceBinding<ConsulApi1Configuration, ConsulClient> {

    public static BoundResource<ConsulClient> start(ConsulApi1Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new ConsulApi1Binding());
    }

    static final String SDK_MAJOR = "1";
    private static final String ARTIFACT_PREFIX = "consul-api-";

    public ConsulApi1Binding() {
        verifyDependencyVersion();
    }

    @Override
    public ConsulClient create(ConsulApi1Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new ConsulClient(configuration.host(), configuration.port());
    }

    @Override
    public Health check(ConsulClient client) {
        Objects.requireNonNull(client, "client").getStatusLeader();
        return Health.healthy(ProbeScope.REMOTE);
    }

    @Override
    public void close(ConsulClient client) {
        Objects.requireNonNull(client, "client");
        // consul-api 1.x does not expose a close operation.
    }

    static void verifyDependencyVersion() {
        String actual = loadedDependencyVersion();
        if (!SDK_MAJOR.equals(majorOf(actual))) {
            throw new IllegalStateException(
                    "Consul API binding requires major " + SDK_MAJOR + " but loaded " + actual);
        }
    }

    static String loadedDependencyVersion() {
        Package sdkPackage = ConsulClient.class.getPackage();
        String implementationVersion = sdkPackage.getImplementationVersion();
        if (implementationVersion != null) {
            return implementationVersion;
        }

        if (ConsulClient.class.getProtectionDomain().getCodeSource() == null) {
            throw new IllegalStateException(
                    "Cannot determine the loaded Consul API dependency location");
        }
        URL location = ConsulClient.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            URI uri = location.toURI();
            Path fileName = Path.of(uri).getFileName();
            String name = fileName == null ? "" : fileName.toString();
            if (name.startsWith(ARTIFACT_PREFIX) && name.endsWith(".jar")) {
                return name.substring(ARTIFACT_PREFIX.length(), name.length() - 4);
            }
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Cannot inspect the loaded Consul API dependency location", failure);
        }
        throw new IllegalStateException(
                "Cannot determine the loaded Consul API dependency version from " + location);
    }

    private static String majorOf(String version) {
        if (version == null) {
            return null;
        }
        int separator = version.indexOf('.');
        return separator < 0 ? version : version.substring(0, separator);
    }
}
