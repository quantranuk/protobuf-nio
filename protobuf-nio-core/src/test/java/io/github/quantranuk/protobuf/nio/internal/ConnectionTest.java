package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.SendException;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatResponse;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.SocketTimeoutException;
import java.net.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback tests of {@link Connection}, against another {@link Connection} or against a raw socket playing the peer.
 */
public class ConnectionTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final int MAX_MESSAGE_SIZE = 2 * 1024 * 1024;

    private ServerSocket serverSocket;
    private final List<Connection> connections = new ArrayList<>();
    private final List<Socket> rawSockets = new ArrayList<>();

    @BeforeEach
    public void openServerSocket() throws IOException {
        serverSocket = new ServerSocket(0, 1024, LOOPBACK);
    }

    @AfterEach
    public void closeEverything() throws IOException {
        // Raw sockets first: a connection's graceful close waits (up to closeTimeout) for its peer to close
        for (Socket socket : rawSockets) {
            socket.close();
        }
        for (Connection connection : connections) {
            connection.close();
        }
        serverSocket.close();
    }

    // ------------------------------------------------------------------ messages

    @Test
    public void messagesFlowBothWaysInOrder() throws Exception {
        Pair pair = connect(config(), config());
        pair.server.events.onMessage = (connection, message) ->
                connection.send(HeartBeatResponse.newBuilder().setRequestTimeMillis(((HeartBeatRequest) message).getRequestTimeMillis()).build());
        for (int i = 0; i < 10_000; i++) {
            assertTrue(pair.client.connection.send(request(i)));
        }
        for (int i = 0; i < 10_000; i++) {
            HeartBeatResponse response = (HeartBeatResponse) pair.client.events.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "Only received " + i + " responses");
            assertEquals(i, response.getRequestTimeMillis());
        }
        assertTrue(pair.client.connection.isOpen());
        assertEquals(10_000, pair.client.connection.messagesWritten());
    }

    @Test
    public void listenerRunsOnTheReaderThread() throws Exception {
        Pair pair = connect(config(), config());
        BlockingQueue<Boolean> onReader = new LinkedBlockingQueue<>();
        pair.server.events.onMessage = (connection, message) -> onReader.add(connection.isReaderThread() && Thread.currentThread().isVirtual());
        pair.client.connection.send(request(1));
        assertEquals(Boolean.TRUE, onReader.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void messagesSentDuringTheHandshakeAreDelivered() throws Exception {
        Socket[] sockets = socketPair();
        Connection client = track(new Connection(sockets[0], config().build(), new Recorder()));
        Recorder serverEvents = new Recorder();
        client.start();
        assertTrue(client.send(request(7)));   // the server side hasn't started: no preamble yet
        Connection server = track(new Connection(sockets[1], config().build(), serverEvents));
        server.start();
        assertEquals(request(7), serverEvents.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void messageLargerThanTheBuffersIsDelivered() throws Exception {
        Pair pair = connect(config(), config());
        String big = "x".repeat(1_000_000);   // well above the 64 KB read and write buffers
        pair.client.connection.send(request("small-before"));
        pair.client.connection.send(request(big));
        pair.client.connection.send(request("small-after"));
        assertEquals("small-before", ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage());
        assertEquals(big, ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage());
        assertEquals("small-after", ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage());
    }

    @Test
    public void concurrentSendersDeliverEverythingInOrderPerSender() throws Exception {
        Pair pair = connect(config(), config());
        int senders = 4;
        int perSender = 50_000;
        List<Thread> threads = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            String name = "s" + s;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < perSender; i++) {
                    pair.client.connection.send(HeartBeatRequest.newBuilder().setRequestTimeMillis(i).setRequestMessage(name).build());
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        Map<String, Long> lastSeen = new HashMap<>();
        for (int i = 0; i < senders * perSender; i++) {
            HeartBeatRequest message = (HeartBeatRequest) pair.server.events.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "Only received " + i + " messages");
            long previous = lastSeen.getOrDefault(message.getRequestMessage(), -1L);
            assertEquals(previous + 1, message.getRequestTimeMillis(), "Out of order for sender " + message.getRequestMessage());
            lastSeen.put(message.getRequestMessage(), message.getRequestTimeMillis());
        }
        assertEquals(senders * perSender, pair.client.connection.messagesWritten());
        assertEquals(0, pair.client.connection.pendingMessages());
        assertEquals(0, pair.client.connection.pendingBytes());
    }

    @Test
    public void listenerExceptionDoesNotStopReading() throws Exception {
        Pair pair = connect(config(), config());
        AtomicInteger calls = new AtomicInteger();
        pair.server.events.onMessage = (connection, message) -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom (expected by the test)");
            }
        };
        pair.client.connection.send(request("first"));
        pair.client.connection.send(request("second"));
        assertEquals("first", ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage());
        assertEquals("second", ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage());
        assertTrue(pair.server.connection.isOpen());
    }

    @Test
    public void tcpNoDelayAvoidsTheDelayedAckStall() throws Exception {
        // Nagle plus delayed ACK held the second of two quick messages for ~40 ms (1.x: 41.5 ms median)
        Pair pair = connect(config(), config());
        AtomicInteger received = new AtomicInteger();
        pair.server.events.onMessage = (connection, message) -> {
            if (received.incrementAndGet() % 2 == 0) {
                connection.send(HeartBeatResponse.getDefaultInstance());
            }
        };
        long[] delays = new long[21];
        for (int i = 0; i < delays.length; i++) {
            pair.client.connection.send(request(i));
            long pause = System.nanoTime() + 300_000;
            while (System.nanoTime() < pause) {
                Thread.onSpinWait();
            }
            long sent = System.nanoTime();
            pair.client.connection.send(request(i));
            assertNotNull(pair.client.events.messages.poll(5, TimeUnit.SECONDS));
            delays[i] = System.nanoTime() - sent;
        }
        Arrays.sort(delays);
        long medianMillis = TimeUnit.NANOSECONDS.toMillis(delays[delays.length / 2]);
        assertTrue(medianMillis < 20, "Median reply delay " + medianMillis + " ms: is TCP_NODELAY off?");
    }

    // ------------------------------------------------------------------ preamble

    @Test
    public void badPreambleIsAProtocolMismatch() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.out.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertHandshakeFails(peer, ProtocolException.class);
    }

    @Test
    public void onePointXPeerIsAProtocolMismatch() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.out.write(ProtobufSerializer.serialize(request(1)));   // 1.x sends frames at once, without a preamble
        assertHandshakeFails(peer, ProtocolException.class);
    }

    @Test
    public void unsupportedPeerVersionIsAProtocolMismatch() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.out.write(Protocol.preamble(0, 0));
        assertHandshakeFails(peer, ProtocolException.class);
    }

    @Test
    public void newerPeerVersionAndUnknownFlagsAreAccepted() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.out.write(Protocol.preamble(Protocol.VERSION + 5, 0xFFFF));
        peer.out.write(ProtobufSerializer.serialize(request(3)));
        peer.connection.handshake().get(5, TimeUnit.SECONDS);
        assertEquals(request(3), peer.events.messages.poll(5, TimeUnit.SECONDS));
        assertEquals(1, peer.events.connectedCalls.get());
    }

    @Test
    public void missingPreambleTimesOut() throws Exception {
        RawPeer peer = rawPeer(config().handshakeTimeout(Duration.ofMillis(200)));
        assertHandshakeFails(peer, SocketTimeoutException.class);
    }

    @Test
    public void closeDuringTheHandshakeFailsItWithoutListenerCalls() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.connection.send(request(1));
        peer.connection.close();
        ExecutionException e = assertThrows(ExecutionException.class, () -> peer.connection.handshake().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, e.getCause());
        assertEquals(DisconnectReason.CLOSED_LOCALLY, peer.connection.closedFuture().get(5, TimeUnit.SECONDS));
        assertTrue(peer.events.calls.isEmpty(), "Listener called: " + peer.events.calls);
        assertEquals(0, peer.connection.pendingMessages());
    }

    // ------------------------------------------------------------------ bad frames

    static Stream<Arguments> badFrames() {
        byte[] unknownName = "com.example.DoesNotExist".getBytes(StandardCharsets.ISO_8859_1);
        byte[] notAMessage = String.class.getName().getBytes(StandardCharsets.ISO_8859_1);
        byte[] requestName = HeartBeatRequest.class.getName().getBytes(StandardCharsets.ISO_8859_1);
        return Stream.of(
                Arguments.of("bad signature", header(0x01020304, 10, 10)),
                Arguments.of("oversized payload", header(ProtobufSerializer.SIGNATURE, 10, MAX_MESSAGE_SIZE + 1)),
                Arguments.of("huge payload", header(ProtobufSerializer.SIGNATURE, 10, Integer.MAX_VALUE)),
                Arguments.of("negative payload length", header(ProtobufSerializer.SIGNATURE, 10, -1)),
                Arguments.of("negative name length", header(ProtobufSerializer.SIGNATURE, -5, 10)),
                Arguments.of("empty name with a payload", header(ProtobufSerializer.SIGNATURE, 0, 10)),
                Arguments.of("name too long", header(ProtobufSerializer.SIGNATURE, Protocol.MAX_CLASS_NAME_LENGTH + 1, 0)),
                Arguments.of("unknown class", frame(unknownName, new byte[0])),
                Arguments.of("non-protobuf class", frame(notAMessage, new byte[0])),
                Arguments.of("unparseable payload", frame(requestName, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF})));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFrames")
    public void badFrameIsAProtocolError(String description, byte[] bytes) throws Exception {
        RawPeer peer = rawPeer(config().maxMessageSize(MAX_MESSAGE_SIZE));
        peer.out.write(Protocol.PREAMBLE);
        peer.out.write(ProtobufSerializer.serialize(request("valid first")));
        peer.out.write(bytes);
        assertEquals(request("valid first"), peer.events.messages.poll(5, TimeUnit.SECONDS));
        Disconnect disconnect = peer.events.disconnects.poll(5, TimeUnit.SECONDS);
        assertNotNull(disconnect);
        assertEquals(DisconnectReason.PROTOCOL_ERROR, disconnect.reason);
        assertInstanceOf(ProtocolException.class, disconnect.cause);
        assertClosedByPeer(peer.socket);
    }

    @Test
    public void heartbeatFramesNeverReachTheListener() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.out.write(Protocol.PREAMBLE);
        for (int i = 0; i < 5; i++) {
            peer.out.write(Protocol.HEARTBEAT_FRAME);
        }
        peer.out.write(ProtobufSerializer.serialize(request(9)));
        assertEquals(request(9), peer.events.messages.poll(5, TimeUnit.SECONDS));
        assertTrue(peer.events.messages.isEmpty());
    }

    @Test
    public void frameSentOneByteAtATimeIsDelivered() throws Exception {
        RawPeer peer = rawPeer(config());
        peer.socket.setTcpNoDelay(true);
        for (byte b : Protocol.PREAMBLE) {
            peer.out.write(b);
        }
        for (byte b : ProtobufSerializer.serialize(request("x".repeat(300)))) {
            peer.out.write(b);
        }
        assertEquals(request("x".repeat(300)), peer.events.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void frameSplitByAStallLongerThanTheIdleDelayIsDelivered() throws Exception {
        RawPeer peer = rawPeer(config().idleShrinkDelay(Duration.ofMillis(50)));
        byte[] frame = ProtobufSerializer.serialize(request("z".repeat(20_000)));
        peer.out.write(Protocol.PREAMBLE);
        peer.out.write(frame, 0, frame.length / 2);
        Thread.sleep(400);   // several idle delays with half a frame buffered: the buffer must not shrink
        peer.out.write(frame, frame.length / 2, frame.length - frame.length / 2);
        assertEquals(request("z".repeat(20_000)), peer.events.messages.poll(5, TimeUnit.SECONDS));
        Thread.sleep(200);   // now idle with an empty buffer: shrinks, then still works
        peer.out.write(frame);
        assertEquals(request("z".repeat(20_000)), peer.events.messages.poll(5, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ timeouts and heartbeats

    @Test
    public void readTimeoutClosesAnIdleConnection() throws Exception {
        Pair pair = connect(config(), config().readTimeout(Duration.ofMillis(200)));
        long start = System.nanoTime();
        Disconnect disconnect = pair.server.events.disconnects.poll(5, TimeUnit.SECONDS);
        assertEquals(DisconnectReason.READ_TIMEOUT, disconnect.reason);
        assertInstanceOf(SocketTimeoutException.class, disconnect.cause);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= 150 && elapsedMillis < 2000, "Closed after " + elapsedMillis + " ms");
        assertEquals(DisconnectReason.CLOSED_BY_PEER, pair.client.events.disconnects.poll(5, TimeUnit.SECONDS).reason);
    }

    @Test
    public void heartbeatsKeepAnIdleConnectionOpen() throws Exception {
        ConnectionConfig.Builder config = config().readTimeout(Duration.ofMillis(300)).heartbeatInterval(Duration.ofMillis(100));
        Pair pair = connect(config, config);
        Thread.sleep(1200);
        assertTrue(pair.client.connection.isOpen());
        assertTrue(pair.server.connection.isOpen());
        assertTrue(pair.client.events.messages.isEmpty());
        assertTrue(pair.server.events.messages.isEmpty());
        pair.client.connection.send(request(1));
        assertEquals(request(1), pair.server.events.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void writeTimeoutWhenThePeerStopsReading() throws Exception {
        Socket[] sockets = socketPair();
        Socket raw = sockets[1];
        raw.setReceiveBufferSize(4096);
        raw.getOutputStream().write(Protocol.PREAMBLE);   // then never reads
        Recorder events = new Recorder();
        Connection connection = new Connection(sockets[0], config()
                .writeTimeout(Duration.ofMillis(300))
                .socketOption(StandardSocketOptions.SO_SNDBUF, 4096)
                .build(), events);
        track(connection);
        connection.start();
        connection.handshake().get(5, TimeUnit.SECONDS);
        HeartBeatRequest big = request("y".repeat(64 * 1024));
        int sent = 0;
        while (sent < 1000 && connection.send(big)) {
            sent++;
        }
        Disconnect disconnect = events.disconnects.poll(10, TimeUnit.SECONDS);
        assertNotNull(disconnect);
        assertEquals(DisconnectReason.WRITE_TIMEOUT, disconnect.reason);
        assertInstanceOf(SocketTimeoutException.class, disconnect.cause);
        assertEquals(sent, connection.messagesWritten() + events.failures.size(), "Every message written or failed");
        assertEquals(0, connection.pendingMessages());
    }

    // ------------------------------------------------------------------ closing

    @Test
    public void disconnectIsReportedOnceEvenWhenClosedTwice() throws Exception {
        Pair pair = connect(config(), config());
        pair.client.connection.close();
        pair.client.connection.close();
        assertEquals(DisconnectReason.CLOSED_LOCALLY, pair.client.connection.awaitClosed());
        assertEquals(DisconnectReason.CLOSED_BY_PEER, pair.server.connection.awaitClosed());
        Disconnect client = pair.client.events.disconnects.poll(5, TimeUnit.SECONDS);
        assertEquals(DisconnectReason.CLOSED_LOCALLY, client.reason);
        assertNull(client.cause);
        assertNull(pair.server.events.disconnects.poll(5, TimeUnit.SECONDS).cause);
        assertEquals(1, pair.client.events.disconnectedCalls.get());
        assertEquals(1, pair.server.events.disconnectedCalls.get());
        assertEquals(List.of("connected", "disconnected"), pair.client.events.calls);
        assertFalse(pair.client.connection.send(request(1)), "send after close");
        assertTrue(pair.client.events.failures.isEmpty());
    }

    @Test
    public void closeFlushesQueuedMessages() throws Exception {
        Pair pair = connect(config(), config());
        int total = 100_000;
        for (int i = 0; i < total; i++) {
            pair.client.connection.send(request(i));
        }
        pair.client.connection.close();
        assertEquals(DisconnectReason.CLOSED_LOCALLY, pair.client.connection.awaitClosed());
        assertEquals(DisconnectReason.CLOSED_BY_PEER, pair.server.connection.awaitClosed());
        assertEquals(total, pair.server.events.messages.size());
        assertEquals(total, pair.client.connection.messagesWritten());
        assertTrue(pair.client.events.failures.isEmpty());
    }

    @Test
    public void closeWithZeroTimeoutReportsEveryMessageAsWrittenOrFailed() throws Exception {
        Pair pair = connect(config().closeTimeout(Duration.ZERO), config());
        int total = 200_000;
        for (int i = 0; i < total; i++) {
            pair.client.connection.send(request(i));
        }
        pair.client.connection.close();
        assertEquals(DisconnectReason.CLOSED_LOCALLY, pair.client.connection.awaitClosed());
        assertEquals(total, pair.client.connection.messagesWritten() + pair.client.events.failures.size());
        assertEquals(0, pair.client.connection.pendingMessages());
        assertEquals(0, pair.client.connection.pendingBytes());
        assertEquals("disconnected", pair.client.events.calls.getLast(), "onDisconnected must come last");
    }

    @Test
    public void closeFromTheListenerDoesNotDeadlock() throws Exception {
        Pair pair = connect(config(), config());
        pair.server.events.onMessage = (connection, message) -> connection.close();
        pair.client.connection.send(request(1));
        assertEquals(DisconnectReason.CLOSED_LOCALLY, pair.server.connection.closedFuture().get(5, TimeUnit.SECONDS));
        assertEquals(DisconnectReason.CLOSED_BY_PEER, pair.client.connection.closedFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    public void closeGivesUpAfterTheCloseTimeoutWhenThePeerDoesNotClose() throws Exception {
        RawPeer peer = rawPeer(config().closeTimeout(Duration.ofMillis(300)));
        peer.out.write(Protocol.PREAMBLE);
        peer.connection.handshake().get(5, TimeUnit.SECONDS);
        long start = System.nanoTime();
        peer.connection.close();   // the raw peer never closes its side
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= 250 && elapsedMillis < 3000, "close() took " + elapsedMillis + " ms");
        assertEquals(DisconnectReason.CLOSED_LOCALLY, peer.connection.closedFuture().get(5, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ memory

    @Test
    public void idleConnectionsStaySmall() throws Exception {
        int pairs = 1000;
        ConnectionConfig config = config().idleShrinkDelay(Duration.ofMillis(100)).build();
        Recorder events = new Recorder();
        long before = usedHeapAfterGc();
        List<Connection> idle = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            Socket[] sockets = socketPair();
            Connection client = new Connection(sockets[0], config, events);
            Connection server = new Connection(sockets[1], config, events);
            idle.add(client);
            idle.add(server);
            client.start();
            server.start();
            client.send(request(i));   // so that buffers have been allocated once
        }
        for (Connection connection : idle) {
            connection.handshake().get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(1000);   // let every connection pass the idle shrink delay
        long after = usedHeapAfterGc();
        long perConnection = (after - before) / idle.size();
        System.out.printf("Heap per idle connection: %d bytes (%d connections)%n", perConnection, idle.size());
        assertEquals(pairs, events.messages.size());
        for (Connection connection : idle) {
            connection.close();
        }
        assertTrue(perConnection < 16 * 1024, "Heap per idle connection: " + perConnection + " bytes");
    }

    // ------------------------------------------------------------------ helpers

    private static ConnectionConfig.Builder config() {
        return ConnectionConfig.builder();
    }

    private static HeartBeatRequest request(long i) {
        return HeartBeatRequest.newBuilder().setRequestTimeMillis(i).build();
    }

    private static HeartBeatRequest request(String text) {
        return HeartBeatRequest.newBuilder().setRequestMessage(text).build();
    }

    private static byte[] header(int signature, int nameLength, int payloadLength) {
        return ByteBuffer.allocate(ProtobufSerializer.HEADER_LENGTH).putInt(signature).putInt(nameLength).putInt(payloadLength).array();
    }

    private static byte[] frame(byte[] name, byte[] payload) {
        return ByteBuffer.allocate(ProtobufSerializer.HEADER_LENGTH + name.length + payload.length)
                .putInt(ProtobufSerializer.SIGNATURE).putInt(name.length).putInt(payload.length).put(name).put(payload).array();
    }

    private Socket[] socketPair() throws IOException {
        Socket client = new Socket(LOOPBACK, serverSocket.getLocalPort());
        Socket server = serverSocket.accept();
        rawSockets.add(client);
        rawSockets.add(server);
        return new Socket[]{client, server};
    }

    private Connection track(Connection connection) {
        connections.add(connection);
        return connection;
    }

    private Pair connect(ConnectionConfig.Builder clientConfig, ConnectionConfig.Builder serverConfig) throws Exception {
        Socket[] sockets = socketPair();
        Side client = side(sockets[0], clientConfig);
        Side server = side(sockets[1], serverConfig);
        client.connection.start();
        server.connection.start();
        client.connection.handshake().get(5, TimeUnit.SECONDS);
        server.connection.handshake().get(5, TimeUnit.SECONDS);
        assertTrue(client.events.connected.await(5, TimeUnit.SECONDS));
        assertTrue(server.events.connected.await(5, TimeUnit.SECONDS));
        return new Pair(client, server);
    }

    private Side side(Socket socket, ConnectionConfig.Builder config) {
        Recorder events = new Recorder();
        Connection connection = track(new Connection(socket, config.build(), events));
        events.connection = connection;
        return new Side(connection, events);
    }

    /** A connection on the server side of a socket pair, with the client side driven by the test. */
    private RawPeer rawPeer(ConnectionConfig.Builder config) throws IOException {
        Socket[] sockets = socketPair();
        Recorder events = new Recorder();
        Connection connection = track(new Connection(sockets[1], config.build(), events));
        events.connection = connection;
        connection.start();
        return new RawPeer(sockets[0], sockets[0].getOutputStream(), connection, events);
    }

    private static void assertHandshakeFails(RawPeer peer, Class<? extends IOException> exception) throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> peer.connection.handshake().get(5, TimeUnit.SECONDS));
        assertInstanceOf(exception, e.getCause());
        assertEquals(DisconnectReason.PROTOCOL_MISMATCH, peer.connection.closedFuture().get(5, TimeUnit.SECONDS));
        assertTrue(peer.events.calls.isEmpty(), "Listener called after a failed handshake: " + peer.events.calls);
        assertClosedByPeer(peer.socket);
    }

    /** Reads (and skips the peer's preamble) until the end of stream. A reset also counts as closed. */
    private static void assertClosedByPeer(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        try {
            while (in.read(buffer) >= 0) {
                // skip
            }
        } catch (SocketException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("reset"), e.toString());
        }
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record Side(Connection connection, Recorder events) {
    }

    private record Pair(Side client, Side server) {
    }

    private record RawPeer(Socket socket, OutputStream out, Connection connection, Recorder events) {
    }

    private record Disconnect(DisconnectReason reason, Throwable cause) {
    }

    private record Failure(MessageLite message, SendException cause) {
    }

    private static final class Recorder implements ConnectionListener {
        volatile Connection connection;
        volatile BiConsumer<Connection, MessageLite> onMessage = (connection, message) -> { };
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicInteger connectedCalls = new AtomicInteger();
        final AtomicInteger disconnectedCalls = new AtomicInteger();
        final BlockingQueue<MessageLite> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Failure> failures = new LinkedBlockingQueue<>();
        final BlockingQueue<Disconnect> disconnects = new LinkedBlockingQueue<>();
        final List<String> calls = new CopyOnWriteArrayList<>();   // connected, failure, disconnected (not messages)

        @Override
        public void onConnected() {
            connectedCalls.incrementAndGet();
            calls.add("connected");
            connected.countDown();
        }

        @Override
        public void onMessage(MessageLite message) {
            messages.add(message);
            onMessage.accept(connection, message);
        }

        @Override
        public void onSendFailure(MessageLite message, SendException cause) {
            if (calls.isEmpty() || !calls.getLast().equals("failure")) {
                calls.add("failure");
            }
            failures.add(new Failure(message, cause));
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable cause) {
            disconnectedCalls.incrementAndGet();
            calls.add("disconnected");
            disconnects.add(new Disconnect(reason, cause));
        }
    }
}
