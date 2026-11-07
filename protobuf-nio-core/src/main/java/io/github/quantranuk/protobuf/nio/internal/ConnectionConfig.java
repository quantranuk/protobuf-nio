package io.github.quantranuk.protobuf.nio.internal;

import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.Collections;
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
    final Map<SocketOption<?>, Object> socketOptions;
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
        socketOptions = Collections.unmodifiableMap(new LinkedHashMap<>(b.socketOptions));
        classLoader = b.classLoader != null ? b.classLoader : defaultClassLoader();
        threadFactory = b.threadFactory;
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
        private final Map<SocketOption<?>, Object> socketOptions = new LinkedHashMap<>(Map.of(StandardSocketOptions.TCP_NODELAY, true));
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

        public <T> Builder socketOption(SocketOption<T> option, T value) {
            Objects.requireNonNull(option, "option");
            Objects.requireNonNull(value, "value");
            socketOptions.put(option, value);
            return this;
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
