package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.ConnectionFailure;
import io.github.quantranuk.protobuf.nio.internal.ProtobufSerializer;
import io.github.quantranuk.protobuf.nio.internal.Protocol;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatResponse;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback tests of the public client and server API.
 */
public class ProtoClientServerTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    public void closeEverything() throws Exception {
        for (AutoCloseable resource : resources.reversed()) {
            resource.close();
        }
    }

    // ------------------------------------------------------------------ request/response

    @Test
    public void requestResponse() throws Exception {
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onMessage(HeartBeatRequest.class, (connection, request) -> connection.send(HeartBeatResponse.newBuilder()
                        .setRequestTimeMillis(request.getRequestTimeMillis()).setResponseMessage("pong").build())));
        BlockingQueue<HeartBeatResponse> responses = new LinkedBlockingQueue<>();
        ProtoClient client = client(server, ProtoClientListener.builder()
                .onMessage(HeartBeatResponse.class, (c, response) -> responses.add(response)));
        client.connect();
        assertTrue(client.isOpen());
        for (int i = 0; i < 1000; i++) {
            assertTrue(client.send(request(i)));
        }
        for (int i = 0; i < 1000; i++) {
            HeartBeatResponse response = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "Only received " + i + " responses");
            assertEquals(i, response.getRequestTimeMillis());
        }
    }

    @Test
    public void listenerCallbacksAndIdentity() throws Exception {
        BlockingQueue<ProtoConnection> serverConnected = new LinkedBlockingQueue<>();
        BlockingQueue<DisconnectReason> serverDisconnected = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onConnected(serverConnected::add)
                .onDisconnected((connection, reason, cause) -> serverDisconnected.add(reason)));
        List<String> clientCalls = new CopyOnWriteArrayList<>();
        ProtoClient client = client(server, ProtoClientListener.builder()
                .onConnected(c -> clientCalls.add("connected " + c.id()))
                .onDisconnected((c, reason, cause) -> clientCalls.add("disconnected " + reason + " " + cause)));
        assertNull(client.localAddress());
        assertTrue(client.remoteAddress().isUnresolved());
        client.connect();

        ProtoConnection connection = serverConnected.poll(5, TimeUnit.SECONDS);
        assertNotNull(connection);
        assertTrue(server.connections().contains(connection));
        assertEquals(client.localAddress(), connection.remoteAddress());
        assertEquals(client.remoteAddress(), connection.localAddress());
        assertNotEquals(client.id(), connection.id());
        assertEquals("ProtoClient#" + client.id() + "[" + client.remoteAddress() + "]", client.toString());
        assertEquals("ProtoConnection#" + connection.id() + "[" + connection.remoteAddress() + "]", connection.toString());
        assertNull(client.tlsSession());

        client.close();
        assertEquals(DisconnectReason.CLOSED_LOCALLY, client.awaitClosed());
        assertEquals(DisconnectReason.CLOSED_BY_PEER, serverDisconnected.poll(5, TimeUnit.SECONDS));
        assertEquals(List.of("connected " + client.id(), "disconnected CLOSED_LOCALLY null"), clientCalls);
        assertFalse(server.connections().contains(connection));
        assertFalse(client.isOpen());
        assertFalse(client.send(request(1)));
    }

    @Test
    public void idsIncreaseAcrossClientsAndConnections() throws Exception {
        BlockingQueue<ProtoConnection> connected = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(ProtoServerListener.builder().onConnected(connected::add));
        ProtoClient first = client(server, ProtoClientListener.builder());
        ProtoClient second = client(server, ProtoClientListener.builder());
        assertTrue(second.id() > first.id());
        first.connect();
        assertTrue(connected.poll(5, TimeUnit.SECONDS).id() > second.id());
    }

    @Test
    public void attachments() throws Exception {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onConnected(connection -> connection.attach("session-" + connection.id()))
                .onMessage(HeartBeatRequest.class, (connection, request) -> seen.add(connection.attachment())));
        ProtoClient client = client(server, ProtoClientListener.builder());
        client.attach(42);
        assertEquals(42, (Integer) client.attachment());
        client.connect();
        client.send(request(1));
        assertTrue(seen.poll(5, TimeUnit.SECONDS).startsWith("session-"));
    }

    // ------------------------------------------------------------------ client states and connect()

    @Test
    public void clientStateRules() throws Exception {
        ProtoServer server = startServer(ProtoServerListener.builder());
        ProtoClient client = client(server, ProtoClientListener.builder());
        assertThrows(IllegalStateException.class, () -> client.send(request(1)));
        assertThrows(IllegalStateException.class, client::awaitClosed);
        assertEquals(0, client.pendingMessages());
        client.connect();
        assertThrows(IllegalStateException.class, client::connect);

        AtomicInteger calls = new AtomicInteger();
        ProtoClient closedFirst = client(server, new ProtoClientListener() {
            @Override
            public void onConnected(ProtoClient c) {
                calls.incrementAndGet();
            }

            @Override
            public void onDisconnected(ProtoClient c, DisconnectReason reason, Throwable cause) {
                calls.incrementAndGet();
            }
        });
        closedFirst.close();
        assertThrows(IllegalStateException.class, closedFirst::connect);
        assertFalse(closedFirst.isOpen());
        assertEquals(0, calls.get());
    }

    @Test
    public void connectRefusedThrowsWithoutListenerCalls() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK)) {
            port = probe.getLocalPort();
        }
        AtomicInteger calls = new AtomicInteger();
        ProtoClient client = track(ProtoClient.builder(new InetSocketAddress(LOOPBACK, port))
                .listener(countingListener(calls)).build());
        assertThrows(ConnectException.class, client::connect);
        assertFalse(client.isOpen());
        assertThrows(IllegalStateException.class, client::connect);
        assertFalse(client.send(request(1)));
        assertEquals(DisconnectReason.IO_ERROR, client.awaitClosed());
        assertEquals(0, calls.get());
    }

    @Test
    public void unknownHostThrows() {
        ProtoClient client = track(ProtoClient.builder("no-such-host.invalid", 1234).build());
        assertThrows(UnknownHostException.class, client::connect);
    }

    @Test
    public void connectTimeout() {
        // A non-routable address: the SYN gets no answer (some networks reject it at once instead)
        ProtoClient client = track(ProtoClient.builder("10.255.255.1", 3456).connectTimeout(Duration.ofMillis(300)).build());
        long start = System.nanoTime();
        assertThrows(IOException.class, client::connect);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 3000, "connect() took " + elapsedMillis + " ms");
    }

    @Test
    public void closeWhileConnectingMakesConnectThrow() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, LOOPBACK)) {   // accepts (backlog) but never sends a preamble
            ProtoClient client = track(ProtoClient.builder(new InetSocketAddress(LOOPBACK, silent.getLocalPort())).build());
            CompletableFuture<Void> connecting = client.connectAsync();
            Thread.sleep(200);
            assertFalse(connecting.isDone());
            client.close();
            ExecutionException e = assertThrows(ExecutionException.class, () -> connecting.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, e.getCause());
            assertEquals(DisconnectReason.CLOSED_LOCALLY, client.awaitClosed());
        }
    }

    @Test
    public void connectToSomethingElseIsAProtocolMismatch() throws Exception {
        try (ServerSocket other = new ServerSocket(0, 50, LOOPBACK)) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = other.accept()) {
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                    socket.getInputStream().read();
                } catch (IOException ignored) {
                    // the client hangs up
                }
            });
            ProtoClient client = track(ProtoClient.builder(new InetSocketAddress(LOOPBACK, other.getLocalPort())).build());
            ConnectionFailure failure = assertThrows(ConnectionFailure.class, client::connect);
            assertEquals(DisconnectReason.PROTOCOL_MISMATCH, failure.reason());
        }
    }

    @Test
    public void messagesSentWhileConnectingAreSentAfterThePreamble() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 50, LOOPBACK)) {
            ProtoClient client = track(ProtoClient.builder(new InetSocketAddress(LOOPBACK, raw.getLocalPort())).build());
            CompletableFuture<Void> connecting = client.connectAsync();
            assertTrue(client.send(request(5)));   // connecting from the moment connectAsync() returns
            try (Socket peer = raw.accept()) {
                peer.getOutputStream().write(Protocol.preamble(Protocol.VERSION, 0));
                connecting.get(5, TimeUnit.SECONDS);
                byte[] expected = ByteBuffer.allocate(64 * 1024)
                        .put(Protocol.preamble(Protocol.VERSION, 0)).put(ProtobufSerializer.serialize(request(5))).flip().array();
                int length = Protocol.PREAMBLE_LENGTH + ProtobufSerializer.getSerializedSize(request(5));
                byte[] actual = peer.getInputStream().readNBytes(length);
                assertEquals(ByteBuffer.wrap(expected, 0, length), ByteBuffer.wrap(actual));
            }
        }
    }

    @Test
    public void connectAsyncCompletes() throws Exception {
        ProtoServer server = startServer(ProtoServerListener.builder());
        ProtoClient client = client(server, ProtoClientListener.builder());
        client.connectAsync().get(5, TimeUnit.SECONDS);
        assertTrue(client.isOpen());
    }

    // ------------------------------------------------------------------ server

    @Test
    public void broadcastReachesEveryMatchingConnection() throws Exception {
        CountDownLatch allConnected = new CountDownLatch(4);
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onConnected(connection -> {
                    connection.attach(connection.id() % 2 == 0 ? "even" : "odd");
                    allConnected.countDown();
                }));
        List<BlockingQueue<MessageLite>> inboxes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockingQueue<MessageLite> inbox = new LinkedBlockingQueue<>();
            inboxes.add(inbox);
            client(server, ProtoClientListener.builder().onOtherMessage((c, message) -> inbox.add(message))).connect();
        }
        assertTrue(allConnected.await(5, TimeUnit.SECONDS));
        assertEquals(4, server.connections().size());

        assertEquals(4, server.broadcast(request(1)));
        for (BlockingQueue<MessageLite> inbox : inboxes) {
            assertEquals(request(1), inbox.poll(5, TimeUnit.SECONDS));
        }
        long evens = server.connections().stream().filter(c -> "even".equals(c.attachment())).count();
        assertEquals(evens, server.broadcast(request(2), c -> "even".equals(c.attachment())));
        int received = 0;
        for (BlockingQueue<MessageLite> inbox : inboxes) {
            if (inbox.poll(500, TimeUnit.MILLISECONDS) != null) {
                received++;
            }
        }
        assertEquals(evens, received);
    }

    @Test
    public void serverCloseClosesEveryConnection() throws Exception {
        ProtoServer server = startServer(ProtoServerListener.builder());
        List<ProtoClient> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ProtoClient client = client(server, ProtoClientListener.builder());
            client.connect();
            clients.add(client);
        }
        assertTrue(server.isOpen());
        server.close();
        assertFalse(server.isOpen());
        server.awaitClosed();
        assertTrue(server.connections().isEmpty());
        for (ProtoClient client : clients) {
            assertEquals(DisconnectReason.CLOSED_BY_PEER, client.awaitClosed());
        }
        assertThrows(IllegalStateException.class, server::start);
        server.close();   // idempotent
    }

    @Test
    public void serverCloseFromAListenerDoesNotDeadlock() throws Exception {
        ProtoServer[] holder = new ProtoServer[1];
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onMessage(HeartBeatRequest.class, (connection, request) -> holder[0].close()));
        holder[0] = server;
        ProtoClient trigger = client(server, ProtoClientListener.builder());
        ProtoClient bystander = client(server, ProtoClientListener.builder());
        trigger.connect();
        bystander.connect();
        trigger.send(request(1));
        server.awaitClosed();
        assertEquals(DisconnectReason.CLOSED_BY_PEER, trigger.awaitClosed());
        assertEquals(DisconnectReason.CLOSED_BY_PEER, bystander.awaitClosed());
    }

    @Test
    public void serverStateRules() throws Exception {
        ProtoServer server = track(ProtoServer.builder(new InetSocketAddress(LOOPBACK, 0)).build());
        assertThrows(IllegalStateException.class, server::localAddress);
        assertThrows(IllegalStateException.class, server::awaitClosed);
        assertFalse(server.isOpen());
        server.start();
        assertNotEquals(0, server.localAddress().getPort());
        assertThrows(IllegalStateException.class, server::start);

        ProtoServer taken = track(ProtoServer.builder(server.localAddress()).build());
        assertThrows(IOException.class, taken::start);
    }

    @Test
    public void maxConnectionsRejectsTheExtraConnection() throws Exception {
        ProtoServer server = startServer(ProtoServer.builder(new InetSocketAddress(LOOPBACK, 0)).maxConnections(2));
        client(server, ProtoClientListener.builder()).connect();
        client(server, ProtoClientListener.builder()).connect();
        ProtoClient third = client(server, ProtoClientListener.builder());
        assertThrows(IOException.class, third::connect);
    }

    @Test
    public void connectedListenerExceptionDoesNotStopAccepting() throws Exception {
        BlockingQueue<MessageLite> received = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onConnected(connection -> {
                    throw new RuntimeException("boom (expected by the test)");
                })
                .onOtherMessage((connection, message) -> received.add(message)));
        for (int i = 0; i < 3; i++) {
            ProtoClient client = client(server, ProtoClientListener.builder());
            client.connect();
            client.send(request(i));
            assertEquals(request(i), received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void connectionsAreHandledConcurrently() throws Exception {
        // D8: a handler blocked on one connection doesn't stall the others
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<Long> handled = new LinkedBlockingQueue<>();
        ProtoServer server = startServer(ProtoServerListener.builder()
                .onMessage(HeartBeatRequest.class, (connection, request) -> {
                    if (request.getRequestTimeMillis() == 1) {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    handled.add(request.getRequestTimeMillis());
                }));
        ProtoClient blocked = client(server, ProtoClientListener.builder());
        ProtoClient other = client(server, ProtoClientListener.builder());
        blocked.connect();
        other.connect();
        blocked.send(request(1));
        Thread.sleep(100);
        other.send(request(2));
        assertEquals(2L, handled.poll(5, TimeUnit.SECONDS), "the second connection was stalled");
        release.countDown();
        assertEquals(1L, handled.poll(5, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ listener builders and builders

    @Test
    public void listenerBuilderDispatchesByExactType() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        ProtoClientListener listener = ProtoClientListener.builder()
                .onMessage(HeartBeatRequest.class, (client, message) -> events.add("request"))
                .onOtherMessage((client, message) -> events.add("other " + message.getClass().getSimpleName()))
                .build();
        listener.onMessage(null, request(1));
        listener.onMessage(null, HeartBeatResponse.getDefaultInstance());
        assertEquals("request", events.poll());
        assertEquals("other HeartBeatResponse", events.poll());

        ProtoServerListener dropping = ProtoServerListener.builder().build();
        dropping.onMessage(null, request(1));   // logged once and dropped
        dropping.onMessage(null, request(2));

        ProtoClientListener.Builder builder = ProtoClientListener.builder().onMessage(HeartBeatRequest.class, (c, m) -> { });
        assertThrows(IllegalArgumentException.class, () -> builder.onMessage(HeartBeatRequest.class, (c, m) -> { }));
    }

    @Test
    public void buildersValidateAtOnce() {
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 0));
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 1).readTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 1).connectTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 1).readBufferSize(10));
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 1).maxMessageSize(-1));
        assertThrows(IllegalArgumentException.class, () -> ProtoClient.builder("localhost", 1).handshakeTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ProtoServer.builder(-1));
        assertThrows(IllegalArgumentException.class, () -> ProtoServer.builder(0).backlog(0));
        assertThrows(IllegalArgumentException.class, () -> ProtoServer.builder(0).maxConnections(0));
        assertThrows(NullPointerException.class, () -> ProtoServer.builder(0).listener(null));
    }

    @Test
    public void clientBuilderIsReusable() throws Exception {
        ProtoServer server = startServer(ProtoServerListener.builder());
        ProtoClient.Builder builder = ProtoClient.builder(server.localAddress());
        ProtoClient first = track(builder.build());
        ProtoClient second = track(builder.build());
        assertNotEquals(first.id(), second.id());
        first.connect();
        second.connect();
        assertTrue(first.isOpen() && second.isOpen());
    }

    // ------------------------------------------------------------------ helpers

    private static HeartBeatRequest request(long i) {
        return HeartBeatRequest.newBuilder().setRequestTimeMillis(i).build();
    }

    private <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    private ProtoServer startServer(ProtoServerListener.Builder listener) throws IOException {
        return startServer(ProtoServer.builder(new InetSocketAddress(LOOPBACK, 0)).listener(listener.build()));
    }

    private ProtoServer startServer(ProtoServer.Builder builder) throws IOException {
        ProtoServer server = track(builder.build());
        server.start();
        return server;
    }

    private ProtoClient client(ProtoServer server, ProtoClientListener.Builder listener) {
        return client(server, listener.build());
    }

    private ProtoClient client(ProtoServer server, ProtoClientListener listener) {
        return track(ProtoClient.builder("localhost", server.localAddress().getPort()).listener(listener).build());
    }

    private static ProtoClientListener countingListener(AtomicInteger calls) {
        return new ProtoClientListener() {
            @Override
            public void onConnected(ProtoClient client) {
                calls.incrementAndGet();
            }

            @Override
            public void onMessage(ProtoClient client, MessageLite message) {
                calls.incrementAndGet();
            }

            @Override
            public void onSendFailure(ProtoClient client, MessageLite message, SendException cause) {
                calls.incrementAndGet();
            }

            @Override
            public void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause) {
                calls.incrementAndGet();
            }
        };
    }
}
