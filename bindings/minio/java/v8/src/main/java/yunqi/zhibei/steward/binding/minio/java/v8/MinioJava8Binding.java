package yunqi.zhibei.steward.binding.minio.java.v8;

import yunqi.zhibei.steward.lifecycle.BoundResource;
import yunqi.zhibei.steward.lifecycle.Health;
import yunqi.zhibei.steward.lifecycle.ProbeScope;
import yunqi.zhibei.steward.lifecycle.ResourceBinding;
import io.minio.MinioClient;
import io.minio.credentials.StaticProvider;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class MinioJava8Binding
        implements ResourceBinding<MinioJava8Configuration, MinioJava8Handle> {

    public static BoundResource<MinioJava8Handle> start(MinioJava8Configuration configuration)
            throws Exception {
        return BoundResource.start(configuration, new MinioJava8Binding());
    }

    @Override
    public MinioJava8Handle create(MinioJava8Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(configuration.pool().maximumConnections());
        dispatcher.setMaxRequestsPerHost(configuration.pool().maximumRequestsPerHost());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(configuration.connectTimeout())
                .readTimeout(configuration.readTimeout())
                .writeTimeout(configuration.writeTimeout())
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(
                        configuration.pool().maximumConnections(),
                        configuration.pool().keepAlive().toMillis(),
                        TimeUnit.MILLISECONDS))
                .build();
        try {
            MinioClient.Builder builder = MinioClient.builder()
                    .endpoint(configuration.endpoint().toString())
                    .httpClient(httpClient, true);
            configuration.region().ifPresent(builder::region);
            if (configuration.accessKey().isPresent()) {
                if (configuration.sessionToken().isPresent()) {
                    builder.credentialsProvider(new StaticProvider(
                            configuration.accessKey().orElseThrow(),
                            configuration.secretKey().orElseThrow(),
                            configuration.sessionToken().orElseThrow()));
                } else {
                    builder.credentials(
                            configuration.accessKey().orElseThrow(),
                            configuration.secretKey().orElseThrow());
                }
            }
            return new MinioJava8Handle(builder.build(), httpClient);
        } catch (RuntimeException | Error failure) {
            closeHttpClient(httpClient);
            throw failure;
        }
    }

    @Override
    public Health check(MinioJava8Handle handle) {
        Objects.requireNonNull(handle, "handle");
        try {
            handle.client().listBuckets();
            return Health.healthy(ProbeScope.REMOTE);
        } catch (Exception exception) {
            return Health.unhealthy(ProbeScope.REMOTE);
        }
    }

    @Override
    public void close(MinioJava8Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        Exception failure = null;
        try {
            handle.client().close();
        } catch (Exception exception) {
            failure = exception;
        } finally {
            closeHttpClient(handle.httpClient());
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeHttpClient(OkHttpClient httpClient) {
        httpClient.connectionPool().evictAll();
        httpClient.dispatcher().executorService().shutdown();
    }
}
