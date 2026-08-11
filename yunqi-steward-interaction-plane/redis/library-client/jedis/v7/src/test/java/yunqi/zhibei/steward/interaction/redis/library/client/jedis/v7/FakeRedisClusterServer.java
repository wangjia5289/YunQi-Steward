package yunqi.zhibei.steward.interaction.redis.library.client.jedis.v7;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FakeRedisClusterServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread acceptor;

    FakeRedisClusterServer() throws IOException {
        serverSocket = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(this::acceptConnections);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // The fixture is already being closed.
        }
        try {
            acceptor.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        connections.shutdownNow();
    }

    private void acceptConnections() {
        try {
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                connections.submit(() -> serve(socket));
            }
        } catch (IOException ignored) {
            if (!serverSocket.isClosed()) {
                throw new AssertionError("fake Redis cluster acceptor failed", ignored);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket; InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream()) {
            while (true) {
                List<String> command = readCommand(input);
                if (command == null) {
                    return;
                }
                writeResponse(output, command);
            }
        } catch (IOException ignored) {
            // Clients close their discovery connection as part of normal cleanup.
        }
    }

    private void writeResponse(OutputStream output, List<String> command) throws IOException {
        String operation = command.getFirst().toUpperCase(java.util.Locale.ROOT);
        if (operation.equals("CLIENT") || operation.equals("PING")
                || operation.equals("AUTH")) {
            output.write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
        } else if (operation.equals("CLUSTER") && command.size() > 1
                && command.get(1).equalsIgnoreCase("SLOTS")) {
            String response = "*1\r\n"
                    + "*3\r\n"
                    + ":0\r\n"
                    + ":16383\r\n"
                    + "*3\r\n"
                    + "$9\r\n127.0.0.1\r\n"
                    + ":" + port() + "\r\n"
                    + "$40\r\n0000000000000000000000000000000000000000\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
        } else {
            output.write("-ERR unsupported fake cluster operation\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        }
        output.flush();
    }

    private static List<String> readCommand(InputStream input) throws IOException {
        int marker = input.read();
        if (marker < 0) {
            return null;
        }
        if (marker != '*') {
            throw new IOException("expected RESP array");
        }
        int count = Integer.parseInt(readLine(input));
        List<String> command = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.read() != '$') {
                throw new IOException("expected RESP bulk string");
            }
            int length = Integer.parseInt(readLine(input));
            byte[] value = input.readNBytes(length);
            if (value.length != length || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("truncated RESP bulk string");
            }
            command.add(new String(value, StandardCharsets.UTF_8));
        }
        return command;
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("truncated RESP line");
            }
            if (previous == '\r' && current == '\n') {
                line.setLength(line.length() - 1);
                return line.toString();
            }
            line.append((char) current);
            previous = current;
        }
    }
}
