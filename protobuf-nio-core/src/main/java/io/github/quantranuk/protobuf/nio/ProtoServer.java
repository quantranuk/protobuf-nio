package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.ConnectionConfig;
import io.github.quantranuk.protobuf.nio.internal.DefaultProtoServer;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;

/**
 * Accepts protobuf-nio connections. Each accepted connection gets its own reader and writer virtual threads, so
 * listener callbacks for different connections run concurrently (see {@link ProtoServerListener}); one more virtual
 * thread accepts connections.
 */
public interface ProtoServer extends AutoCloseable {

    /** @param port the port to listen on, on every interface; 0 = any free port (see {@link #localAddress()}) */
    static Builder builder(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        return new Builder(new InetSocketAddress(port));
    }

    static Builder builder(InetSocketAddress bindAddress) {
        return new Builder(Objects.requireNonNull(bindAddress, "bindAddress"));
    }

    /**
     * Binds and starts accepting connections. Returns once bound.
     *
     * @throws IOException           if binding fails (the server can then be started again)
     * @throws IllegalStateException if already started or closed
     */
    void start() throws IOException;

    /** Bound address (useful with port 0). @throws IllegalStateException before {@code start()} */
    InetSocketAddress localAddress();

    /** Weakly consistent, unmodifiable view of the open connections. */
    Collection<ProtoConnection> connections();

    /**
     * Encodes the message once and queues it on every open connection. Never blocks: under
     * {@link OverflowPolicy#BLOCK}, a connection whose queue is full rejects the message as under
     * {@link OverflowPolicy#REJECT}; {@code DROP_OLDEST} and {@code DISCONNECT} apply as usual. Rejections and drops are
     * reported to {@code onSendFailure} on the calling thread.
     * @return the number of connections it was queued on
     */
    int broadcast(MessageLite message);

    /** Same, for the open connections that match the filter (for example subscribers to a topic). */
    int broadcast(MessageLite message, Predicate<? super ProtoConnection> filter);

    /** @return true after {@code start()}, until {@code close()} */
    boolean isOpen();

    /**
     * Stops accepting, closes every connection as {@link ProtoConnection#close()} does (in parallel), and waits until
     * each has reported {@code onDisconnected}. Called from a connection's own listener, it doesn't wait for that
     * connection. Idempotent.
     */
    @Override
    void close();

    /**
     * Blocks until {@code close()} has finished (for example at the end of {@code main()}).
     * @throws IllegalStateException if {@code start()} hasn't been called
     */
    void awaitClosed() throws InterruptedException;

    /** Reusable. Invalid values throw {@link IllegalArgumentException} at once. */
    final class Builder {
        private final InetSocketAddress bindAddress;
        private final ConnectionConfig.Builder config = ConnectionConfig.builder().socketOption(StandardSocketOptions.SO_REUSEADDR, true);
        private ProtoServerListener listener = new ProtoServerListener() { };
        private int backlog = 1024;
        private int maxConnections = Integer.MAX_VALUE;

        private Builder(InetSocketAddress bindAddress) {
            this.bindAddress = bindAddress;
        }

        /** Default: a no-op listener. */
        public Builder listener(ProtoServerListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Pending connections the operating system may queue. Default 1024. */
        public Builder backlog(int backlog) {
            if (backlog < 1) {
                throw new IllegalArgumentException("backlog must be positive: " + backlog);
            }
            this.backlog = backlog;
            return this;
        }

        /** Connections beyond this are closed as soon as they're accepted. Default: unlimited. */
        public Builder maxConnections(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxConnections must be positive: " + max);
            }
            maxConnections = max;
            return this;
        }

        /** Protocol preamble (and TLS handshake, when configured). Default 10 s. */
        public Builder handshakeTimeout(Duration timeout) {
            config.handshakeTimeout(timeout);
            return this;
        }

        /** Close a connection if nothing is received for this long; any frame, heartbeats included, resets it. Default: none. */
        public Builder readTimeout(Duration timeout) {
            config.readTimeout(timeout);
            return this;
        }

        /** Send a heartbeat frame when nothing has been sent for this long. Default: none. */
        public Builder heartbeatInterval(Duration interval) {
            config.heartbeatInterval(interval);
            return this;
        }

        /** Close a connection if a socket write blocks for longer than this. Default 10 s. */
        public Builder writeTimeout(Duration timeout) {
            config.writeTimeout(timeout);
            return this;
        }

        /** How long closing a connection may spend writing queued messages. Default 5 s; {@link Duration#ZERO} = at once. */
        public Builder closeTimeout(Duration timeout) {
            config.closeTimeout(timeout);
            return this;
        }

        /** Largest socket read per connection. Default 64 KB. */
        public Builder readBufferSize(int bytes) {
            config.readBufferSize(bytes);
            return this;
        }

        /** Largest batch written in one go per connection. Default 64 KB. */
        public Builder writeBufferSize(int bytes) {
            config.writeBufferSize(bytes);
            return this;
        }

        /** Largest protobuf payload accepted from a client. Default 64 MiB. */
        public Builder maxMessageSize(int bytes) {
            config.maxMessageSize(bytes);
            return this;
        }

        /**
         * Most encoded bytes queued and not yet written, per connection. A single larger message is queued only when
         * nothing else is pending. Default 64 MiB.
         */
        public Builder maxPendingBytes(long bytes) {
            config.maxPendingBytes(bytes);
            return this;
        }

        /** Most messages queued and not yet written, per connection. Default 1,000,000. */
        public Builder maxPendingMessages(int messages) {
            config.maxPendingMessages(messages);
            return this;
        }

        /** What {@link ProtoConnection#send} does when a connection's queue is full. Default {@link OverflowPolicy#BLOCK}. */
        public Builder overflowPolicy(OverflowPolicy policy) {
            config.overflowPolicy(policy);
            return this;
        }

        /** How long {@link OverflowPolicy#BLOCK} waits for room. {@link Duration#ZERO} = not at all. Default 10 s. */
        public Builder sendTimeout(Duration timeout) {
            config.sendTimeout(timeout);
            return this;
        }

        /**
         * A socket option, applied to the listening socket and to accepted connections, where supported (others are
         * ignored). Default: {@code TCP_NODELAY} and {@code SO_REUSEADDR} on.
         */
        public <T> Builder socketOption(SocketOption<T> option, T value) {
            config.socketOption(option, value);
            return this;
        }

        /** Loads received message classes. Default: the thread's context class loader when {@link #build()} runs. */
        public Builder classLoader(ClassLoader loader) {
            config.classLoader(Objects.requireNonNull(loader, "loader"));
            return this;
        }

        /** Creates the acceptor thread and each connection's reader and writer threads. Default: virtual threads. */
        public Builder threadFactory(ThreadFactory factory) {
            config.threadFactory(Objects.requireNonNull(factory, "factory"));
            return this;
        }

        public ProtoServer build() {
            return new DefaultProtoServer(bindAddress, backlog, maxConnections, config.build(), listener);
        }
    }
}
