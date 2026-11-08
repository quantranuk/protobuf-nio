package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.OverflowPolicy;
import io.github.quantranuk.protobuf.nio.TlsClientAuth;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import javax.net.ssl.SSLContext;

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
    final SSLContext sslContext;         // null = no TLS
    final boolean tlsHostnameVerification;
    final TlsClientAuth tlsClientAuth;

    /**
     * A wait with no limit: {@link Duration#ZERO} for the handshake, close and send timeouts. About 73 years, so that
     * deadlines can be computed and compared with {@code System.nanoTime()} without special cases.
     */
    static final long NO_LIMIT_NANOS = Long.MAX_VALUE / 4;

    private ConnectionConfig(Builder b, Map<ByteBuffer, Parser<? extends MessageLite>> allowedTypes) {
        // Duration.ZERO means "no limit" for every timeout. The read and write timeouts and the heartbeat interval keep 0
        // ("none") and are checked with > 0; the waits below become NO_LIMIT_NANOS instead.
        handshakeTimeoutNanos = waitNanos(b.handshakeTimeout);
        readTimeoutNanos = nanos(b.readTimeout);
        heartbeatIntervalNanos = nanos(b.heartbeatInterval);
        writeTimeoutNanos = nanos(b.writeTimeout);
        closeTimeoutNanos = waitNanos(b.closeTimeout);
        idleShrinkDelayNanos = nanos(b.idleShrinkDelay);
        readBufferSize = b.readBufferSize;
        writeBufferSize = b.writeBufferSize;
        maxMessageSize = b.maxMessageSize;
        maxPendingBytes = b.maxPendingBytes;
        maxPendingMessages = b.maxPendingMessages;
        overflowPolicy = b.overflowPolicy;
        sendTimeoutNanos = waitNanos(b.sendTimeout);
        socketOptions = Collections.unmodifiableMap(new LinkedHashMap<>(b.socketOptions));
        this.allowedTypes = allowedTypes;
        rawHandlers = Map.copyOf(b.rawHandlers);
        classLoader = b.classLoader != null ? b.classLoader : defaultClassLoader();
        threadFactory = b.threadFactory;
        sslContext = b.sslContext;
        tlsHostnameVerification = b.tlsHostnameVerification;
        tlsClientAuth = b.tlsClientAuth;
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
    /** @return the duration in nanoseconds, saturated at {@link #NO_LIMIT_NANOS} (toNanos() throws above 292 years) */
    private static long nanos(Duration duration) {
        return duration.compareTo(Duration.ofNanos(NO_LIMIT_NANOS)) >= 0 ? NO_LIMIT_NANOS : duration.toNanos();
    }

    /** @return {@link #nanos} for a wait, with {@link Duration#ZERO} meaning no limit */
    private static long waitNanos(Duration duration) {
        return duration.isZero() ? NO_LIMIT_NANOS : nanos(duration);
    }

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
        private SSLContext sslContext;
        private boolean tlsHostnameVerification = true;
        private TlsClientAuth tlsClientAuth = TlsClientAuth.NONE;

        private Builder() {
        }

        /** TLS handshake plus protocol preamble; {@link Duration#ZERO} = no limit. */
        public Builder handshakeTimeout(Duration timeout) {
            handshakeTimeout = nonNegative(timeout, "handshakeTimeout");
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

        /** {@link Duration#ZERO} = no limit: wait as long as the flush and the peer's close take. */
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

        /** How long {@link OverflowPolicy#BLOCK} waits for room; {@link Duration#ZERO} = no limit. */
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
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Unable to get the parser of " + type.getName() + ": " + e.getMessage(), e);
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

        /** {@code null} = no TLS. */
        public Builder tls(SSLContext context) {
            sslContext = context;
            return this;
        }

        /** Client side, with TLS: check that the server's certificate matches the host name. Default true. */
        public Builder tlsHostnameVerification(boolean enabled) {
            tlsHostnameVerification = enabled;
            return this;
        }

        /** Server side, with TLS: whether clients must present a certificate. Default {@link TlsClientAuth#NONE}. */
        public Builder tlsClientAuth(TlsClientAuth mode) {
            tlsClientAuth = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public ConnectionConfig build() {
            return new ConnectionConfig(this, allowedTypes);
        }

        /**
         * Builds the configuration of a server or client, checked against the types that have an {@code onMessage}
         * handler, so that a handler can't be silently bypassed: a type can't also have a raw handler (which would take
         * all its messages), and with an allowlist every handled type must be on it (otherwise each such message would
         * disconnect the peer). This builder isn't changed, so it stays reusable.
         *
         * @param handledTypes           the types with an {@code onMessage} handler
         * @param onlyHandledTypes       accept only the handled types (and the raw-handler types, always accepted): the
         *                               allowlist is derived from the handlers
         * @param hasOtherMessageHandler whether {@code onOtherMessage} is set
         * @throws IllegalArgumentException if the handlers and the settings contradict each other
         */
        public ConnectionConfig build(Collection<Class<? extends MessageLite>> handledTypes, boolean onlyHandledTypes,
                                      boolean hasOtherMessageHandler) {
            for (Class<? extends MessageLite> type : handledTypes) {
                ByteBuffer name = encodedName(type);
                if (rawHandlers.containsKey(name)) {
                    throw new IllegalArgumentException(type.getName() + " has both an onMessage and an onRawMessage handler;"
                            + " the raw handler would receive every message of that type");
                }
                if (allowedTypes != null && !allowedTypes.containsKey(name)) {
                    throw new IllegalArgumentException(type.getName() + " has an onMessage handler but isn't in"
                            + " allowedMessageTypes, so every message of that type would disconnect the peer");
                }
            }
            if (!onlyHandledTypes) {
                return build();
            }
            if (allowedTypes != null) {
                throw new IllegalArgumentException("allowOnlyHandledTypes() and allowedMessageTypes() can't both be set");
            }
            if (hasOtherMessageHandler) {
                throw new IllegalArgumentException("onOtherMessage would never be called with allowOnlyHandledTypes()");
            }
            if (handledTypes.isEmpty() && rawHandlers.isEmpty()) {
                throw new IllegalArgumentException("allowOnlyHandledTypes() needs at least one onMessage or onRawMessage handler");
            }
            Map<ByteBuffer, Parser<? extends MessageLite>> allowed = new HashMap<>();
            for (Class<? extends MessageLite> type : handledTypes) {
                allowed.put(encodedName(type), parserOf(type));
            }
            return new ConnectionConfig(this, Map.copyOf(allowed));
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
