package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.OverflowPolicy;
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
import java.util.concurrent.CompletableFuture;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    public void allowlistAcceptsListedTypesAndDisconnectsOnOthers() throws Exception {
        RawPeer peer = rawPeer(config().allowedMessageTypes(HeartBeatRequest.class));
        peer.out.write(Protocol.PREAMBLE);
        peer.out.write(ProtobufSerializer.serialize(request(1)));
        peer.out.write(ProtobufSerializer.serialize(HeartBeatResponse.newBuilder().setRequestTimeMillis(2).build()));
        assertEquals(request(1), peer.events.messages.poll(5, TimeUnit.SECONDS));
        Disconnect disconnect = peer.events.disconnects.poll(5, TimeUnit.SECONDS);
        assertNotNull(disconnect);
        assertEquals(DisconnectReason.PROTOCOL_ERROR, disconnect.reason);
        assertInstanceOf(ProtocolException.class, disconnect.cause);
        assertEquals("Message type not allowed: " + HeartBeatResponse.class.getName(), disconnect.cause.getMessage());
        assertTrue(peer.events.messages.isEmpty());
        assertClosedByPeer(peer.socket);
    }

    @Test
    public void allowlistNeverLoadsADisallowedClass() throws Exception {
        List<String> requested = new CopyOnWriteArrayList<>();
        ClassLoader recording = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
                return super.loadClass(name, resolve);
            }
        };
        RawPeer peer = rawPeer(config().classLoader(recording).allowedMessageTypes(HeartBeatRequest.class));
        peer.out.write(Protocol.PREAMBLE);
        peer.out.write(frame(String.class.getName().getBytes(StandardCharsets.ISO_8859_1), new byte[0]));
        Disconnect disconnect = peer.events.disconnects.poll(5, TimeUnit.SECONDS);
        assertNotNull(disconnect);
        assertEquals(DisconnectReason.PROTOCOL_ERROR, disconnect.reason);
        assertEquals("Message type not allowed: java.lang.String", disconnect.cause.getMessage());
        assertTrue(requested.isEmpty(), "Class loader asked for " + requested);
    }

    @Test
    public void allowlistRejectsInvalidTypes() {
        assertThrows(IllegalArgumentException.class, () -> config().allowedMessageTypes());
        assertThrows(IllegalArgumentException.class, () -> config().allowedMessageTypes(MessageLite.class));
        assertThrows(NullPointerException.class, () -> config().allowedMessageTypes(HeartBeatRequest.class, null));
    }

    @Test
    public void rawHandlerGetsThePayloadInsteadOfOnMessage() throws Exception {
        Object handler = new Object();
        // The raw type is accepted although the allowlist doesn't list it
        RawPeer peer = rawPeer(config().allowedMessageTypes(HeartBeatResponse.class).rawHandler(HeartBeatRequest.class, handler));
        peer.out.write(Protocol.PREAMBLE);
        HeartBeatResponse response = HeartBeatResponse.newBuilder().setRequestTimeMillis(7).build();
        for (int i = 0; i < 100; i++) {
            peer.out.write(ProtobufSerializer.serialize(request("raw " + i)));
            peer.out.write(ProtobufSerializer.serialize(response));
        }
        peer.out.write(ProtobufSerializer.serialize(request("")));   // empty payload
        for (int i = 0; i < 100; i++) {
            Raw raw = peer.events.raws.poll(5, TimeUnit.SECONDS);
            assertSame(handler, raw.handler);
            assertEquals(request("raw " + i), HeartBeatRequest.parseFrom(raw.payload));
            assertEquals(response, peer.events.messages.poll(5, TimeUnit.SECONDS));
        }
        assertEquals(0, peer.events.raws.poll(5, TimeUnit.SECONDS).payload.length);
        assertTrue(peer.events.messages.isEmpty());
        // One view per read buffer, and the buffer is replaced only a few times as it grows
        assertTrue(peer.events.views.size() <= 10, "Views not reused: " + peer.events.views.size());
        assertTrue(peer.connection.isOpen());
    }

    @Test
    public void rawHandlerRegistrationIsValidated() {
        ConnectionConfig.Builder builder = config().rawHandler(HeartBeatRequest.class, new Object());
        assertThrows(IllegalArgumentException.class, () -> builder.rawHandler(HeartBeatRequest.class, new Object()));
        assertThrows(IllegalArgumentException.class, () -> builder.rawHandler(MessageLite.class, new Object()));
        assertThrows(NullPointerException.class, () -> builder.rawHandler(HeartBeatResponse.class, null));
    }

    @ParameterizedTest
    @MethodSource("brokenMessages")
    public void aMessageThatFailsToEncodeFailsAloneAndTheBatchCarriesOn(String description, MessageLite broken) throws Exception {
        Pair pair = connect(config(), config());
        Connection client = pair.client.connection;
        for (int round = 0; round < 50; round++) {   // most rounds land in one batch
            assertTrue(client.send(request(2L * round)));
            assertTrue(client.send(broken));
            assertTrue(client.send(request(2L * round + 1)));
        }
        for (long i = 0; i < 100; i++) {
            assertEquals(request(i), pair.server.events.messages.poll(5, TimeUnit.SECONDS));
        }
        for (int round = 0; round < 50; round++) {
            Failure failure = pair.client.events.failures.poll(5, TimeUnit.SECONDS);
            assertSame(broken, failure.message);
            assertEquals(SendException.Reason.WRITE_FAILED, failure.cause.reason());
        }
        assertTrue(client.isOpen());
        // The writer updates the counts just after its write, so the peer may already have the messages
        awaitTrue(() -> client.pendingMessages() == 0 && client.pendingBytes() == 0);
    }

    static Stream<Arguments> brokenMessages() {
        return Stream.of(
                Arguments.of("throws while encoding", brokenMessage(20, out -> {
                    out.writeRawByte(1);
                    throw new IllegalStateException("boom (expected by the test)");
                })),
                Arguments.of("encodes fewer bytes than its size", brokenMessage(20, out -> out.writeRawBytes(new byte[3]))),
                Arguments.of("encodes more bytes than its size", brokenMessage(3, out -> out.writeRawBytes(new byte[20]))));
    }

    private interface Encoding {
        void writeTo(com.google.protobuf.CodedOutputStream out) throws IOException;
    }

    /** A MessageLite that reports {@code size} but encodes as {@code encoding} says. */
    private static MessageLite brokenMessage(int size, Encoding encoding) {
        return (MessageLite) java.lang.reflect.Proxy.newProxyInstance(ConnectionTest.class.getClassLoader(), new Class<?>[]{MessageLite.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSerializedSize" -> size;
                    case "writeTo" -> {
                        encoding.writeTo((com.google.protobuf.CodedOutputStream) args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "BrokenMessage";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    // ------------------------------------------------------------------ backpressure

    // The server side's reader blocks in onMessage until the gate opens, so the client's socket buffers fill up and its
    // queue grows. Messages are 16 KB, so the writer takes up to 4 per batch and the rest stay queued.

    private static final int QUEUE_LIMIT = 8;

    @Test
    public void rejectReportsOnTheCallingThreadAndKeepsTheRest() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().overflowPolicy(OverflowPolicy.REJECT).maxPendingMessages(QUEUE_LIMIT), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            assertEquals(QUEUE_LIMIT, client.pendingMessages());
            HeartBeatRequest rejected = numbered(-1);
            assertFalse(client.send(rejected));
            Failure failure = pair.client.events.failures.poll(5, TimeUnit.SECONDS);
            assertSame(rejected, failure.message);
            assertEquals(SendException.Reason.REJECTED, failure.cause.reason());
            assertSame(Thread.currentThread(), failure.thread);
            gate.countDown();
            assertReceivedInOrder(pair.server.events, sent, List.of());
            assertTrue(pair.client.events.failures.isEmpty());
            // The writer updates the counts just after its write, so the peer may already have the messages
            awaitTrue(() -> client.pendingMessages() == 0 && client.pendingBytes() == 0);
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void pendingBytesAreLimited() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        long limit = 5L * ProtobufSerializer.getSerializedSize(numbered(Long.MAX_VALUE)) + 1000;
        Pair pair = stalledPair(config().overflowPolicy(OverflowPolicy.REJECT).maxPendingBytes(limit), gate);
        try {
            Connection client = pair.client.connection;
            int sent = 0;
            while (client.send(numbered(sent))) {
                sent++;
                assertTrue(client.pendingBytes() <= limit);
                assertTrue(sent < 100_000, "Never full");
            }
            assertTrue(client.pendingMessages() <= 5);
            assertEquals(SendException.Reason.REJECTED, pair.client.events.failures.poll(5, TimeUnit.SECONDS).cause.reason());
            gate.countDown();
            assertReceivedInOrder(pair.server.events, sent, List.of());
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void aMessageLargerThanMaxPendingBytesIsSentWhenNothingElseIsPending() throws Exception {
        Pair pair = connect(config().overflowPolicy(OverflowPolicy.REJECT).maxPendingBytes(1024), config());
        assertTrue(pair.client.connection.send(request("z".repeat(10_000))));
        assertEquals(10_000, ((HeartBeatRequest) pair.server.events.messages.poll(5, TimeUnit.SECONDS)).getRequestMessage().length());
    }

    @Test
    public void blockWaitsForRoom() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().maxPendingMessages(QUEUE_LIMIT).sendTimeout(Duration.ofSeconds(30)), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> client.send(numbered(sent)), Thread::startVirtualThread);
            Thread.sleep(200);
            assertFalse(blocked.isDone(), "send didn't block");
            gate.countDown();
            assertTrue(blocked.get(5, TimeUnit.SECONDS));
            assertReceivedInOrder(pair.server.events, sent + 1, List.of());
            assertTrue(pair.client.events.failures.isEmpty());
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void blockGivesUpAfterTheSendTimeout() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().maxPendingMessages(QUEUE_LIMIT).sendTimeout(Duration.ofMillis(200)), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            long start = System.nanoTime();
            HeartBeatRequest rejected = numbered(-1);
            assertFalse(client.send(rejected));
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(190), "Didn't wait for the send timeout");
            Failure failure = pair.client.events.failures.poll(5, TimeUnit.SECONDS);
            assertSame(rejected, failure.message);
            assertEquals(SendException.Reason.REJECTED, failure.cause.reason());
            assertSame(Thread.currentThread(), failure.thread);

            // An interrupted sender doesn't wait, keeps its interrupt flag, and the message is still reported
            Thread.currentThread().interrupt();
            start = System.nanoTime();
            assertFalse(client.send(rejected));
            assertTrue(Thread.interrupted(), "Interrupt flag lost");
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(150), "Waited although interrupted");
            assertEquals(SendException.Reason.REJECTED, pair.client.events.failures.poll(5, TimeUnit.SECONDS).cause.reason());

            // Encoded frames (broadcast) never wait
            start = System.nanoTime();
            assertFalse(client.send(EncodedFrame.of(rejected)));
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(150), "A broadcast frame waited");
            assertEquals(SendException.Reason.REJECTED, pair.client.events.failures.poll(5, TimeUnit.SECONDS).cause.reason());

            gate.countDown();
            assertReceivedInOrder(pair.server.events, sent, List.of());
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void closingWakesBlockedSendersWithoutReports() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().maxPendingMessages(QUEUE_LIMIT).sendTimeout(Duration.ofSeconds(30)), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> client.send(numbered(sent)), Thread::startVirtualThread);
            Thread.sleep(200);
            assertFalse(blocked.isDone(), "send didn't block");
            Thread closer = Thread.startVirtualThread(client::close);
            assertFalse(blocked.get(5, TimeUnit.SECONDS));
            gate.countDown();
            closer.join(5000);
            assertEquals(DisconnectReason.CLOSED_LOCALLY, client.awaitClosed());
            assertReceivedInOrder(pair.server.events, sent, List.of());   // close() flushed the queue
            assertTrue(pair.client.events.failures.isEmpty(), "Reported: " + pair.client.events.failures);
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void dropOldestDropsQueuedMessagesNotTheOnesBeingWritten() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().overflowPolicy(OverflowPolicy.DROP_OLDEST).maxPendingMessages(QUEUE_LIMIT), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            int queued = client.queuedMessages();
            for (int i = 0; i < queued + 2; i++) {   // more than are queued: some of the new ones get dropped too
                assertTrue(client.send(numbered(sent++)));
                assertTrue(client.pendingMessages() <= QUEUE_LIMIT);
            }
            List<Long> dropped = new ArrayList<>();
            for (Failure failure : pair.client.events.failures) {
                assertEquals(SendException.Reason.DROPPED, failure.cause.reason());
                assertSame(Thread.currentThread(), failure.thread);
                dropped.add(((HeartBeatRequest) failure.message).getRequestTimeMillis());
            }
            // Exactly queued + 2 if the writer stays stalled; fewer if it manages to write a little more meanwhile
            assertTrue(!dropped.isEmpty() && dropped.size() <= queued + 2, "Dropped: " + dropped);
            for (int i = 1; i < dropped.size(); i++) {
                assertEquals(dropped.get(i - 1) + 1, dropped.get(i), "Not dropped oldest first: " + dropped);
            }
            gate.countDown();
            // Everything not dropped arrives exactly once and in order: a message being written is never also dropped
            assertReceivedInOrder(pair.server.events, sent, dropped);
            assertNull(pair.server.events.messages.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void dropOldestRejectsWhenEverythingPendingIsBeingWritten() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        // Two 600 KB messages don't fit in 1 MB; each is written on its own, and the first blocks in the write
        Pair pair = stalledPair(config().overflowPolicy(OverflowPolicy.DROP_OLDEST).maxPendingBytes(1024 * 1024), gate);
        try {
            Connection client = pair.client.connection;
            // A small message first, so the server's reader is held before the big one (it reads a whole frame
            // before calling onMessage)
            assertTrue(client.send(numbered(0)));
            assertNotNull(pair.server.events.messages.poll(5, TimeUnit.SECONDS));
            HeartBeatRequest big = request("b".repeat(600 * 1024));
            assertTrue(client.send(big));
            awaitTrue(() -> client.queuedMessages() == 0 && stalled(client));
            assertFalse(client.send(big));
            Failure failure = pair.client.events.failures.poll(5, TimeUnit.SECONDS);
            assertEquals(SendException.Reason.REJECTED, failure.cause.reason());
            assertTrue(pair.client.events.failures.isEmpty());
            gate.countDown();
            assertEquals(big, pair.server.events.messages.poll(10, TimeUnit.SECONDS));
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void disconnectRejectsThenClosesAtOnce() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Pair pair = stalledPair(config().overflowPolicy(OverflowPolicy.DISCONNECT).maxPendingMessages(QUEUE_LIMIT), gate);
        try {
            Connection client = pair.client.connection;
            int sent = fillQueue(client);
            HeartBeatRequest rejected = numbered(-1);
            assertFalse(client.send(rejected));
            Failure first = pair.client.events.failures.poll(5, TimeUnit.SECONDS);
            assertSame(rejected, first.message);
            assertEquals(SendException.Reason.REJECTED, first.cause.reason());
            assertSame(Thread.currentThread(), first.thread);
            assertEquals(DisconnectReason.SEND_QUEUE_OVERFLOW, client.awaitClosed());
            assertFalse(client.send(numbered(0)));
            // Not flushed: whatever wasn't written failed
            assertEquals(sent, client.messagesWritten() + pair.client.events.failures.size(), "Every message written or failed");
            for (Failure failure : pair.client.events.failures) {
                assertTrue(failure.cause.reason() == SendException.Reason.CLOSED || failure.cause.reason() == SendException.Reason.WRITE_FAILED,
                        failure.cause.toString());
            }
            assertEquals("disconnected", pair.client.events.calls.getLast());
            assertEquals(0, pair.client.events.lateFailures.get());
            assertEquals(0, client.pendingMessages());
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void rejectionsDuringTheHandshakeAreNotReported() throws Exception {
        RawPeer peer = rawPeer(config().overflowPolicy(OverflowPolicy.REJECT).maxPendingMessages(2));   // no preamble yet
        peer.socket.setReceiveBufferSize(4096);
        HeartBeatRequest big = request("h".repeat(64 * 1024));
        int sent = 0;
        while (peer.connection.send(big)) {
            sent++;
            assertTrue(sent < 1000, "Never full");
        }
        assertTrue(peer.events.calls.isEmpty(), "Listener called during the handshake: " + peer.events.calls);
        assertTrue(peer.events.failures.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("overflowPolicies")
    public void sendersRacingWithCloseLoseNothing(OverflowPolicy policy) throws Exception {
        Pair pair = connect(config().overflowPolicy(policy).maxPendingMessages(64).sendTimeout(Duration.ofMillis(50)), config());
        Connection client = pair.client.connection;
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> senders = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            senders.add(Thread.startVirtualThread(() -> {
                for (int i = 0; i < 1_000_000; i++) {
                    if (client.send(request(i))) {
                        accepted.incrementAndGet();
                    } else if (!client.isOpen()) {
                        return;
                    }
                }
            }));
        }
        Thread.sleep(100);
        client.close();
        for (Thread sender : senders) {
            sender.join(10_000);
            assertFalse(sender.isAlive());
        }
        DisconnectReason reason = client.awaitClosed();
        pair.server.connection.awaitClosed();
        int lost = 0;
        for (Failure failure : pair.client.events.failures) {
            if (failure.cause.reason() != SendException.Reason.REJECTED) {
                lost++;   // DROPPED, or CLOSED/WRITE_FAILED after a DISCONNECT
            }
        }
        assertEquals(accepted.get(), pair.server.events.messages.size() + lost, "Accepted messages unaccounted for (" + reason + ")");
        assertEquals(0, client.pendingMessages());
        assertEquals(0, client.pendingBytes());
        assertEquals("disconnected", pair.client.events.calls.getLast());
        assertEquals(0, pair.client.events.lateFailures.get());
    }

    private static Stream<OverflowPolicy> overflowPolicies() {
        return Stream.of(OverflowPolicy.values());
    }

    /** A connected pair whose server side stops reading at the first message until the gate opens. */
    private Pair stalledPair(ConnectionConfig.Builder clientConfig, CountDownLatch gate) throws Exception {
        // Not a tiny SO_RCVBUF on the server: on loopback, a receive window below the MSS slows the transfer to a crawl
        Pair pair = connect(clientConfig.socketOption(StandardSocketOptions.SO_SNDBUF, 4096), config());
        pair.server.events.onMessage = (connection, message) -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        return pair;
    }

    /** Sends numbered messages until the queue stays full. @return how many were sent */
    private static int fillQueue(Connection connection) throws InterruptedException {
        int sent = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            while (connection.pendingMessages() < QUEUE_LIMIT) {
                assertTrue(connection.send(numbered(sent)));   // single sender: there's room, so it never waits
                sent++;
            }
            if (stalled(connection)) {
                return sent;
            }
            assertTrue(System.nanoTime() < deadline, "The queue never filled");
            Thread.sleep(10);
        }
    }

    /**
     * @return true if the writer has been blocked in a socket write for over 100 ms. With the peer's reader held, its
     *         receive window doesn't grow, so the write stays blocked.
     */
    private static boolean stalled(Connection connection) {
        return connection.writeBlockedNanos() > TimeUnit.MILLISECONDS.toNanos(100);
    }

    private static HeartBeatRequest numbered(long i) {
        return HeartBeatRequest.newBuilder().setRequestTimeMillis(i).setRequestMessage("n".repeat(16 * 1024)).build();
    }

    private static void assertReceivedInOrder(Recorder receiver, int sent, List<Long> missing) throws InterruptedException {
        for (long i = 0; i < sent; i++) {
            if (missing.contains(i)) {
                continue;
            }
            MessageLite message = receiver.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "Message " + i + " not received");
            assertEquals(i, ((HeartBeatRequest) message).getRequestTimeMillis());
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition not met within 5 s");
            Thread.sleep(5);
        }
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

    private record Failure(MessageLite message, SendException cause, Thread thread) {
    }

    private record Raw(Object handler, byte[] payload) {
    }

    private static final class Recorder implements ConnectionListener {
        volatile Connection connection;
        volatile BiConsumer<Connection, MessageLite> onMessage = (connection, message) -> { };
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicInteger connectedCalls = new AtomicInteger();
        final AtomicInteger disconnectedCalls = new AtomicInteger();
        final AtomicInteger lateFailures = new AtomicInteger();   // onSendFailure after onDisconnected
        final BlockingQueue<MessageLite> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Failure> failures = new LinkedBlockingQueue<>();
        final BlockingQueue<Raw> raws = new LinkedBlockingQueue<>();
        final java.util.Set<ByteBuffer> views = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
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
        public void onRawMessage(Object handler, ByteBuffer payload) {
            views.add(payload);
            byte[] copy = new byte[payload.remaining()];
            payload.get(copy);
            raws.add(new Raw(handler, copy));
        }

        @Override
        public void onSendFailure(MessageLite message, SendException cause) {
            if (calls.isEmpty() || !calls.getLast().equals("failure")) {
                calls.add("failure");
            }
            if (disconnectedCalls.get() > 0) {
                lateFailures.incrementAndGet();
            }
            failures.add(new Failure(message, cause, Thread.currentThread()));
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable cause) {
            disconnectedCalls.incrementAndGet();
            calls.add("disconnected");
            disconnects.add(new Disconnect(reason, cause));
        }
    }
}
