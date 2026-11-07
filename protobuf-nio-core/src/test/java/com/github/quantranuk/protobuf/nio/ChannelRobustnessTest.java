package com.github.quantranuk.protobuf.nio;

import com.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import com.github.quantranuk.protobuf.nio.serializer.ProtobufSerializer;
import com.google.protobuf.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback tests for failure handling: bad input must disconnect the peer, and user handler exceptions
 * must not stop the channel or the server.
 */
public class ChannelRobustnessTest {

    private static final int MAX_MESSAGE_SIZE = 1024;

    private ProtoServerSocketChannel server;
    private int port;
    private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<SocketAddress> disconnected = new LinkedBlockingQueue<>();

    @BeforeEach
    public void startServer() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = ProtoChannelFactory.newServer(port).setMaxMessageSize(MAX_MESSAGE_SIZE).build();
        server.addMessageReceivedHandler((address, message) -> received.add(message));
        server.addDisconnectionHandler(disconnected::add);
        server.start();
    }

    @AfterEach
    public void stopServer() {
        server.stop();
    }

    @Test
    public void invalidSignatureDisconnectsClient() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(header(0x01020304, 10, 10));
            assertNotNull(disconnected.poll(5, TimeUnit.SECONDS));
            assertClosedByPeer(socket);
        }
    }

    @Test
    public void oversizedPayloadDisconnectsClient() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(header(ProtobufSerializer.SIGNATURE, 10, Integer.MAX_VALUE));
            assertNotNull(disconnected.poll(5, TimeUnit.SECONDS));
            assertClosedByPeer(socket);
        }
    }

    @Test
    public void negativeLengthDisconnectsClient() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(header(ProtobufSerializer.SIGNATURE, -5, 10));
            assertNotNull(disconnected.poll(5, TimeUnit.SECONDS));
            assertClosedByPeer(socket);
        }
    }

    @Test
    public void unknownClassNameDisconnectsClient() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            byte[] name = "com.example.DoesNotExist".getBytes(StandardCharsets.ISO_8859_1);
            OutputStream out = socket.getOutputStream();
            out.write(header(ProtobufSerializer.SIGNATURE, name.length, 0));
            out.write(name);
            assertNotNull(disconnected.poll(5, TimeUnit.SECONDS));
            assertClosedByPeer(socket);
        }
    }

    @Test
    public void nonProtobufClassIsRejectedWithoutInitializingIt() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            byte[] name = NotAMessage.class.getName().getBytes(StandardCharsets.ISO_8859_1);
            OutputStream out = socket.getOutputStream();
            out.write(header(ProtobufSerializer.SIGNATURE, name.length, 0));
            out.write(name);
            assertNotNull(disconnected.poll(5, TimeUnit.SECONDS));
        }
        assertEquals(0, NOT_A_MESSAGE_INITIALIZED.get());
    }

    @Test
    public void receivedHandlerExceptionDoesNotStopReading() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.addMessageReceivedHandler((address, message) -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
        });
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(ProtobufSerializer.serialize(request("first")));
            out.write(ProtobufSerializer.serialize(request("second")));
            assertEquals("first", ((HeartBeatRequest) received.poll(5, TimeUnit.SECONDS)).getRequestMessage());
            assertEquals("second", ((HeartBeatRequest) received.poll(5, TimeUnit.SECONDS)).getRequestMessage());
        }
    }

    @Test
    public void connectionHandlerExceptionDoesNotStopAccepting() throws Exception {
        server.addConnectionHandler(address -> {
            throw new RuntimeException("boom");
        });
        for (int i = 0; i < 3; i++) {
            try (Socket socket = new Socket("localhost", port)) {
                socket.getOutputStream().write(ProtobufSerializer.serialize(request("msg" + i)));
                assertEquals("msg" + i, ((HeartBeatRequest) received.poll(5, TimeUnit.SECONDS)).getRequestMessage());
            }
        }
    }

    @Test
    public void clientDisconnectNotifiesOnce() throws Exception {
        ProtoSocketChannel client = ProtoChannelFactory.newClient("localhost", port).build();
        AtomicInteger disconnects = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        server.addConnectionHandler(address -> connected.countDown());
        client.addDisconnectionHandler(address -> disconnects.incrementAndGet());
        client.connect();
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        client.disconnect();
        client.disconnect();
        assertEquals(1, disconnects.get());
    }

    private static void assertClosedByPeer(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        assertEquals(-1, in.read());
    }

    private static byte[] header(int signature, int classNameLength, int payloadLength) {
        return ByteBuffer.allocate(ProtobufSerializer.HEADER_LENGTH)
                .putInt(signature).putInt(classNameLength).putInt(payloadLength).array();
    }

    private static HeartBeatRequest request(String text) {
        return HeartBeatRequest.newBuilder().setRequestMessage(text).build();
    }

    // Kept outside NotAMessage: reading a field of NotAMessage would initialize it
    static final AtomicInteger NOT_A_MESSAGE_INITIALIZED = new AtomicInteger();

    public static final class NotAMessage {
        static {
            NOT_A_MESSAGE_INITIALIZED.incrementAndGet();
        }
    }
}
