package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.ConnectionConfig;
import io.github.quantranuk.protobuf.nio.internal.DefaultProtoClient;
import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

/**
 * A single-use client connection: build it, {@link #connect()} it, {@link #close()} it. Build a new one to reconnect
 * (the {@link Builder} is reusable).
 * <pre>{@code
 * try (ProtoClient client = ProtoClient.builder("localhost", 3456).listener(listener).build()) {
 *     client.connect();
 *     client.send(request);
 * }
 * }</pre>
 * States: new (after {@code build()}), connecting (during {@code connect()}), open, closed. All methods are
 * thread-safe. {@code equals}/{@code hashCode} are identity; {@code toString()} is
 * {@code "ProtoClient#<id>[<remote address>]"}.
 * <p>
 * Each connected client uses two virtual threads: a reader, which runs {@code onConnected}, {@code onMessage} and
 * {@code onDisconnected}, and a writer, which runs {@code onSendFailure}. See {@link ProtoClientListener}.
 */
public interface ProtoClient extends AutoCloseable {

    /**
     * Starts building a client for a host name or address. The host name is resolved by {@link #connect()}, not here,
     * and with TLS it's also the name the server's certificate is checked against.
     *
     * @param host the server's host name or IP address
     * @param port the server's port
     * @return a new builder
     * @throws IllegalArgumentException if the port is outside 1&ndash;65535
     */
    static Builder builder(String host, int port) {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
        return new Builder(InetSocketAddress.createUnresolved(host, port));
    }

    /**
     * Starts building a client for a socket address. An unresolved address is resolved by {@link #connect()}.
     *
     * @param address the server's address; with TLS, its {@link InetSocketAddress#getHostString() host string} is
     *                the name the server's certificate is checked against
     * @return a new builder
     */
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
     *                               {@code UnknownHostException}, ...), or if {@link #close()} was called while
     * connecting
     * @throws IllegalStateException if {@code connect()} was already called, or the client was closed before connecting
     */
    void connect() throws IOException;

    /**
     * Same as {@link #connect()}, on a virtual thread. The client is in the connecting state when this returns, so
     * messages sent from then on are queued.
     *
     * @return a future that completes when connected, or fails with the exception {@code connect()} would throw
     * @throws IllegalStateException if {@code connect()} was already called, or the client was closed before connecting
     */
    CompletableFuture<Void> connectAsync();

    /**
     * Identifies the client, for example in logs.
     *
     * @return an id unique within the JVM and increasing; the sequence is shared with server connections
     */
    long id();

    /**
     * The server's address.
     *
     * @return before {@code connect()} succeeds, the target address, possibly unresolved; after, the resolved address
     */
    InetSocketAddress remoteAddress();

    /**
     * This side's address.
     *
     * @return the local address of the socket, or {@code null} until connected
     */
    InetSocketAddress localAddress();

    /**
     * Whether the client is connected and accepting sends.
     *
     * @return true once connected, until closing starts
     */
    boolean isOpen();

    /**
     * The TLS session, with the negotiated protocol, cipher suite and the server's certificates.
     *
     * @return the TLS session, or {@code null} without TLS or before {@code connect()}
     */
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
     * @param message the message to send
     * @return true if queued; false if not (queue full, or closing or closed). If interrupted while waiting for room,
     *         returns false with the interrupt flag kept.
     * @throws IllegalStateException if {@code connect()} hasn't been called
     * @throws NullPointerException  if {@code message} is null
     */
    boolean send(MessageLite message);

    /**
     * How far behind the client is, in messages.
     *
     * @return the number of messages queued and not yet written
     */
    int pendingMessages();

    /**
     * How far behind the client is, in bytes.
     *
     * @return the encoded size of the messages queued and not yet written
     */
    long pendingBytes();

    /**
     * Stops accepting sends, writes what's queued for up to {@code closeTimeout}, then closes; messages still queued
     * then
     * fail with {@link SendException.Reason#CLOSED}. Blocks, except when called from this client's own threads (from
     * its
     * writer thread it returns at once; from its reader thread it waits only for the queue to be written). Idempotent.
     * <p>
     * Before {@code connect()}: marks the client closed, without listener calls. While connecting: cancels, and
     * {@code connect()} throws.
     */
    @Override
    void close();

    /**
     * Blocks until the client has closed and {@code onDisconnected} has returned, or {@code connect()} has failed.
     *
     * @return why it closed
     * @throws IllegalStateException if {@code connect()} hasn't been called
     * @throws InterruptedException  if interrupted while waiting
     */
    DisconnectReason awaitClosed() throws InterruptedException;

    /**
     * Your own state, not used by the library (for example which session a shared listener is serving). Visible to
     * every thread once {@link #attach} has returned.
     *
     * @param <T> the type to cast the attachment to
     * @return the last object passed to {@link #attach}, or {@code null}
     * @throws ClassCastException if the attachment isn't a {@code T}, when the result is assigned
     */
    <T> T attachment();

    /**
     * Sets the object returned by {@link #attachment()}, replacing the previous one.
     *
     * @param attachment any object, or {@code null}
     */
    void attach(Object attachment);

    /**
     * Configures and builds clients. Reusable: each {@link #build()} makes a new, unconnected client with the current
     * settings. Invalid values throw {@link IllegalArgumentException} as soon as the setting is called, and
     * {@code null} arguments throw {@link NullPointerException}.
     */
    final class Builder {
        private final InetSocketAddress address;
        private final ConnectionConfig.Builder config = ConnectionConfig.builder();
        private ProtoClientListener listener = new ProtoClientListener() { };
        private Duration connectTimeout = Duration.ofSeconds(10);

        private Builder(InetSocketAddress address) {
            this.address = address;
        }

        /**
         * Sets the listener that receives the events of the client. Default: a no-op listener.
         *
         * @param listener receives the client's events
         * @return this builder
         */
        public Builder listener(ProtoClientListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /**
         * Optional low-garbage path: messages of this type skip parsing and go to {@code handler} with their raw
         * bytes, instead of the listener's {@code onMessage}. The type is always accepted, even when
         * {@link #allowedMessageTypes} doesn't list it.
         *
         * @param type the generated message class
         * @param handler receives the payload of each message of that type
         * @return this builder
         * @throws IllegalArgumentException if the type already has a raw handler, or isn't a generated protobuf message
         *                                  class
         */
        public Builder rawHandler(Class<? extends MessageLite> type, ProtoClientListener.RawMessageHandler handler) {
            config.rawHandler(type, handler);
            return this;
        }

        /**
         * Timeout of the TCP connect. Default 10 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} for the operating system's
         * @return this builder
         */
        public Builder connectTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must not be negative: " + timeout);
            }
            connectTimeout = timeout;
            return this;
        }

        /**
         * Timeout of the protocol preamble (and TLS handshake, when configured), after the TCP connect. Default 10 s.
         *
         * @param timeout the timeout; must be positive
         * @return this builder
         */
        public Builder handshakeTimeout(Duration timeout) {
            config.handshakeTimeout(timeout);
            return this;
        }

        /**
         * Close if nothing is received for this long; any frame, heartbeats included, resets it. Default: none.
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
         * Close if a socket write blocks for longer than this (the peer stopped reading). Default 10 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} for none
         * @return this builder
         */
        public Builder writeTimeout(Duration timeout) {
            config.writeTimeout(timeout);
            return this;
        }

        /**
         * How long {@code close()} may spend writing queued messages. Default 5 s.
         *
         * @param timeout the timeout, or {@link Duration#ZERO} to close at once without flushing
         * @return this builder
         */
        public Builder closeTimeout(Duration timeout) {
            config.closeTimeout(timeout);
            return this;
        }

        /**
         * Largest socket read; reads start small and grow only while they fill the buffer. Default 64 KB.
         *
         * @param bytes the size, at least 256
         * @return this builder
         */
        public Builder readBufferSize(int bytes) {
            config.readBufferSize(bytes);
            return this;
        }

        /**
         * Largest batch of queued messages written in one go; a single larger message is still sent. Default 64 KB.
         *
         * @param bytes the size, at least 256
         * @return this builder
         */
        public Builder writeBufferSize(int bytes) {
            config.writeBufferSize(bytes);
            return this;
        }

        /**
         * Largest protobuf payload accepted from the peer, which is disconnected
         * ({@link DisconnectReason#PROTOCOL_ERROR}) if it sends a larger one. Default 64 MiB.
         *
         * @param bytes the size, from 0 up to about 2 GiB
         * @return this builder
         */
        public Builder maxMessageSize(int bytes) {
            config.maxMessageSize(bytes);
            return this;
        }

        /**
         * Most encoded bytes queued and not yet written. A single larger message is queued only when nothing else is
         * pending. Default 64 MiB.
         *
         * @param bytes the limit; must be positive
         * @return this builder
         */
        public Builder maxPendingBytes(long bytes) {
            config.maxPendingBytes(bytes);
            return this;
        }

        /**
         * Most messages queued and not yet written. Default 1,000,000.
         *
         * @param messages the limit; must be positive
         * @return this builder
         */
        public Builder maxPendingMessages(int messages) {
            config.maxPendingMessages(messages);
            return this;
        }

        /**
         * What {@link ProtoClient#send} does when the queue is full. Default {@link OverflowPolicy#BLOCK}.
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
         * A socket option; options the socket doesn't support are ignored. Default: {@code TCP_NODELAY} on.
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
         * Creates the reader and writer threads (two per connection). Default: virtual threads.
         *
         * @param factory the thread factory
         * @return this builder
         */
        public Builder threadFactory(ThreadFactory factory) {
            config.threadFactory(Objects.requireNonNull(factory, "factory"));
            return this;
        }

        /**
         * Connects over TLS, with the protocols, ciphers and trust material of this context (its trust managers decide
         * which server certificates are accepted; its key managers provide a client certificate if the server asks for
         * one). The TLS handshake runs within {@code handshakeTimeout}; if it fails, {@code connect()} throws an
         * {@link javax.net.ssl.SSLException}. Default: no TLS.
         *
         * @param context provides the protocols, cipher suites, certificate and trust material
         * @return this builder
         */
        public Builder tls(SSLContext context) {
            config.tls(Objects.requireNonNull(context, "context"));
            return this;
        }

        /**
         * With TLS, whether the server's certificate must match the host name given to {@link ProtoClient#builder}
         * (as HTTPS clients check). Turn it off only for tests or when trust is pinned some other way. Default true.
         *
         * @param enabled whether to check the host name
         * @return this builder
         */
        public Builder tlsHostnameVerification(boolean enabled) {
            config.tlsHostnameVerification(enabled);
            return this;
        }

        /**
         * Builds a client with the current settings. Call {@link ProtoClient#connect()} on it to connect.
         *
         * @return a new, unconnected client
         */
        public ProtoClient build() {
            return new DefaultProtoClient(address, connectTimeout, config.build(), listener);
        }
    }
}
