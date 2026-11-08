package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatResponse;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TlsTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @TempDir
    static Path certificates;
    private static TestTls tls;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @BeforeAll
    public static void generateCertificates() throws Exception {
        tls = new TestTls(certificates);
    }

    @AfterEach
    public void closeEverything() throws Exception {
        for (AutoCloseable resource : resources.reversed()) {
            resource.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.3", "TLSv1.2"})
    public void messagesFlowAndCloseFlushesOverTls(String protocol) throws Exception {
        BlockingQueue<MessageLite> serverReceived = new LinkedBlockingQueue<>();
        BlockingQueue<SSLSession> serverSessions = new LinkedBlockingQueue<>();
        BlockingQueue<DisconnectReason> serverReasons = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(serverBuilder(tls.serverContext(tls.localhost))
                .onConnected(connection -> serverSessions.add(connection.tlsSession()))
                .onMessage(HeartBeatRequest.class, (connection, message) -> {
                    serverReceived.add(message);
                    if (message.getRequestTimeMillis() == 0) {
                        connection.send(HeartBeatResponse.newBuilder().setRequestTimeMillis(0).build());
                    }
                })
                .onDisconnected((connection, reason, cause) -> serverReasons.add(reason)));
        BlockingQueue<MessageLite> clientReceived = new LinkedBlockingQueue<>();
        ProtoClient client = track(clientBuilder(server, "localhost", tls.clientContext(protocol, null))
                .onOtherMessage((c, message) -> clientReceived.add(message))
                .build());
        client.connect();

        assertEquals(protocol, client.tlsSession().getProtocol());
        assertEquals(protocol, serverSessions.poll(5, TimeUnit.SECONDS).getProtocol());
        assertTrue(client.send(request(0)));
        assertEquals(HeartBeatResponse.newBuilder().setRequestTimeMillis(0).build(), clientReceived.poll(5, TimeUnit.SECONDS));

        int total = 50_000;   // still queued when close() is called: closing over TLS must flush them
        for (int i = 1; i <= total; i++) {
            assertTrue(client.send(request(i)));
        }
        client.close();
        assertEquals(DisconnectReason.CLOSED_LOCALLY, client.awaitClosed());
        assertEquals(DisconnectReason.CLOSED_BY_PEER, serverReasons.poll(5, TimeUnit.SECONDS));
        for (int i = 0; i <= total; i++) {
            assertEquals(i, ((HeartBeatRequest) serverReceived.poll(5, TimeUnit.SECONDS)).getRequestTimeMillis());
        }
    }

    @Test
    public void idleConnectionKeepsWorkingOverTls() throws Exception {
        // The reader wakes on a socket timeout after the idle delay (1 s) to release its buffer: the TLS socket must
        // survive read timeouts
        BlockingQueue<MessageLite> received = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(serverBuilder(tls.serverContext(tls.localhost))
                .onOtherMessage((connection, message) -> received.add(message)));
        ProtoClient client = track(clientBuilder(server, "localhost", tls.clientContext("TLS", null)).build());
        client.connect();
        assertTrue(client.send(request("x".repeat(20_000))));
        assertNotNull(received.poll(5, TimeUnit.SECONDS));
        Thread.sleep(1500);
        assertTrue(client.send(request(1)));
        assertEquals(request(1), received.poll(5, TimeUnit.SECONDS));
        assertTrue(client.isOpen());
    }

    @Test
    public void untrustedServerCertificateFailsTheConnect() throws Exception {
        AtomicInteger serverCalls = new AtomicInteger();
        ProtoServer server = startServer(counting(serverBuilder(tls.serverContext(tls.untrusted)), serverCalls));
        ProtoClient client = track(clientBuilder(server, "localhost", tls.clientContext("TLS", null))
                .onConnected(c -> serverCalls.incrementAndGet())
                .build());
        assertThrows(SSLException.class, client::connect);
        assertEquals(DisconnectReason.TLS_ERROR, client.awaitClosed());
        assertNoConnections(server, serverCalls);
    }

    @Test
    public void hostnameVerification() throws Exception {
        // A trusted certificate, but for other.example
        ProtoServer server = startServer(serverBuilder(tls.serverContext(tls.otherHost)));
        ProtoClient verifying = track(clientBuilder(server, "localhost", tls.clientContext("TLS", null)).build());
        assertThrows(SSLException.class, verifying::connect);
        assertEquals(DisconnectReason.TLS_ERROR, verifying.awaitClosed());

        ProtoClient notVerifying = track(clientBuilder(server, "localhost", tls.clientContext("TLS", null))
                .tlsHostnameVerification(false).build());
        notVerifying.connect();
        assertTrue(notVerifying.isOpen());
    }

    @Test
    public void clientAuthentication() throws Exception {
        BlockingQueue<SSLSession> sessions = new LinkedBlockingQueue<>();
        AtomicInteger serverCalls = new AtomicInteger();
        ProtoServer server = startServer(serverBuilder(tls.serverContext(tls.localhost))
                .tlsClientAuth(TlsClientAuth.NEED)
                .onConnected(connection -> {
                    serverCalls.incrementAndGet();
                    sessions.add(connection.tlsSession());
                }));

        ProtoClient anonymous = track(clientBuilder(server, "localhost", tls.clientContext("TLS", null)).build());
        assertThrows(IOException.class, anonymous::connect);
        assertRejectedByThePeer(anonymous.awaitClosed());
        assertNull(sessions.poll(300, TimeUnit.MILLISECONDS));

        ProtoClient authenticated = track(clientBuilder(server, "localhost", tls.clientContext("TLS", tls.client)).build());
        authenticated.connect();
        SSLSession session = sessions.poll(5, TimeUnit.SECONDS);
        assertNotNull(session);
        assertEquals("CN=client", ((X509Certificate) session.getPeerCertificates()[0]).getSubjectX500Principal().getName());
        assertEquals(1, serverCalls.get());
    }

    @Test
    public void tlsClientAndPlainServerDontConnect() throws Exception {
        AtomicInteger serverCalls = new AtomicInteger();
        ProtoServer plain = startServer(counting(ProtoServer.builder(new InetSocketAddress(LOOPBACK, 0)), serverCalls));
        ProtoClient tlsClient = track(clientBuilder(plain, "localhost", tls.clientContext("TLS", null)).build());
        assertThrows(IOException.class, tlsClient::connect);
        assertRejectedByThePeer(tlsClient.awaitClosed());
        assertNoConnections(plain, serverCalls);
    }

    @Test
    public void plainClientAndTlsServerDontConnect() throws Exception {
        AtomicInteger serverCalls = new AtomicInteger();
        ProtoServer server = startServer(counting(serverBuilder(tls.serverContext(tls.localhost)), serverCalls));
        ProtoClient plainClient = track(ProtoClient.builder("localhost", server.localAddress().getPort())
                .handshakeTimeout(Duration.ofSeconds(2)).build());
        assertThrows(IOException.class, plainClient::connect);
        assertNoConnections(server, serverCalls);
    }

    @Test
    public void tlsHandshakeTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, LOOPBACK)) {   // accepts, then never answers
            ProtoClient client = track(ProtoClient.builder("localhost", silent.getLocalPort())
                    .tls(tls.clientContext("TLS", null))
                    .handshakeTimeout(Duration.ofMillis(300))
                    .build());
            Thread acceptor = Thread.startVirtualThread(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(5000);
                } catch (IOException | InterruptedException e) {
                    // closed by the test
                }
            });
            long start = System.nanoTime();
            assertInstanceOf(SocketTimeoutException.class, assertThrows(IOException.class, client::connect));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            assertEquals(DisconnectReason.TLS_ERROR, client.awaitClosed());
            acceptor.interrupt();
        }
    }

    @Test
    public void buildersValidateTlsSettings() {
        assertThrows(NullPointerException.class, () -> ProtoClient.builder("localhost", 1).tls(null));
        assertThrows(NullPointerException.class, () -> ProtoServer.builder(0).tls(null));
        assertThrows(NullPointerException.class, () -> ProtoServer.builder(0).tlsClientAuth(null));
    }

    // ------------------------------------------------------------------ helpers

    private static HeartBeatRequest request(long i) {
        return HeartBeatRequest.newBuilder().setRequestTimeMillis(i).build();
    }

    private static HeartBeatRequest request(String text) {
        return HeartBeatRequest.newBuilder().setRequestMessage(text).build();
    }

    private static ProtoServer.Builder serverBuilder(SSLContext context) {
        return ProtoServer.builder(new InetSocketAddress(LOOPBACK, 0)).tls(context);
    }

    private static ProtoClient.Builder clientBuilder(ProtoServer server, String host, SSLContext context) {
        return ProtoClient.builder(host, server.localAddress().getPort()).tls(context);
    }

    private ProtoServer startServer(ProtoServer.Builder builder) throws IOException {
        ProtoServer server = track(builder.build());
        server.start();
        return server;
    }

    private <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    /** Counts every connection, message and disconnect the server reports. */
    private static ProtoServer.Builder counting(ProtoServer.Builder builder, AtomicInteger calls) {
        return builder
                .onConnected(connection -> calls.incrementAndGet())
                .onOtherMessage((connection, message) -> calls.incrementAndGet())
                .onDisconnected((connection, reason, cause) -> calls.incrementAndGet());
    }

    /**
     * When the peer is the one that rejects the handshake, it closes with this side's bytes still unread, which sends a
     * TCP reset; the reset can overtake the peer's TLS alert (or preamble). So this side sees either the TLS error or
     * a connection reset. With TLS 1.3 that includes a rejected client certificate: the client's side of the handshake
     * is already over when the server checks it.
     */
    private static void assertRejectedByThePeer(DisconnectReason reason) {
        assertTrue(reason == DisconnectReason.TLS_ERROR || reason == DisconnectReason.IO_ERROR
                || reason == DisconnectReason.CLOSED_BY_PEER, reason.toString());
    }

    /** No listener call, and no connection left open (a failed handshake is never reported). */
    private static void assertNoConnections(ProtoServer server, AtomicInteger serverCalls) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!server.connections().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(200);
        assertTrue(server.connections().isEmpty());
        assertEquals(0, serverCalls.get(), "Listener called for a connection whose handshake failed");
    }
}
