package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatResponse;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clients send messages of mixed sizes for a few seconds while both sides close connections at random, and the
 * delivery contract is checked end to end through the public API: every message {@code send} accepted is either
 * received or reported to {@code onSendFailure}, and {@code onDisconnected} comes last, exactly once, on both sides.
 * Each client runs session after session (a client is single-use). The seed is printed; {@code -Dsoak.seed=...}
 * replays the random choices (not the timing).
 */
public class SoakTest {

    private static final int CLIENTS = 8;
    private static final long DURATION_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_PER_SESSION = 50_000;
    private static final String PAYLOAD = "p".repeat(256 * 1024);

    // Message id = session key * MAX_PER_SESSION + sequence number in the session
    private final Map<Long, BitSet> received = new ConcurrentHashMap<>();
    private final Map<ProtoConnection, Events> serverEvents = new ConcurrentHashMap<>();
    private final AtomicLong serverCloses = new AtomicLong();

    @Test
    public void everyAcceptedMessageIsReceivedOrReported() throws Exception {
        long seed = Long.getLong("soak.seed", System.nanoTime());
        Random random = new Random(seed);
        ProtoServer server = ProtoServer.builder(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .listener(new ServerEvents())
                .build();
        server.start();
        List<Session> sessions = new ArrayList<>();
        try {
            long deadline = System.nanoTime() + DURATION_NANOS;
            List<Thread> clients = new ArrayList<>();
            for (int k = 0; k < CLIENTS; k++) {
                int client = k;
                Random clientRandom = new Random(random.nextLong());
                clients.add(Thread.startVirtualThread(() -> {
                    for (int n = 0; System.nanoTime() < deadline; n++) {
                        Session session = new Session(client * 100_000L + n, server.localAddress().getPort(), clientRandom);
                        synchronized (sessions) {
                            sessions.add(session);
                        }
                        session.run(deadline);
                    }
                }));
            }
            Thread closer = Thread.startVirtualThread(() -> closeRandomConnections(server, new Random(seed + 1)));
            for (Thread client : clients) {
                client.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(client.isAlive(), "A client is still running (seed " + seed + ")");
            }
            closer.interrupt();
            closer.join();
        } finally {
            server.close();
        }

        long accepted = 0;
        long failed = 0;
        long receivedCount = 0;
        for (Session session : sessions) {
            String where = "Session " + session.key + " (seed " + seed + ")";
            assertNull(session.error, where + " failed: " + session.error);
            session.client.awaitClosed();
            assertEquals(1, session.events.disconnects.get(), where + ": onDisconnected calls");
            assertEquals(0, session.events.lateFailures.get(), where + ": onSendFailure after onDisconnected");
            BitSet got = received.getOrDefault(session.key, new BitSet());
            BitSet lost = (BitSet) session.accepted.clone();
            lost.andNot(got);
            synchronized (session.failed) {
                lost.andNot(session.failed);
                failed += session.failed.cardinality();
            }
            assertTrue(lost.isEmpty(), where + ": " + lost.cardinality() + " accepted messages neither received nor reported");
            accepted += session.accepted.cardinality();
            receivedCount += got.cardinality();
        }
        for (Events events : serverEvents.values()) {
            assertEquals(1, events.disconnects.get(), "Server-side onDisconnected calls (seed " + seed + ")");
            assertEquals(0, events.lateFailures.get(), "Server-side onSendFailure after onDisconnected (seed " + seed + ")");
        }
        assertTrue(server.connections().isEmpty());
        System.out.printf("SoakTest seed %d: %d sessions, %d server-side closes, %d accepted, %d received, %d reported failed%n",
                seed, sessions.size(), serverCloses.get(), accepted, receivedCount, failed);
        assertTrue(sessions.size() > CLIENTS, "Too few sessions to mean much");
    }

    private void closeRandomConnections(ProtoServer server, Random random) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(20 + random.nextInt(100));
                List<ProtoConnection> open = new ArrayList<>(server.connections());
                if (!open.isEmpty()) {
                    open.get(random.nextInt(open.size())).close();
                    serverCloses.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            // done
        }
    }

    /** Records what the server receives, echoes some of it, and checks its callbacks' order per connection. */
    private final class ServerEvents implements ProtoServerListener {
        @Override
        public void onConnected(ProtoConnection connection) {
            serverEvents.put(connection, new Events());
        }

        @Override
        public void onMessage(ProtoConnection connection, MessageLite message) {
            long id = ((HeartBeatRequest) message).getRequestTimeMillis();
            BitSet bits = received.computeIfAbsent(id / MAX_PER_SESSION, key -> new BitSet());
            synchronized (bits) {
                bits.set((int) (id % MAX_PER_SESSION));
            }
            if (id % 50 == 0) {   // some traffic the other way
                connection.send(HeartBeatResponse.newBuilder().setRequestTimeMillis(id).build());
            }
        }

        @Override
        public void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause) {
            serverEvents.get(connection).failure();
        }

        @Override
        public void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause) {
            serverEvents.get(connection).disconnected();
        }
    }

    /** Disconnect count, and send failures reported after the disconnect (which must never happen). */
    private static final class Events {
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger lateFailures = new AtomicInteger();

        void failure() {
            if (disconnects.get() > 0) {
                lateFailures.incrementAndGet();
            }
        }

        void disconnected() {
            disconnects.incrementAndGet();
        }
    }

    /** One client connection: sends messages of random sizes, and sometimes closes itself. */
    private static final class Session {
        final long key;
        final ProtoClient client;
        final Random random;
        final Events events = new Events();
        final BitSet accepted = new BitSet();   // the session's thread only, read after it ends
        final BitSet failed = new BitSet();
        volatile Throwable error;

        Session(long key, int port, Random random) {
            this.key = key;
            this.random = random;
            this.client = ProtoClient.builder("localhost", port)
                    .listener(new ProtoClientListener() {
                        @Override
                        public void onSendFailure(ProtoClient c, MessageLite message, SendException cause) {
                            events.failure();
                            if (cause.reason() != SendException.Reason.REJECTED) {
                                synchronized (failed) {
                                    failed.set((int) (((HeartBeatRequest) message).getRequestTimeMillis() % MAX_PER_SESSION));
                                }
                            }
                        }

                        @Override
                        public void onDisconnected(ProtoClient c, DisconnectReason reason, Throwable cause) {
                            events.disconnected();
                        }
                    })
                    .build();
        }

        void run(long deadline) {
            int closeAfter = random.nextInt(3) == 0 ? random.nextInt(MAX_PER_SESSION) : Integer.MAX_VALUE;
            try {
                client.connect();
                for (int i = 0; i < MAX_PER_SESSION && client.isOpen() && System.nanoTime() < deadline; i++) {
                    if (i == closeAfter) {
                        break;
                    }
                    HeartBeatRequest message = HeartBeatRequest.newBuilder()
                            .setRequestTimeMillis(key * MAX_PER_SESSION + i)
                            .setRequestMessage(PAYLOAD.substring(0, size()))
                            .build();
                    if (client.send(message)) {
                        accepted.set(i);
                    }
                    if (random.nextInt(2000) == 0) {
                        Thread.sleep(random.nextInt(20));
                    }
                }
            } catch (Throwable t) {
                error = t;
            } finally {
                client.close();
            }
        }

        /** Mostly small messages, some medium, a few large. */
        private int size() {
            int roll = random.nextInt(100);
            if (roll < 90) {
                return random.nextInt(64);
            }
            if (roll < 99) {
                return random.nextInt(4096);
            }
            return random.nextInt(PAYLOAD.length());
        }
    }
}
