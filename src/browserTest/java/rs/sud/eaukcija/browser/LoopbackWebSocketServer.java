package rs.sud.eaukcija.browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Minimal in-process RFC 6455 handshake endpoint for the network-guard test. */
final class LoopbackWebSocketServer implements AutoCloseable {

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private volatile Socket acceptedSocket;

    LoopbackWebSocketServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0));
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "browser-test-loopback-websocket");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::acceptOneConnection);
    }

    String url() {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort() + "/network-guard";
    }

    private void acceptOneConnection() {
        try (Socket socket = serverSocket.accept()) {
            acceptedSocket = socket;
            String key = readWebSocketKey(socket);
            writeHandshake(socket, key);

            // Keep the connection alive until the browser or test closes it.
            while (socket.getInputStream().read() != -1) {
                // Frames are irrelevant; a successful real handshake is the proof.
            }
        } catch (IOException ignoredWhenFixtureCloses) {
            // close() interrupts accept/read; browser open status proves success.
        } finally {
            acceptedSocket = null;
        }
    }

    private static String readWebSocketKey(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String key = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0
                    && line.substring(0, separator).trim().toLowerCase(Locale.ROOT)
                            .equals("sec-websocket-key")) {
                key = line.substring(separator + 1).trim();
            }
        }
        if (key == null || key.isBlank()) {
            throw new IOException("WebSocket handshake omitted Sec-WebSocket-Key");
        }
        return key;
    }

    private static void writeHandshake(Socket socket, String key) throws IOException {
        String accept = Base64.getEncoder().encodeToString(
                sha1((key + ACCEPT_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        Writer writer = new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        writer.write("HTTP/1.1 101 Switching Protocols\r\n");
        writer.write("Upgrade: websocket\r\n");
        writer.write("Connection: Upgrade\r\n");
        writer.write("Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
        writer.flush();
    }

    private static byte[] sha1(byte[] input) {
        try {
            // RFC 6455 mandates SHA-1 for Sec-WebSocket-Accept.
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK lacks the RFC 6455 SHA-1 algorithm", exception);
        }
    }

    @Override
    public void close() throws IOException {
        Socket socket = acceptedSocket;
        if (socket != null) {
            socket.close();
        }
        serverSocket.close();
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
