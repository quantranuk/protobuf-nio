package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.ProtoConnection;
import io.github.quantranuk.protobuf.nio.ProtoServer;
import io.github.quantranuk.protobuf.nio.ProtoServerListener;
import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * {@link ProtoServer} with a blocking accept loop on its own thread. Every accepted socket becomes a
 * {@link DefaultProtoConnection}, tracked in {@code all} until it has closed; it's in {@code open} from
 * {@code onConnected} to {@code onDisconnected}.
 */
public final class DefaultProtoServer implements ProtoServer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultProtoServer.class);

    private static final int NEW = 0;
    private static final int STARTING = 1;
    private static final int RUNNING = 2;
    private static final int CLOSING = 3;
    private static final int CLOSED = 4;

    private final InetSocketAddress bindAddress;
    private final int backlog;
    private final int maxConnections;
    private final ConnectionConfig config;
    private final ProtoServerListener listener;

    private final Map<Long, DefaultProtoConnection> all = new ConcurrentHashMap<>();
    private final Set<ProtoConnection> open = ConcurrentHashMap.newKeySet();
    private final Collection<ProtoConnection> openView = Collections.unmodifiableSet(open);
    private final AtomicInteger state = new AtomicInteger(NEW);
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean started;
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptor;

    public DefaultProtoServer(InetSocketAddress bindAddress, int backlog, int maxConnections, ConnectionConfig config,
                              ProtoServerListener listener) {
        this.bindAddress = bindAddress;
        this.backlog = backlog;
        this.maxConnections = maxConnections;
        this.config = config;
        this.listener = listener;
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
                LOG.warn("{}: accept failed", this, e);   // e.g. out of file descriptors: don't spin
                pause();
                continue;
            }
            if (all.size() >= maxConnections) {
                LOG.warn("{}: maxConnections ({}) reached, closing the connection from {}", this, maxConnections, accepted.getRemoteSocketAddress());
                closeQuietly(accepted);
                continue;
            }
            DefaultProtoConnection connection = new DefaultProtoConnection(this, accepted, config, listener);
            all.put(connection.id(), connection);
            connection.connection.closedFuture().whenComplete((reason, error) -> all.remove(connection.id()));
            try {
                connection.connection.start();
            } catch (IOException | RuntimeException e) {
                LOG.debug("{}: unable to start {}", this, connection, e);
            }
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
                closers.add(Thread.ofVirtual().name("protobuf-nio-close-" + connection.id()).start(connection::close));
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
