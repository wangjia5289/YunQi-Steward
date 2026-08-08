package yunqi.zhibei.steward.binding.minio.java.v8;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;

import java.util.Objects;

public final class MinioJava8Handle {

    private final MinioClient client;
    private final OkHttpClient httpClient;

    MinioJava8Handle(MinioClient client, OkHttpClient httpClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public MinioClient client() {
        return client;
    }

    public OkHttpClient httpClient() {
        return httpClient;
    }
}
