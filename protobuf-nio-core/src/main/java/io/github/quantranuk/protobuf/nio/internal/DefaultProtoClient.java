package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.ProtoClient;
import io.github.quantranuk.protobuf.nio.ProtoClientListener;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ProtoClient} on top of a {@link Connection}. The connection (and its unconnected socket) is created when
 * {@link #connect()} starts, so that messages sent while connecting are queued by the connection itself.
 */
public final class DefaultProtoClient implements ProtoClient {

    private static final int NEW = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;
    private static final int CLOSED = 3;

    private final long id = Connection.nextId();
    private final InetSocketAddress target;
    private final int connectTimeoutMillis;
    private final ConnectionConfig config;
    private final ProtoClientListener listener;
    private final AtomicInteger state = new AtomicInteger(NEW);
    private volatile Connection connection;
    private volatile Object attachment;

    public DefaultProtoClient(InetSocketAddress target, Duration connectTimeout, ConnectionConfig config, ProtoClientListener listener) {
        this.target = target;
        this.connectTimeoutMillis = connectTimeout.isZero() ? 0 : (int) Math.clamp(connectTimeout.toMillis(), 1, Integer.MAX_VALUE);
        this.config = config;
        this.listener = listener;
    }

    @Override
    public void connect() throws IOException {
        finishConnect(beginConnect());
    }

    /** Enters the connecting state on the calling thread, so that sends from now on are queued. */
    private Connection beginConnect() {
        if (!state.compareAndSet(NEW, CONNECTING)) {
            throw new IllegalStateException(state.get() == CLOSED && connection == null
                    ? "The client was closed before connecting" : "connect() was already called");
        }
        Connection c = new Connection(id, new Socket(), target.getHostString(), config, new Events());
        connection = c;
        return c;
    }

    private void finishConnect(Connection c) throws IOException {
        if (state.get() == CLOSED) {   // close() ran before it could see the connection
            c.close();   // records CLOSED_LOCALLY
            c.abort(null);
            throw new IOException("Closed while connecting");
        }
        try {
            Socket socket = c.socket();
            Connection.applySocketOptions(socket, config.socketOptions);   // before connecting, e.g. for SO_RCVBUF
            socket.connect(resolve(target), connectTimeoutMillis);
            c.start();
        } catch (IOException | RuntimeException e) {
            c.abort(e);   // after close() while connecting, the reason stays CLOSED_LOCALLY
            state.set(CLOSED);
            throw e;
        }
        try {
            c.handshake().get();
        } catch (ExecutionException e) {
            state.set(CLOSED);
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            c.close();
            state.set(CLOSED);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while connecting");
        }
        state.compareAndSet(CONNECTING, CONNECTED);
    }

    private static InetSocketAddress resolve(InetSocketAddress address) throws UnknownHostException {
        InetSocketAddress resolved = address.isUnresolved() ? new InetSocketAddress(address.getHostString(), address.getPort()) : address;
        if (resolved.isUnresolved()) {
            throw new UnknownHostException(address.getHostString());
        }
        return resolved;
    }

    @Override
    public CompletableFuture<Void> connectAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Connection c;
        try {
            c = beginConnect();
        } catch (IllegalStateException e) {
            future.completeExceptionally(e);
            return future;
        }
        Thread.ofVirtual().name("protobuf-nio-connect-" + id).start(() -> {
            try {
                finishConnect(c);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        Connection c = connection;
        InetSocketAddress remote = c == null ? null : c.remoteAddress();
        return remote != null ? remote : target;
    }

    @Override
    public InetSocketAddress localAddress() {
        Connection c = connection;
        return c == null ? null : c.localAddress();
    }

    @Override
    public boolean isOpen() {
        Connection c = connection;
        return c != null && c.isOpen();
    }

    @Override
    public SSLSession tlsSession() {
        Connection c = connection;
        return c != null ? c.tlsSession() : null;
    }

    @Override
    public boolean send(MessageLite message) {
        return connected().send(message);
    }

    @Override
    public int pendingMessages() {
        Connection c = connection;
        return c == null ? 0 : c.pendingMessages();
    }

    @Override
    public long pendingBytes() {
        Connection c = connection;
        return c == null ? 0 : c.pendingBytes();
    }

    @Override
    public void close() {
        if (state.compareAndSet(NEW, CLOSED)) {
            return;
        }
        state.set(CLOSED);
        Connection c = connection;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public DisconnectReason awaitClosed() throws InterruptedException {
        return connected().awaitClosed();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attachment() {
        return (T) attachment;
    }

    @Override
    public void attach(Object attachment) {
        this.attachment = attachment;
    }

    @Override
    public String toString() {
        return "ProtoClient#" + id + "[" + remoteAddress() + "]";
    }

    private Connection connected() {
        Connection c = connection;
        if (c == null) {
            throw new IllegalStateException("connect() hasn't been called");
        }
        return c;
    }

    private final class Events implements ConnectionListener {
        @Override
        public void onConnected() {
            listener.onConnected(DefaultProtoClient.this);
        }

        @Override
        public void onMessage(MessageLite message) {
            listener.onMessage(DefaultProtoClient.this, message);
        }

        @Override
        public void onRawMessage(Object handler, ByteBuffer payload) {
            ((ProtoClientListener.RawMessageHandler) handler).onMessage(DefaultProtoClient.this, payload);
        }

        @Override
        public void onSendFailure(MessageLite message, SendException cause) {
            listener.onSendFailure(DefaultProtoClient.this, message, cause);
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable cause) {
            listener.onDisconnected(DefaultProtoClient.this, reason, cause);
        }
    }
}
