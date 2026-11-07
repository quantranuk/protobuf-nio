package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.ConnectionConfig;
import io.github.quantranuk.protobuf.nio.internal.DefaultProtoClient;
import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

/**
 * A single-use client connection: build it, {@link #connect()} it, {@link #close()} it. Build a new one to reconnect.
 * <p>
 * States: new (after {@code build()}), connecting (during {@code connect()}), open, closed.
 * {@code equals}/{@code hashCode} are identity; {@code toString()} is {@code "ProtoClient#<id>[<remote address>]"}.
 * <p>
 * Each connected client uses two virtual threads: a reader, which runs {@code onConnected}, {@code onMessage} and
 * {@code onDisconnected}, and a writer, which runs {@code onSendFailure}. See {@link ProtoClientListener}.
 */
public interface ProtoClient extends AutoCloseable {

    /** The host name is resolved by {@link #connect()}, not here. */
    static Builder builder(String host, int port) {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
        return new Builder(InetSocketAddress.createUnresolved(host, port));
    }

    static Builder builder(InetSocketAddress address) {
        return new Builder(Objects.requireNonNull(address, "address"));
    }

    /**
     * Connects, completes the protocol preamble, then returns. {@code onConnected} runs on the reader thread before any
     * {@code onMessage}, and may still be running when this returns. On failure the client is closed and no listener
     * method is called.
     * <p>
     * The host name is resolved here, not at {@code build()}.
     *
     * @throws java.net.ProtocolException if the peer isn't a protobuf-nio 2.x endpoint (bad preamble) or has no
     *                               protocol version in common with this side; retrying against it won't help
     * @throws IOException           on any other failure or timeout ({@code ConnectException},
     *                               {@code SocketTimeoutException} for the connect or handshake timeout,
     *                               {@code UnknownHostException}, ...), or if {@link #close()} was called while connecting
     * @throws IllegalStateException if {@code connect()} was already called, or the client was closed before connecting
     */
    void connect() throws IOException;

    /**
     * Same as {@link #connect()}, on a virtual thread; the future fails with the exception {@code connect()} would throw.
     * The client is in the connecting state when this returns, so messages sent from then on are queued.
     */
    CompletableFuture<Void> connectAsync();

    /** Unique within the JVM and increasing; the sequence is shared with server connections. */
    long id();

    /** Before {@code connect()} succeeds: the target address, possibly unresolved. After: the resolved peer address. */
    InetSocketAddress remoteAddress();

    /** {@code null} until connected. */
    InetSocketAddress localAddress();

    /** @return true once connected, until closing starts */
    boolean isOpen();

    /** TLS session, or {@code null} without TLS or before {@code connect()}. */
    SSLSession tlsSession();

    /**
     * Queues the message. Thread-safe; messages from one thread are sent in order.
     * When the queue is full ({@code maxPendingMessages} or {@code maxPendingBytes}), the {@link OverflowPolicy}
     * decides: {@code BLOCK} waits up to {@code sendTimeout} for room, except on this client's writer thread (that is,
     * from {@code onSendFailure}), where it rejects at once.
     * Until closing starts, every message passed to send is either written or reported to {@code onSendFailure}: when
     * this returns false because the queue is full, {@code onSendFailure(REJECTED)} has already run on the calling
     * thread, and {@code DROP_OLDEST} reports what it drops as {@code DROPPED}, also on the calling thread.
     * Once closing has started, send returns false without calling the listener ({@code onDisconnected} stays last).
     * While {@code connect()} is running, messages are queued and sent after the preamble; if the queue fills up
     * meanwhile, send returns false without calling the listener ({@code DROP_OLDEST} then rejects rather than drops),
     * and if {@code connect()} fails, no listener method is called at all.
     *
     * @return true if queued; false if not (queue full, or closing or closed). If interrupted while waiting for room,
     *         returns false with the interrupt flag kept.
     * @throws IllegalStateException if {@code connect()} hasn't been called
     */
    boolean send(MessageLite message);

    /** Messages queued and not yet written. */
    int pendingMessages();

    /** Encoded size of the messages queued and not yet written. */
    long pendingBytes();

    /**
     * Stops accepting sends, writes what's queued for up to {@code closeTimeout}, then closes; messages still queued then
     * fail with {@link SendException.Reason#CLOSED}. Blocks, except when called from this client's own threads (from its
     * writer thread it returns at once; from its reader thread it waits only for the queue to be written). Idempotent.
     * <p>
     * Before {@code connect()}: marks the client closed, without listener calls. While connecting: cancels, and
     * {@code connect()} throws.
     */
    @Override
    void close();

    /**
     * Blocks until closed and {@code onDisconnected} has returned (or {@code connect()} has failed).
     * @return why it closed
     * @throws IllegalStateException if {@code connect()} hasn't been called
     */
    DisconnectReason awaitClosed() throws InterruptedException;

    /** Your own state, not used by the library (for example which session a shared listener is serving). */
    <T> T attachment();

    void attach(Object attachment);

    /** Reusable: each {@link #build()} makes a new, unconnected client. Invalid values throw {@link IllegalArgumentException} at once. */
    final class Builder {
        private final InetSocketAddress address;
        private final ConnectionConfig.Builder config = ConnectionConfig.builder();
        private ProtoClientListener listener = new ProtoClientListener() { };
        private Duration connectTimeout = Duration.ofSeconds(10);

        private Builder(InetSocketAddress address) {
            this.address = address;
        }

        /** Default: a no-op listener. */
        public Builder listener(ProtoClientListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** TCP connect. Default 10 s; {@link Duration#ZERO} = no timeout (the operating system's). */
        public Builder connectTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must not be negative: " + timeout);
            }
            connectTimeout = timeout;
            return this;
        }

        /** Protocol preamble (and TLS handshake, when configured). Default 10 s. */
        public Builder handshakeTimeout(Duration timeout) {
            config.handshakeTimeout(timeout);
            return this;
        }

        /** Close if nothing is received for this long; any frame, heartbeats included, resets it. Default: none. */
        public Builder readTimeout(Duration timeout) {
            config.readTimeout(timeout);
            return this;
        }

        /** Send a heartbeat frame when nothing has been sent for this long. Default: none. */
        public Builder heartbeatInterval(Duration interval) {
            config.heartbeatInterval(interval);
            return this;
        }

        /** Close if a socket write blocks for longer than this (the peer stopped reading). Default 10 s. */
        public Builder writeTimeout(Duration timeout) {
            config.writeTimeout(timeout);
            return this;
        }

        /** How long {@code close()} may spend writing queued messages. Default 5 s; {@link Duration#ZERO} = close at once. */
        public Builder closeTimeout(Duration timeout) {
            config.closeTimeout(timeout);
            return this;
        }

        /** Largest socket read; reads start small and grow only while they fill the buffer. Default 64 KB. */
        public Builder readBufferSize(int bytes) {
            config.readBufferSize(bytes);
            return this;
        }

        /** Largest batch of queued messages written in one go; a single larger message is still sent. Default 64 KB. */
        public Builder writeBufferSize(int bytes) {
            config.writeBufferSize(bytes);
            return this;
        }

        /** Largest protobuf payload accepted from the peer, which is disconnected if it sends a larger one. Default 64 MiB. */
        public Builder maxMessageSize(int bytes) {
            config.maxMessageSize(bytes);
            return this;
        }

        /**
         * Most encoded bytes queued and not yet written. A single larger message is queued only when nothing else is
         * pending. Default 64 MiB.
         */
        public Builder maxPendingBytes(long bytes) {
            config.maxPendingBytes(bytes);
            return this;
        }

        /** Most messages queued and not yet written. Default 1,000,000. */
        public Builder maxPendingMessages(int messages) {
            config.maxPendingMessages(messages);
            return this;
        }

        /** What {@link ProtoClient#send} does when the queue is full. Default {@link OverflowPolicy#BLOCK}. */
        public Builder overflowPolicy(OverflowPolicy policy) {
            config.overflowPolicy(policy);
            return this;
        }

        /** How long {@link OverflowPolicy#BLOCK} waits for room. {@link Duration#ZERO} = not at all. Default 10 s. */
        public Builder sendTimeout(Duration timeout) {
            config.sendTimeout(timeout);
            return this;
        }

        /** A socket option; options the socket doesn't support are ignored. Default: {@code TCP_NODELAY} on. */
        public <T> Builder socketOption(SocketOption<T> option, T value) {
            config.socketOption(option, value);
            return this;
        }

        /**
         * Accepts only these message types from the peer, which is disconnected with
         * {@link DisconnectReason#PROTOCOL_ERROR} if it sends any other; the class of a disallowed type is never loaded.
         * Use it when the peer isn't trusted. Replaces the previous list. Default: any protobuf class on the class path.
         * @throws IllegalArgumentException if no type is given, or a type isn't a generated protobuf message class
         */
        @SafeVarargs
        public final Builder allowedMessageTypes(Class<? extends MessageLite>... types) {
            config.allowedMessageTypes(types);
            return this;
        }

        /** Loads received message classes. Default: the thread's context class loader when {@link #build()} runs. */
        public Builder classLoader(ClassLoader loader) {
            config.classLoader(Objects.requireNonNull(loader, "loader"));
            return this;
        }

        /** Creates the reader and writer threads (two per connection). Default: virtual threads. */
        public Builder threadFactory(ThreadFactory factory) {
            config.threadFactory(Objects.requireNonNull(factory, "factory"));
            return this;
        }

        public ProtoClient build() {
            return new DefaultProtoClient(address, connectTimeout, config.build(), listener);
        }
    }
}
