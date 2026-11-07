package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.TypeDispatcher;
import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Events of the connections accepted by a {@link ProtoServer}. Every method is an optional no-op.
 * <ul>
 *     <li>Reader thread of each connection: {@link #onConnected}, {@link #onMessage} and raw handlers,
 *     {@link #onDisconnected}, in that order and never overlapping for one connection.</li>
 *     <li>Writer thread of each connection: {@link #onSendFailure}, except {@code REJECTED} and {@code DROPPED}, which
 *     run on the thread that called {@code send()} or {@code broadcast()}.</li>
 *     <li>{@link #onDisconnected} is a connection's last callback, exactly once, after every {@link #onSendFailure}.</li>
 *     <li>A connection whose handshake fails is never reported.</li>
 * </ul>
 * <b>Callbacks for different connections run concurrently</b>, each on its connection's own virtual thread, so the
 * listener must be thread-safe. Handlers may block; that stalls only their own connection. An exception thrown by a
 * callback is logged and ignored, and the connection carries on.
 */
public interface ProtoServerListener {

    default void onConnected(ProtoConnection connection) {
    }

    default void onMessage(ProtoConnection connection, MessageLite message) {
    }

    /** A message passed to {@code send} that wasn't written. */
    default void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause) {
    }

    /** @param cause {@code null} for {@link DisconnectReason#CLOSED_LOCALLY} and {@link DisconnectReason#CLOSED_BY_PEER} */
    default void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause) {
    }

    static Builder builder() {
        return new Builder();
    }

    /** Lambda-friendly listener with per-type dispatch (exact class match, no allocation per message). */
    final class Builder {
        private Consumer<ProtoConnection> onConnected = connection -> { };
        private final Map<Class<?>, MessageHandler<?>> handlers = new HashMap<>();
        private MessageHandler<MessageLite> onOtherMessage;
        private SendFailureHandler onSendFailure = (connection, message, cause) -> { };
        private DisconnectHandler onDisconnected = (connection, reason, cause) -> { };

        private Builder() {
        }

        public Builder onConnected(Consumer<ProtoConnection> handler) {
            onConnected = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** @throws IllegalArgumentException if a handler for this type is already registered */
        public <T extends MessageLite> Builder onMessage(Class<T> type, MessageHandler<? super T> handler) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(handler, "handler");
            if (handlers.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException("A handler for " + type.getName() + " is already registered");
            }
            return this;
        }

        /** Messages of types without a handler. By default they're logged at WARN once per type and dropped. */
        public Builder onOtherMessage(MessageHandler<MessageLite> handler) {
            onOtherMessage = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Builder onSendFailure(SendFailureHandler handler) {
            onSendFailure = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Builder onDisconnected(DisconnectHandler handler) {
            onDisconnected = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public ProtoServerListener build() {
            Consumer<ProtoConnection> connected = onConnected;
            TypeDispatcher<MessageHandler<?>> dispatcher = new TypeDispatcher<>(handlers, onOtherMessage);
            SendFailureHandler sendFailure = onSendFailure;
            DisconnectHandler disconnected = onDisconnected;
            return new ProtoServerListener() {
                @Override
                public void onConnected(ProtoConnection connection) {
                    connected.accept(connection);
                }

                @Override
                @SuppressWarnings("unchecked")
                public void onMessage(ProtoConnection connection, MessageLite message) {
                    MessageHandler<MessageLite> handler = (MessageHandler<MessageLite>) dispatcher.handlerFor(message);
                    if (handler != null) {
                        handler.onMessage(connection, message);
                    }
                }

                @Override
                public void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause) {
                    sendFailure.onSendFailure(connection, message, cause);
                }

                @Override
                public void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause) {
                    disconnected.onDisconnected(connection, reason, cause);
                }
            };
        }
    }

    @FunctionalInterface
    interface MessageHandler<T extends MessageLite> {
        void onMessage(ProtoConnection connection, T message);
    }

    @FunctionalInterface
    interface SendFailureHandler {
        void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause);
    }

    /**
     * Optional low-garbage path for one message type, registered with {@code ProtoServer.Builder.rawHandler}:
     * messages of that type skip parsing and never reach {@code onMessage}.
     */
    @FunctionalInterface
    interface RawMessageHandler {
        /**
         * Reader thread, like {@code onMessage}.
         * @param payload the unparsed protobuf bytes, from its position to its limit. To avoid allocating per message,
         *                it's a view over the read buffer that's reused: it's valid only during the call, and its
         *                bytes mustn't be modified. It's a heap buffer, so {@code payload.array()},
         *                {@code arrayOffset() + position()} and {@code remaining()} give the bytes without copying,
         *                for example to parse into a reused builder. Copy what you need to keep.
         */
        void onMessage(ProtoConnection connection, ByteBuffer payload);
    }

    @FunctionalInterface
    interface DisconnectHandler {
        void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause);
    }
}
