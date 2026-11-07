package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytes allocated per message on each thread of the hot path, measured with the per-thread allocation counter.
 * Connections use platform threads here, because the counter doesn't cover virtual threads.
 * <p>
 * The limits leave headroom over what's measured, and catch a regression of the kind fixed in V4 (an encoder or a
 * buffer wrapper per message). They use a message with numeric fields only, because protobuf itself allocates for
 * string fields (on JDK 9+ it encodes them with {@code String.getBytes}); the figures for a message with a string are
 * printed too. What remains per message:
 * <ul>
 * <li>Sender: the send queue's node (24 bytes, {@code ConcurrentLinkedQueue}).</li>
 * <li>Writer: nothing; one encoder serves many batches.</li>
 * <li>Reader: protobuf's parsing (the decoder, the message, its strings); nothing with a raw handler.</li>
 * </ul>
 */
public class AllocationTest {

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 300_000;
    private static final int MEASURED = 1_000_000;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    public void closeEverything() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    public void bytesAllocatedPerMessage() throws Exception {
        HeartBeatRequest numeric = HeartBeatRequest.newBuilder().setRequestTimeMillis(123_456_789L).build();
        HeartBeatRequest withString = numeric.toBuilder().setRequestMessage("hello").build();
        Result parsed = measure(numeric, false);
        Result raw = measure(numeric, true);
        Result string = measure(withString, false);
        System.out.printf("Allocated per message: sender %.1f B, writer %.1f B, reader %.1f B (parsing), %.1f B (raw handler)."
                        + " With a string field: sender %.1f B, writer %.1f B, reader %.1f B%n",
                parsed.sender, parsed.writer, parsed.reader, raw.reader, string.sender, string.writer, string.reader);
        assertTrue(parsed.sender < 40, "Sender: " + parsed.sender + " B/msg");
        assertTrue(parsed.writer < 4, "Writer: " + parsed.writer + " B/msg");
        assertTrue(raw.sender < 40, "Sender: " + raw.sender + " B/msg");
        assertTrue(raw.writer < 4, "Writer: " + raw.writer + " B/msg");
        assertTrue(raw.reader < 4, "Reader with a raw handler: " + raw.reader + " B/msg");
        assertTrue(parsed.reader < 400, "Reader: " + parsed.reader + " B/msg");
    }

    private record Result(double sender, double writer, double reader) {
    }

    private Result measure(HeartBeatRequest message, boolean rawHandler) throws Exception {
        List<Thread> clientThreads = new ArrayList<>();
        List<Thread> serverThreads = new ArrayList<>();
        AtomicLong received = new AtomicLong();
        ConnectionListener counting = new ConnectionListener() {
            @Override
            public void onMessage(MessageLite message) {
                received.incrementAndGet();
            }

            @Override
            public void onRawMessage(Object handler, ByteBuffer payload) {
                received.incrementAndGet();
            }
        };
        ConnectionConfig.Builder serverConfig = ConnectionConfig.builder().threadFactory(platform(serverThreads));
        if (rawHandler) {
            serverConfig.rawHandler(HeartBeatRequest.class, new Object());
        }
        Socket[] sockets = socketPair();
        Connection client = track(new Connection(sockets[0], ConnectionConfig.builder().threadFactory(platform(clientThreads)).build(),
                new ConnectionListener() { }));
        Connection server = track(new Connection(sockets[1], serverConfig.build(), counting));
        client.start();
        server.start();
        client.handshake().get(5, TimeUnit.SECONDS);
        // Created in the constructor as reader, then writer
        Thread clientWriter = clientThreads.get(1);
        Thread serverReader = serverThreads.get(0);

        send(client, message, WARMUP, received, WARMUP);

        long sender = allocated(Thread.currentThread());
        long writer = allocated(clientWriter);
        long reader = allocated(serverReader);
        send(client, message, MEASURED, received, WARMUP + MEASURED);
        return new Result(
                (double) (allocated(Thread.currentThread()) - sender) / MEASURED,
                (double) (allocated(clientWriter) - writer) / MEASURED,
                (double) (allocated(serverReader) - reader) / MEASURED);
    }

    private static void send(Connection client, MessageLite message, int count, AtomicLong received, long expected) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            assertTrue(client.send(message));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received.get() < expected) {
            assertTrue(System.nanoTime() < deadline, "Only " + received.get() + " of " + expected + " received");
            Thread.sleep(1);
        }
    }

    private static long allocated(Thread thread) {
        return THREADS.getThreadAllocatedBytes(thread.threadId());
    }

    private static java.util.concurrent.ThreadFactory platform(List<Thread> created) {
        return task -> {
            Thread thread = Thread.ofPlatform().daemon().unstarted(task);
            created.add(thread);
            return thread;
        };
    }

    private Socket[] socketPair() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            Socket server = listener.accept();
            resources.add(client);
            resources.add(server);
            return new Socket[]{client, server};
        }
    }

    private Connection track(Connection connection) {
        resources.addFirst(connection::close);   // before the sockets
        return connection;
    }
}
