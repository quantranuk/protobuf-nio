package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.TypeDispatcher;
import com.google.protobuf.MessageLite;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Events of a {@link ProtoClient}. Every method is an optional no-op.
 * <ul>
 *     <li>Reader thread: {@link #onConnected}, {@link #onMessage}, {@link #onDisconnected}, in that order and never
 *     overlapping.</li>
 *     <li>Writer thread: {@link #onSendFailure}, except {@code REJECTED} and {@code DROPPED}, which run on the thread
 *     that called {@code send()}.</li>
 *     <li>{@link #onDisconnected} is the last callback, exactly once, after every {@link #onSendFailure}.</li>
 *     <li>If {@link ProtoClient#connect()} fails, no method is called.</li>
 * </ul>
 * Handlers may block; that only delays this client. An exception thrown by a callback is logged and ignored, and the
 * client carries on. One listener can serve many clients: every callback receives the client.
 */
public interface ProtoClientListener {

    default void onConnected(ProtoClient client) {
    }

    default void onMessage(ProtoClient client, MessageLite message) {
    }

    /** A message passed to {@code send} that wasn't written. */
    default void onSendFailure(ProtoClient client, MessageLite message, SendException cause) {
    }

    /** @param cause {@code null} for {@link DisconnectReason#CLOSED_LOCALLY} and {@link DisconnectReason#CLOSED_BY_PEER} */
    default void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause) {
    }

    static Builder builder() {
        return new Builder();
    }

    /** Lambda-friendly listener with per-type dispatch (exact class match, no allocation per message). */
    final class Builder {
        private Consumer<ProtoClient> onConnected = client -> { };
        private final Map<Class<?>, MessageHandler<?>> handlers = new HashMap<>();
        private MessageHandler<MessageLite> onOtherMessage;
        private SendFailureHandler onSendFailure = (client, message, cause) -> { };
        private DisconnectHandler onDisconnected = (client, reason, cause) -> { };

        private Builder() {
        }

        public Builder onConnected(Consumer<ProtoClient> handler) {
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

        public ProtoClientListener build() {
            Consumer<ProtoClient> connected = onConnected;
            TypeDispatcher<MessageHandler<?>> dispatcher = new TypeDispatcher<>(handlers, onOtherMessage);
            SendFailureHandler sendFailure = onSendFailure;
            DisconnectHandler disconnected = onDisconnected;
            return new ProtoClientListener() {
                @Override
                public void onConnected(ProtoClient client) {
                    connected.accept(client);
                }

                @Override
                @SuppressWarnings("unchecked")
                public void onMessage(ProtoClient client, MessageLite message) {
                    MessageHandler<MessageLite> handler = (MessageHandler<MessageLite>) dispatcher.handlerFor(message);
                    if (handler != null) {
                        handler.onMessage(client, message);
                    }
                }

                @Override
                public void onSendFailure(ProtoClient client, MessageLite message, SendException cause) {
                    sendFailure.onSendFailure(client, message, cause);
                }

                @Override
                public void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause) {
                    disconnected.onDisconnected(client, reason, cause);
                }
            };
        }
    }

    @FunctionalInterface
    interface MessageHandler<T extends MessageLite> {
        void onMessage(ProtoClient client, T message);
    }

    @FunctionalInterface
    interface SendFailureHandler {
        void onSendFailure(ProtoClient client, MessageLite message, SendException cause);
    }

    @FunctionalInterface
    interface DisconnectHandler {
        void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause);
    }
}
