package yunqi.zhibei.steward.binding.milvus.sdk.v2;

import yunqi.zhibei.steward.support.testing.BindingContract;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.milvus.grpc.ConnectRequest;
import io.milvus.grpc.ConnectResponse;
import io.milvus.grpc.ListDatabasesRequest;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.grpc.Status;
import io.milvus.v2.client.ConnectConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusSdk2BindingTest {

    @Test
    void satisfiesTheRefreshSafeBindingContractAgainstLocalProtocolFixture() throws Exception {
        Server server = ServerBuilder.forPort(0)
                .addService(new MilvusServiceGrpc.MilvusServiceImplBase() {
                    @Override
                    public void connect(
                            ConnectRequest request,
                            StreamObserver<ConnectResponse> responseObserver) {
                        responseObserver.onNext(ConnectResponse.newBuilder()
                                .setStatus(Status.getDefaultInstance())
                                .setIdentifier(1)
                                .build());
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void listDatabases(
                            ListDatabasesRequest request,
                            StreamObserver<ListDatabasesResponse> responseObserver) {
                        responseObserver.onNext(ListDatabasesResponse.newBuilder()
                                .setStatus(Status.getDefaultInstance())
                                .addDbNames("vectors-a")
                                .addDbNames("vectors-b")
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        try {
            BindingContract.verify(
                    new MilvusSdk2Binding(),
                    configuration(server.getPort(), "vectors-a"),
                    configuration(server.getPort(), "vectors-b"),
                    "milvus-secret");
        } finally {
            server.shutdownNow();
            assertThat(server.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void mapsConnectionConfigurationWithoutAnRpcDeadline() {
        MilvusSdk2Configuration configuration = new MilvusSdk2Configuration(
                URI.create("https://milvus.internal:19530"),
                Optional.of("milvus-secret"),
                "vectors",
                Duration.ofSeconds(4));

        ConnectConfig nativeConfiguration = MilvusSdk2Binding.nativeConfiguration(configuration);

        assertThat(nativeConfiguration.getUri()).isEqualTo("https://milvus.internal:19530");
        assertThat(nativeConfiguration.getToken()).isEqualTo("milvus-secret");
        assertThat(nativeConfiguration.getDbName()).isEqualTo("vectors");
        assertThat(nativeConfiguration.getSecure()).isTrue();
        assertThat(nativeConfiguration.getConnectTimeoutMs()).isEqualTo(4_000);
        assertThat(configuration.toString())
                .contains("[REDACTED]")
                .doesNotContain("milvus-secret");
        assertThat(Arrays.stream(MilvusSdk2Configuration.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("rpcDeadline");
    }

    @Test
    void validatesEndpointAndCredentials() {
        assertThat(MilvusSdk2Configuration.defaults().database()).isEqualTo("default");

        assertThatThrownBy(() -> new MilvusSdk2Configuration(
                URI.create("grpc://milvus.internal:19530"),
                Optional.empty(),
                "default",
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");

        assertThatThrownBy(() -> new MilvusSdk2Configuration(
                URI.create("http://milvus.internal:19530"),
                Optional.of(" "),
                "default",
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    private static MilvusSdk2Configuration configuration(int port, String database) {
        return new MilvusSdk2Configuration(
                URI.create("http://127.0.0.1:" + port),
                Optional.of("milvus-secret"),
                database,
                Duration.ofSeconds(1));
    }

}
