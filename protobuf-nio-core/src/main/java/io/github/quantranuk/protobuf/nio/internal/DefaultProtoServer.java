package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.ProtoConnection;
import io.github.quantranuk.protobuf.nio.ProtoServer;
import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * {@link ProtoServer} with a blocking accept loop on its own thread. Every accepted socket becomes a
 * {@link DefaultProtoConnection}, tracked in {@code all} until it has closed; it's in {@code open} from
 * {@code onConnected} to {@code onDisconnected}.
 * <p>
 * At {@code maxConnections}, a new connection replaces the oldest one still in its handshake, so peers that connect
 * and stay silent can't hold every slot; without one, the new connection is closed.
 */
public final class DefaultProtoServer implements ProtoServer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultProtoServer.class);

    private static final int NEW = 0;
    private static final int STARTING = 1;
    private static final int RUNNING = 2;
    private static final int CLOSING = 3;
    private static final int CLOSED = 4;

    /** At most one warning per interval for each kind, so a connection flood doesn't become a log flood. */
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final InetSocketAddress bindAddress;
    private final int backlog;
    private final int maxConnections;
    private final ConnectionConfig config;
    private final Callbacks<ProtoConnection> callbacks;

    private final Map<Long, DefaultProtoConnection> all = new ConcurrentHashMap<>();
    private final Set<ProtoConnection> open = ConcurrentHashMap.newKeySet();
    private final Collection<ProtoConnection> openView = Collections.unmodifiableSet(open);
    private final AtomicInteger state = new AtomicInteger(NEW);
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean started;
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptor;
    // Acceptor thread only
    private final Throttle limitWarnings = new Throttle();
    private final Throttle acceptFailureWarnings = new Throttle();

    public DefaultProtoServer(InetSocketAddress bindAddress, int backlog, int maxConnections, ConnectionConfig config,
                              Callbacks<ProtoConnection> callbacks) {
        this.bindAddress = bindAddress;
        this.backlog = backlog;
        this.maxConnections = maxConnections;
        this.config = config;
        this.callbacks = callbacks;
    }

    @Override
    public void start() throws IOException {
        if (!state.compareAndSet(NEW, STARTING)) {
            throw new IllegalStateException(state.get() >= CLOSING ? "The server is closed" : "The server is already started");
        }
        ServerSocket socket = new ServerSocket();
        try {
            Set<SocketOption<?>> supported = socket.supportedOptions();
            for (Map.Entry<SocketOption<?>, Object> option : config.socketOptions.entrySet()) {
                if (supported.contains(option.getKey())) {
                    setOption(socket, option.getKey(), option.getValue());
                }
            }
            socket.bind(bindAddress, backlog);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            state.set(NEW);
            throw e;
        }
        serverSocket = socket;
        Thread thread = config.threadFactory != null
                ? config.threadFactory.newThread(this::acceptLoop)
                : Thread.ofVirtual().name("protobuf-nio-acceptor-" + socket.getLocalPort()).unstarted(this::acceptLoop);
        acceptor = thread;
        started = true;
        state.set(RUNNING);
        thread.start();
    }

    private void acceptLoop() {
        ServerSocket socket = serverSocket;
        while (state.get() == RUNNING) {
            Socket accepted;
            try {
                accepted = socket.accept();
            } catch (IOException e) {
                if (state.get() != RUNNING) {
                    return;
                }
                int suppressed = acceptFailureWarnings.tryAcquire();
                if (suppressed >= 0) {
                    LOG.warn("{}: accept failed ({} more failures not logged since the last warning)", this, suppressed, e);
                }
                pause();   // e.g. out of file descriptors: don't spin
                continue;
            }
            if (all.size() >= maxConnections && !evictOldestHandshaking()) {
                int suppressed = limitWarnings.tryAcquire();
                if (suppressed >= 0) {
                    LOG.warn("{}: maxConnections ({}) reached, closing the connection from {} ({} more not logged since the last warning)",
                            this, maxConnections, accepted.getRemoteSocketAddress(), suppressed);
                }
                closeQuietly(accepted);
                continue;
            }
            DefaultProtoConnection connection = new DefaultProtoConnection(this, accepted, config, callbacks);
            all.put(connection.id(), connection);
            connection.connection.closedFuture().whenComplete((reason, error) -> all.remove(connection.id()));
            try {
                connection.connection.start();
            } catch (IOException | RuntimeException e) {
                LOG.debug("{}: unable to start {}", this, connection, e);
            }
        }
    }

    /**
     * Closes the oldest connection still in its handshake, to make room for a new one. It never gets a callback.
     * Acceptor thread only.
     *
     * @return false if every connection has finished its handshake
     */
    private boolean evictOldestHandshaking() {
        while (true) {
            DefaultProtoConnection oldest = null;
            for (DefaultProtoConnection connection : all.values()) {
                if (connection.connection.isHandshaking() && (oldest == null || connection.id() < oldest.id())) {
                    oldest = connection;
                }
            }
            if (oldest == null) {
                return false;
            }
            if (oldest.connection.abortHandshake(new SocketException(
                    "Closed during the handshake to make room for a new connection (maxConnections " + maxConnections + ")"))) {
                all.remove(oldest.id());   // its slot is free now, though its threads take a moment to finish
                LOG.debug("{}: maxConnections ({}) reached, closed {} (still in its handshake) to accept a new connection",
                        this, maxConnections, oldest);
                return true;
            }
            // It finished its handshake meanwhile: look again
        }
    }

    void opened(DefaultProtoConnection connection) {
        open.add(connection);
    }

    void disconnected(DefaultProtoConnection connection) {
        open.remove(connection);
    }

    @Override
    public InetSocketAddress localAddress() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("start() hasn't been called");
        }
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    @Override
    public Collection<ProtoConnection> connections() {
        return openView;
    }

    @Override
    public int broadcast(MessageLite message) {
        return broadcastFiltered(message, null);
    }

    @Override
    public int broadcast(MessageLite message, Predicate<? super ProtoConnection> filter) {
        return broadcastFiltered(message, Objects.requireNonNull(filter, "filter"));
    }

    private int broadcastFiltered(MessageLite message, Predicate<? super ProtoConnection> filter) {
        Objects.requireNonNull(message, "message");
        EncodedFrame frame = null;   // encoded once, and only if some connection matches
        int queued = 0;
        for (ProtoConnection connection : open) {
            if (filter == null || filter.test(connection)) {
                if (frame == null) {
                    frame = EncodedFrame.of(message);
                }
                if (((DefaultProtoConnection) connection).send(frame)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    @Override
    public boolean isOpen() {
        return state.get() == RUNNING;
    }

    @Override
    public void close() {
        while (true) {
            int s = state.get();
            if (s == NEW) {
                if (state.compareAndSet(NEW, CLOSED)) {
                    closed.countDown();
                    return;
                }
            } else if (s == STARTING) {
                Thread.onSpinWait();
            } else if (s == RUNNING) {
                if (state.compareAndSet(RUNNING, CLOSING)) {
                    closeConnections();
                    state.set(CLOSED);
                    closed.countDown();
                    return;
                }
            } else {
                // Someone else is closing; waiting from a connection's own thread could deadlock with them
                if (findOwnConnection(new ArrayList<>(all.values())) == null) {
                    awaitUninterruptibly(closed);
                }
                return;
            }
        }
    }

    private void closeConnections() {
        closeQuietly(serverSocket);
        joinUninterruptibly(acceptor);   // after this, no connection gets added
        List<DefaultProtoConnection> connections = new ArrayList<>(all.values());
        DefaultProtoConnection own = findOwnConnection(connections);
        List<Thread> closers = new ArrayList<>(connections.size());
        for (DefaultProtoConnection connection : connections) {
            if (connection != own) {
                closers.add(Thread.ofVirtual().name("protobuf-nio-close-" + connection.id()).start(connection::closeForShutdown));
            }
        }
        if (own != null) {
            own.close();   // on its own reader or writer thread: doesn't wait for itself
        }
        for (Thread closer : closers) {
            joinUninterruptibly(closer);
        }
        for (DefaultProtoConnection connection : connections) {
            if (connection != own) {
                connection.connection.closedFuture().join();   // until onDisconnected has returned
            }
        }
    }

    private static DefaultProtoConnection findOwnConnection(List<DefaultProtoConnection> connections) {
        for (DefaultProtoConnection connection : connections) {
            if (connection.isOwnThread()) {
                return connection;
            }
        }
        return null;
    }

    @Override
    public void awaitClosed() throws InterruptedException {
        if (!started) {
            throw new IllegalStateException("start() hasn't been called");
        }
        closed.await();
    }

    @Override
    public String toString() {
        ServerSocket socket = serverSocket;
        return "ProtoServer[" + (socket != null ? socket.getLocalSocketAddress() : bindAddress) + "]";
    }

    @SuppressWarnings("unchecked")
    private static <T> void setOption(ServerSocket socket, SocketOption<T> option, Object value) throws IOException {
        socket.setOption(option, (T) value);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("Error while closing {}", closeable, e);
        }
    }

    /** Rate limit for one kind of warning. Not thread-safe: used by the acceptor thread only. */
    private static final class Throttle {
        private boolean logged;
        private long lastNanos;
        private int suppressed;

        /** @return the number of warnings suppressed since the last one, or -1 to suppress this one */
        int tryAcquire() {
            long now = System.nanoTime();
            if (logged && now - lastNanos < WARNING_INTERVAL_NANOS) {
                suppressed++;
                return -1;
            }
            logged = true;
            lastNanos = now;
            int count = suppressed;
            suppressed = 0;
            return count;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinUninterruptibly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
