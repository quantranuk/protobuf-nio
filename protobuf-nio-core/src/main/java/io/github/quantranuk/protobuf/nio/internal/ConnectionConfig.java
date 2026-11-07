package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.OverflowPolicy;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * Immutable settings of a {@link Connection}. Durations are stored in nanoseconds; 0 means "none" for the read timeout,
 * heartbeat interval and write timeout, and "close at once" for the close timeout.
 */
public final class ConnectionConfig {

    public static final int MIN_BUFFER_SIZE = 256;
    /** Keeps the largest frame (header + longest class name + payload) within an int. */
    public static final int MAX_MESSAGE_SIZE_LIMIT = Integer.MAX_VALUE - ProtobufSerializer.HEADER_LENGTH - Protocol.MAX_CLASS_NAME_LENGTH - 8;

    final long handshakeTimeoutNanos;
    final long readTimeoutNanos;
    final long heartbeatIntervalNanos;
    final long writeTimeoutNanos;
    final long closeTimeoutNanos;
    final long idleShrinkDelayNanos;
    final int readBufferSize;
    final int writeBufferSize;
    final int maxMessageSize;
    final long maxPendingBytes;
    final int maxPendingMessages;
    final OverflowPolicy overflowPolicy;
    final long sendTimeoutNanos;
    final Map<SocketOption<?>, Object> socketOptions;
    // Allowed message types: read-only buffer of the ISO-8859-1 class name -> parser; null = any protobuf class
    final Map<ByteBuffer, Parser<? extends MessageLite>> allowedTypes;
    // Raw handlers: read-only buffer of the ISO-8859-1 class name -> handler; always allowed
    final Map<ByteBuffer, RawHandler> rawHandlers;
    final ClassLoader classLoader;
    final ThreadFactory threadFactory;   // null = a named virtual thread per reader and writer

    private ConnectionConfig(Builder b) {
        handshakeTimeoutNanos = b.handshakeTimeout.toNanos();
        readTimeoutNanos = b.readTimeout.toNanos();
        heartbeatIntervalNanos = b.heartbeatInterval.toNanos();
        writeTimeoutNanos = b.writeTimeout.toNanos();
        closeTimeoutNanos = b.closeTimeout.toNanos();
        idleShrinkDelayNanos = b.idleShrinkDelay.toNanos();
        readBufferSize = b.readBufferSize;
        writeBufferSize = b.writeBufferSize;
        maxMessageSize = b.maxMessageSize;
        maxPendingBytes = b.maxPendingBytes;
        maxPendingMessages = b.maxPendingMessages;
        overflowPolicy = b.overflowPolicy;
        sendTimeoutNanos = b.sendTimeout.toNanos();
        socketOptions = Collections.unmodifiableMap(new LinkedHashMap<>(b.socketOptions));
        allowedTypes = b.allowedTypes;
        rawHandlers = Map.copyOf(b.rawHandlers);
        classLoader = b.classLoader != null ? b.classLoader : defaultClassLoader();
        threadFactory = b.threadFactory;
    }

    /** A raw handler registered for one message type; the handler is the public API's, passed back as is. */
    record RawHandler(String typeName, Object handler) {
    }

    public static Builder builder() {
        return new Builder();
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ConnectionConfig.class.getClassLoader();
    }

    /** Mutable and reusable. Setters validate at once and throw {@link IllegalArgumentException}. */
    public static final class Builder {
        private Duration handshakeTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ZERO;
        private Duration heartbeatInterval = Duration.ZERO;
        private Duration writeTimeout = Duration.ofSeconds(10);
        private Duration closeTimeout = Duration.ofSeconds(5);
        private Duration idleShrinkDelay = Duration.ofSeconds(1);
        private int readBufferSize = 64 * 1024;
        private int writeBufferSize = 64 * 1024;
        private int maxMessageSize = 64 * 1024 * 1024;
        private long maxPendingBytes = 64L * 1024 * 1024;
        private int maxPendingMessages = 1_000_000;
        private OverflowPolicy overflowPolicy = OverflowPolicy.BLOCK;
        private Duration sendTimeout = Duration.ofSeconds(10);
        private final Map<SocketOption<?>, Object> socketOptions = new LinkedHashMap<>(Map.of(StandardSocketOptions.TCP_NODELAY, true));
        private Map<ByteBuffer, Parser<? extends MessageLite>> allowedTypes;
        private final Map<ByteBuffer, RawHandler> rawHandlers = new HashMap<>();
        private ClassLoader classLoader;
        private ThreadFactory threadFactory;

        private Builder() {
        }

        /** TLS handshake plus protocol preamble; must be positive. */
        public Builder handshakeTimeout(Duration timeout) {
            handshakeTimeout = positive(timeout, "handshakeTimeout");
            return this;
        }

        /** {@link Duration#ZERO} = none. */
        public Builder readTimeout(Duration timeout) {
            readTimeout = nonNegative(timeout, "readTimeout");
            return this;
        }

        /** {@link Duration#ZERO} = no heartbeats. */
        public Builder heartbeatInterval(Duration interval) {
            heartbeatInterval = nonNegative(interval, "heartbeatInterval");
            return this;
        }

        /** {@link Duration#ZERO} = none. */
        public Builder writeTimeout(Duration timeout) {
            writeTimeout = nonNegative(timeout, "writeTimeout");
            return this;
        }

        /** {@link Duration#ZERO} = close at once, without flushing. */
        public Builder closeTimeout(Duration timeout) {
            closeTimeout = nonNegative(timeout, "closeTimeout");
            return this;
        }

        /** How long a connection must be idle before its buffers are released. Not part of the public API. */
        public Builder idleShrinkDelay(Duration delay) {
            idleShrinkDelay = positive(delay, "idleShrinkDelay");
            return this;
        }

        public Builder readBufferSize(int bytes) {
            readBufferSize = atLeast(bytes, MIN_BUFFER_SIZE, "readBufferSize");
            return this;
        }

        public Builder writeBufferSize(int bytes) {
            writeBufferSize = atLeast(bytes, MIN_BUFFER_SIZE, "writeBufferSize");
            return this;
        }

        public Builder maxMessageSize(int bytes) {
            if (bytes < 0 || bytes > MAX_MESSAGE_SIZE_LIMIT) {
                throw new IllegalArgumentException("maxMessageSize must be between 0 and " + MAX_MESSAGE_SIZE_LIMIT + ": " + bytes);
            }
            maxMessageSize = bytes;
            return this;
        }

        /** Encoded bytes queued but not yet written; must be positive. A larger message is queued only when nothing else is. */
        public Builder maxPendingBytes(long bytes) {
            if (bytes < 1) {
                throw new IllegalArgumentException("maxPendingBytes must be positive: " + bytes);
            }
            maxPendingBytes = bytes;
            return this;
        }

        /** Messages queued but not yet written; must be positive. */
        public Builder maxPendingMessages(int messages) {
            maxPendingMessages = atLeast(messages, 1, "maxPendingMessages");
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy policy) {
            overflowPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** How long {@link OverflowPolicy#BLOCK} waits for room; {@link Duration#ZERO} = not at all. */
        public Builder sendTimeout(Duration timeout) {
            sendTimeout = nonNegative(timeout, "sendTimeout");
            return this;
        }

        public <T> Builder socketOption(SocketOption<T> option, T value) {
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(value, "value");
            socketOptions.put(option, value);
            return this;
        }

        /**
         * Restricts the received message types to these: any other type disconnects the peer with
         * {@code PROTOCOL_ERROR}, and its class is never loaded. Replaces the previous list. Default: any protobuf class.
         * @throws IllegalArgumentException if empty, or if a type isn't a generated message class
         */
        @SafeVarargs
        public final Builder allowedMessageTypes(Class<? extends MessageLite>... types) {
            Objects.requireNonNull(types, "types");
            if (types.length == 0) {
                throw new IllegalArgumentException("allowedMessageTypes needs at least one type");
            }
            Map<ByteBuffer, Parser<? extends MessageLite>> allowed = new HashMap<>();
            for (Class<? extends MessageLite> type : types) {
                allowed.put(encodedName(type), parserOf(type));
            }
            allowedTypes = Map.copyOf(allowed);
            return this;
        }

        /**
         * Messages of this type skip parsing and go to {@code handler} (through
         * {@link ConnectionListener#onRawMessage}) instead of {@code onMessage}. The type is always allowed.
         * @throws IllegalArgumentException if the type already has a raw handler or isn't a generated message class
         */
        public Builder rawHandler(Class<? extends MessageLite> type, Object handler) {
            Objects.requireNonNull(handler, "handler");
            parserOf(type);   // validates the type
            ByteBuffer name = encodedName(type);
            if (rawHandlers.containsKey(name)) {
                throw new IllegalArgumentException("A raw handler is already registered for " + type.getName());
            }
            rawHandlers.put(name, new RawHandler(type.getName(), handler));
            return this;
        }

        private static ByteBuffer encodedName(Class<?> type) {
            return ByteBuffer.wrap(type.getName().getBytes(ProtobufSerializer.CHARSET)).asReadOnlyBuffer();
        }

        private static Parser<? extends MessageLite> parserOf(Class<? extends MessageLite> type) {
            Objects.requireNonNull(type, "type");
            try {
                return MessageTypes.parserOf(type);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                throw new IllegalArgumentException("Not a generated protobuf message class: " + type.getName(), e);
            }
        }

        /** {@code null} = the thread's context class loader when {@link #build()} runs. */
        public Builder classLoader(ClassLoader loader) {
            classLoader = loader;
            return this;
        }

        /** {@code null} = virtual threads. Each connection uses two threads. */
        public Builder threadFactory(ThreadFactory factory) {
            threadFactory = factory;
            return this;
        }

        public ConnectionConfig build() {
            return new ConnectionConfig(this);
        }

        private static Duration nonNegative(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative: " + d);
            }
            return d;
        }

        private static Duration positive(Duration d, String name) {
            if (nonNegative(d, name).isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return d;
        }

        private static int atLeast(int value, int min, String name) {
            if (value < min) {
                throw new IllegalArgumentException(name + " must be at least " + min + ": " + value);
            }
            return value;
        }
    }
}
