package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.Callbacks;
import io.github.quantranuk.protobuf.nio.internal.ConnectionConfig;
import io.github.quantranuk.protobuf.nio.internal.DefaultProtoServer;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;

/**
 * Accepts protobuf-nio connections, each a {@link ProtoConnection}. The builder sets what happens on each connection's
 * events:
 * <pre>{@code
 * ProtoServer server = ProtoServer.builder(3456)
 *         .onMessage(Ping.class, (connection, ping) -> connection.send(Pong.newBuilder().setText("pong").build()))
 *         .build();
 * server.start();
 * // ...
 * server.close();
 * }</pre>
 * All methods are thread-safe.
 *
 * <h2>Callbacks and threads</h2>
 * Each accepted connection has a reader and a writer virtual thread; one more virtual thread accepts connections.
 * <ul>
 *     <li>A connection's reader thread runs its {@code onConnected}, {@code onMessage} (and raw handlers) and
 *     {@code onDisconnected} callbacks, in that order and never overlapping.</li>
 *     <li>Its writer thread runs {@code onSendFailure}, except for {@link SendException.Reason#REJECTED} and
 *     {@link SendException.Reason#DROPPED}, which run on the thread that called {@code send()} or
 *     {@code broadcast()}, before it returns.</li>
 *     <li>{@code onDisconnected} is a connection's last callback, exactly once, after every {@code onSendFailure}.</li>
 *     <li>A connection whose handshake fails is never reported.</li>
 * </ul>
 * <b>Callbacks for different connections run concurrently</b>, each on its connection's own virtual thread, so they
 * must be thread-safe. A callback may block; that stalls only its own connection. An exception thrown by a callback is
 * logged and ignored, and the connection carries on.
 */
public interface ProtoServer extends AutoCloseable {

    /**
     * Starts building a server that listens on every interface.
     *
     * @param port the port to listen on, or 0 for any free port (see {@link #localAddress()})
     * @return a new builder
     * @throws IllegalArgumentException if the port is outside 0&ndash;65535
     */
    static Builder builder(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        return new Builder(new InetSocketAddress(port));
    }

    /**
     * Starts building a server that listens on one address, for example {@code new InetSocketAddress("127.0.0.1", 0)}.
     *
     * @param bindAddress the local address and port to bind to
     * @return a new builder
     */
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

    /**
     * The address the server is listening on, useful with port 0.
     *
     * @return the bound address
     * @throws IllegalStateException before {@code start()}
     */
    InetSocketAddress localAddress();

    /**
     * The open connections: those that have had {@code onConnected} and not yet {@code onDisconnected}.
     *
     * @return a weakly consistent, unmodifiable view, which changes as connections open and close
     */
    Collection<ProtoConnection> connections();

    /**
     * Encodes the message once and queues it on every open connection. Never blocks: under
     * {@link OverflowPolicy#BLOCK}, a connection whose queue is full rejects the message as under
     * {@link OverflowPolicy#REJECT}; {@code DROP_OLDEST} and {@code DISCONNECT} apply as usual. Rejections and drops
     * are
     * reported to {@code onSendFailure} on the calling thread.
     *
     * @param message the message to send
     * @return the number of connections it was queued on
     * @throws NullPointerException if {@code message} is null
     */
    int broadcast(MessageLite message);

    /**
     * Same as {@link #broadcast(MessageLite)}, for the open connections that match the filter, for example the
     * subscribers to a topic. The filter runs on the calling thread, once per open connection.
     *
     * @param message the message to send
     * @param filter  selects the connections to send to
     * @return the number of connections it was queued on
     * @throws NullPointerException if {@code message} or {@code filter} is null
     */
    int broadcast(MessageLite message, Predicate<? super ProtoConnection> filter);

    /**
     * Whether the server is accepting connections.
     *
     * @return true after {@code start()}, until {@code close()}
     */
    boolean isOpen();

    /**
     * Stops accepting, closes every connection as {@link ProtoConnection#close()} does (in parallel), and waits until
     * each has reported {@code onDisconnected}. A connection still in its handshake isn't cut off: its client counts
     * itself connected as soon as it has the server's preamble and may already be sending, so the handshake gets until
     * {@code closeTimeout} to finish, and the connection then opens and closes gracefully like the others (it's
     * reported, {@code onConnected} included); one that doesn't finish by then is closed without being reported. Called
     * from a connection's own callback, it doesn't wait for that connection. Idempotent.
     */
    @Override
    void close();

    /**
     * Blocks until {@code close()} has finished, for example at the end of {@code main()}.
     *
     * @throws IllegalStateException if {@code start()} hasn't been called
     * @throws InterruptedException  if interrupted while waiting
     */
    void awaitClosed() throws InterruptedException;

    /**
     * Configures and builds servers. Reusable: each {@link #build()} makes a new, unstarted server with the current
     * settings. Invalid values throw {@link IllegalArgumentException} as soon as the setting is called, and
     * {@code null} arguments throw {@link NullPointerException}. Connection settings apply to each accepted connection.
     */
    final class Builder {
        private final InetSocketAddress bindAddress;
        private final ConnectionConfig.Builder config = ConnectionConfig.builder().socketOption(StandardSocketOptions.SO_REUSEADDR, true);
        private final Callbacks.Builder<ProtoConnection> callbacks = Callbacks.builder();
        private int backlog = 1024;
        private int maxConnections = Integer.MAX_VALUE;

        private Builder(InetSocketAddress bindAddress) {
            this.bindAddress = bindAddress;
        }

        /**
         * Called when a connection is open: its TLS handshake (when configured) and protocol preamble have succeeded.
         * Runs on the connection's reader thread, before any {@code onMessage}. Just before it runs, the connection joins
         * {@link ProtoServer#connections()}, so it may already receive broadcasts. Default: nothing.
         *
         * @param handler called with each new connection
         * @return this builder
         */
        public Builder onConnected(Consumer<ProtoConnection> handler) {
            callbacks.onConnected(handler);
            return this;
        }

        /**
         * Handles received messages whose class is exactly {@code type} (subclasses and other types don't match). Runs
         * on the connection's reader thread, in the order the client sent the messages; the connection's next message
         * isn't read until the handler returns. Reply with {@link ProtoConnection#send}.
         *
         * @param type    the generated message class
         * @param handler called for each message of that type
         * @param <T>     the message type
         * @return this builder
         * @throws IllegalArgumentException if a handler for this type is already registered
         */
        @SuppressWarnings("unchecked")
        public <T extends MessageLite> Builder onMessage(Class<T> type, MessageHandler<? super T> handler) {
            Objects.requireNonNull(handler, "handler");
            callbacks.onMessage(type, (connection, message) -> handler.onMessage(connection, (T) message));
            return this;
        }

        /**
         * Handles received messages of types without their own {@link #onMessage} handler. Without it, they're logged
         * at WARN once per type and dropped.
         *
         * @param handler called on the connection's reader thread for each such message
         * @return this builder
         */
        public Builder onOtherMessage(MessageHandler<MessageLite> handler) {
            Objects.requireNonNull(handler, "handler");
            callbacks.onOtherMessage(handler::onMessage);
            return this;
        }

        /**
         * Optional low-garbage path: messages of this type skip parsing and go to {@code handler} with their raw
         * bytes, instead of {@code onMessage}. The type is always accepted, even when {@link #allowedMessageTypes}
         * doesn't list it.
         *
         * @param type    the generated message class
         * @param handler receives the payload of each message of that type, on the connection's reader thread
         * @return this builder
         * @throws IllegalArgumentException if the type already has a raw handler, or isn't a generated protobuf message
         *                                  class
         */
        public Builder onRawMessage(Class<? extends MessageLite> type, RawMessageHandler handler) {
            config.rawHandler(type, handler);
            return this;
        }

        /**
         * Called for a message passed to {@link ProtoConnection#send} or {@link ProtoServer#broadcast} that wasn't
         * written. Runs on the connection's writer thread, except for {@link SendException.Reason#REJECTED} and
         * {@link SendException.Reason#DROPPED}, which run on the thread that called {@code send()} or
         * {@code broadcast()}. Default: nothing.
         *
         * @param handler called for each message that wasn't written
         * @return this builder
         */
        public Builder onSendFailure(SendFailureHandler handler) {
            Objects.requireNonNull(handler, "handler");
            callbacks.onSendFailure(handler::onSendFailure);
            return this;
        }

        /**
         * Called when a connection closes: its last callback, exactly once, on its reader thread, after every
         * {@code onSendFailure}. Default: nothing.
         *
         * @param handler called once for each connection that closes
         * @return this builder
         */
        public Builder onDisconnected(DisconnectHandler handler) {
            Objects.requireNonNull(handler, "handler");
            callbacks.onDisconnected(handler::onDisconnected);
            return this;
        }

        /**
         * Pending connections the operating system may queue. Default 1024.
         *
         * @param backlog the queue length; must be positive
         * @return this builder
         */
        public Builder backlog(int backlog) {
            if (backlog < 1) {
                throw new IllegalArgumentException("backlog must be positive: " + backlog);
            }
            this.backlog = backlog;
            return this;
        }

        /**
         * Caps the number of connections, including those still in their handshake. At the limit, a new connection
         * replaces the oldest connection still in its handshake, which is closed without any callback, so peers that
         * connect and stay silent can't hold every slot; if every connection has finished its handshake, the new
         * connection is closed at once. Default: unlimited.
         *
         * @param max the limit; must be positive
         * @return this builder
         */
        public Builder maxConnections(int max) {
            if (max < 1) {
                throw new IllegalArgumentException("maxConnections must be positive: " + max);
            }
            maxConnections = max;
            return this;
        }

        /**
         * Timeout of each connection's protocol preamble (and TLS handshake, when configured). Default 10 s.
         *
         * @param timeout the timeout; must be positive
         * @return this builder
         */
        public Builder handshakeTimeout(Duration timeout) {
            config.handshakeTimeout(timeout);
            return this;
        }

        /**
         * Close a connection if nothing is received for this long; any frame, heartbeats included, resets it. Default:
         * none.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} for none
         * @return this builder
         */
        public Builder readTimeout(Duration timeout) {
            config.readTimeout(timeout);
            return this;
        }

        /**
         * Send a heartbeat frame when nothing has been sent for this long. Default: none.
         *
         * @param interval the interval, or {@link Duration#ZERO} for no heartbeats
         * @return this builder
         */
        public Builder heartbeatInterval(Duration interval) {
            config.heartbeatInterval(interval);
            return this;
        }

        /**
         * Close a connection if a socket write blocks for longer than this. Default 10 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} for none
         * @return this builder
         */
        public Builder writeTimeout(Duration timeout) {
            config.writeTimeout(timeout);
            return this;
        }

        /**
         * How long closing a connection may spend writing queued messages. Default 5 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} to close at once without flushing
         * @return this builder
         */
        public Builder closeTimeout(Duration timeout) {
            config.closeTimeout(timeout);
            return this;
        }

        /**
         * Largest socket read per connection. Default 64 KB.
         *
         * @param bytes the size, at least 256
         * @return this builder
         */
        public Builder readBufferSize(int bytes) {
            config.readBufferSize(bytes);
            return this;
        }

        /**
         * Largest batch written in one go per connection. Default 64 KB.
         *
         * @param bytes the size, at least 256
         * @return this builder
         */
        public Builder writeBufferSize(int bytes) {
            config.writeBufferSize(bytes);
            return this;
        }

        /**
         * Largest protobuf payload accepted from a client. Default 64 MiB.
         *
         * @param bytes the size, from 0 up to about 2 GiB
         * @return this builder
         */
        public Builder maxMessageSize(int bytes) {
            config.maxMessageSize(bytes);
            return this;
        }

        /**
         * Most encoded bytes queued and not yet written, per connection. A single larger message is queued only when
         * nothing else is pending. Default 64 MiB.
         *
         * @param bytes the limit; must be positive
         * @return this builder
         */
        public Builder maxPendingBytes(long bytes) {
            config.maxPendingBytes(bytes);
            return this;
        }

        /**
         * Most messages queued and not yet written, per connection. Default 1,000,000.
         *
         * @param messages the limit; must be positive
         * @return this builder
         */
        public Builder maxPendingMessages(int messages) {
            config.maxPendingMessages(messages);
            return this;
        }

        /**
         * What {@link ProtoConnection#send} does when a connection's queue is full. Default {@link
         * OverflowPolicy#BLOCK}.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder overflowPolicy(OverflowPolicy policy) {
            config.overflowPolicy(policy);
            return this;
        }

        /**
         * How long {@link OverflowPolicy#BLOCK} waits for room. Default 10 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} to give up at once
         * @return this builder
         */
        public Builder sendTimeout(Duration timeout) {
            config.sendTimeout(timeout);
            return this;
        }

        /**
         * A socket option, applied to the listening socket and to accepted connections, where supported (others are
         * ignored). Default: {@code TCP_NODELAY} and {@code SO_REUSEADDR} on.
         *
         * @param option the option, for example {@link java.net.StandardSocketOptions#SO_SNDBUF}
         * @param value its value
         * @param <T> the type of the option's value
         * @return this builder
         */
        public <T> Builder socketOption(SocketOption<T> option, T value) {
            config.socketOption(option, value);
            return this;
        }

        /**
         * Accepts only these message types from the peer, which is disconnected with
         * {@link DisconnectReason#PROTOCOL_ERROR} if it sends any other; the class of a disallowed type is never
         * loaded.
         * Use it when the peer isn't trusted. Replaces the previous list. Default: any protobuf class on the class
         * path.
         *
         * @param types the generated message classes to accept
         * @return this builder
         * @throws IllegalArgumentException if no type is given, or a type isn't a generated protobuf message class
         */
        @SafeVarargs
        public final Builder allowedMessageTypes(Class<? extends MessageLite>... types) {
            config.allowedMessageTypes(types);
            return this;
        }

        /**
         * Loads received message classes. Default: the thread's context class loader when {@link #build()} runs.
         *
         * @param loader the class loader
         * @return this builder
         */
        public Builder classLoader(ClassLoader loader) {
            config.classLoader(Objects.requireNonNull(loader, "loader"));
            return this;
        }

        /**
         * Creates the acceptor thread and each connection's reader and writer threads. Default: virtual threads.
         *
         * @param factory the thread factory
         * @return this builder
         */
        public Builder threadFactory(ThreadFactory factory) {
            config.threadFactory(Objects.requireNonNull(factory, "factory"));
            return this;
        }

        /**
         * Accepts TLS connections only, with the certificate of this context's key managers (and its trust managers
         * for client certificates, see {@link #tlsClientAuth}). Each connection's TLS handshake runs on its own thread
         * within {@code handshakeTimeout}; a connection whose handshake fails is never reported to the callbacks.
         * Default: no TLS.
         *
         * @param context provides the protocols, cipher suites, certificate and trust material
         * @return this builder
         */
        public Builder tls(SSLContext context) {
            config.tls(Objects.requireNonNull(context, "context"));
            return this;
        }

        /**
         * With TLS, whether clients must present a trusted certificate. Default {@link TlsClientAuth#NONE}.
         *
         * @param mode whether to ask for client certificates
         * @return this builder
         */
        public Builder tlsClientAuth(TlsClientAuth mode) {
            config.tlsClientAuth(mode);
            return this;
        }

        /**
         * Builds a server with the current settings. Call {@link ProtoServer#start()} on it to bind.
         *
         * @return a new, unstarted server
         */
        public ProtoServer build() {
            return new DefaultProtoServer(bindAddress, backlog, maxConnections, config.build(), callbacks.build());
        }
    }

    /**
     * Handles received messages of one type; see {@link Builder#onMessage}.
     *
     * @param <T> the message type
     */
    @FunctionalInterface
    interface MessageHandler<T extends MessageLite> {
        /**
         * Called on the connection's reader thread.
         *
         * @param connection the connection that received the message
         * @param message    the parsed message
         */
        void onMessage(ProtoConnection connection, T message);
    }

    /** Handles a message of a type registered with {@link Builder#onRawMessage}, without parsing it. */
    @FunctionalInterface
    interface RawMessageHandler {
        /**
         * Called on the connection's reader thread, like {@code onMessage}. Raw handlers for different connections run
         * concurrently.
         *
         * @param connection the connection that received the message
         * @param payload    the unparsed protobuf bytes, from its position to its limit. To avoid allocating per
         *                   message, it's a view over the read buffer that's reused: it's valid only during the call,
         *                   and its bytes mustn't be modified. It's a heap buffer, so {@code payload.array()},
         *                   {@code arrayOffset() + position()} and {@code remaining()} give the bytes without copying,
         *                   for example to parse into a builder reused by this connection. Copy what you need to keep.
         */
        void onMessage(ProtoConnection connection, ByteBuffer payload);
    }

    /** Called for a message that wasn't written; see {@link Builder#onSendFailure}. */
    @FunctionalInterface
    interface SendFailureHandler {
        /**
         * See {@link Builder#onSendFailure}.
         *
         * @param connection the connection the message was sent on
         * @param message    the message that wasn't written
         * @param cause      why; {@link SendException#reason()} tells the cases apart
         */
        void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause);
    }

    /** Called when a connection closes; see {@link Builder#onDisconnected}. */
    @FunctionalInterface
    interface DisconnectHandler {
        /**
         * See {@link Builder#onDisconnected}.
         *
         * @param connection the connection that closed
         * @param reason     why it closed
         * @param cause      the exception behind the reason, or {@code null} for {@link DisconnectReason#CLOSED_LOCALLY}
         *                   and {@link DisconnectReason#CLOSED_BY_PEER}
         */
        void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause);
    }
}
