package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.TypeDispatcher;
import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Events of a {@link ProtoClient}. Every method is an optional no-op: implement the ones you need, or build a listener
 * from lambdas with {@link #builder()}.
 * <p>
 * Threads:
 * <ul>
 *     <li>Reader thread: {@link #onConnected}, {@link #onMessage} and raw handlers, {@link #onDisconnected}, in that
 *     order and never overlapping.</li>
 *     <li>Writer thread: {@link #onSendFailure}, except {@code REJECTED} and {@code DROPPED}, which run on the thread
 *     that called {@code send()}, before it returns.</li>
 *     <li>{@link #onDisconnected} is the last callback, exactly once, after every {@link #onSendFailure}.</li>
 *     <li>If {@link ProtoClient#connect()} fails, no method is called.</li>
 * </ul>
 * Handlers may block; that only delays this client. An exception thrown by a callback is logged and ignored, and the
 * client carries on. One listener can serve many clients, since every callback receives the client, but callbacks for
 * different clients then run concurrently, so the listener must be thread-safe.
 */
public interface ProtoClientListener {

    /**
     * The client is open: the TCP connect, the TLS handshake (when configured) and the protocol preamble have
     * succeeded. Runs on the reader thread, before any {@link #onMessage}; {@link ProtoClient#connect()} may return
     * before or while this runs.
     *
     * @param client the client that connected
     */
    default void onConnected(ProtoClient client) {
    }

    /**
     * A message was received and parsed. Runs on the reader thread, in the order the peer sent the messages; the next
     * message isn't read until this returns. Not called for types that have a
     * {@linkplain ProtoClient.Builder#rawHandler raw handler}.
     *
     * @param client  the client that received the message
     * @param message the parsed message
     */
    default void onMessage(ProtoClient client, MessageLite message) {
    }

    /**
     * A message passed to {@link ProtoClient#send} wasn't written. Runs on the writer thread, except for
     * {@link SendException.Reason#REJECTED} and {@link SendException.Reason#DROPPED}, which run on the thread that
     * called {@code send()}.
     *
     * @param client  the client the message was sent on
     * @param message the message that wasn't written
     * @param cause   why; {@link SendException#reason()} tells the cases apart
     */
    default void onSendFailure(ProtoClient client, MessageLite message, SendException cause) {
    }

    /**
     * The client closed. The last callback, called exactly once on the reader thread, after every
     * {@link #onSendFailure}. Not called if {@link ProtoClient#connect()} failed.
     *
     * @param client the client that closed
     * @param reason why it closed
     * @param cause  the exception behind the reason, or {@code null} for {@link DisconnectReason#CLOSED_LOCALLY} and
     *               {@link DisconnectReason#CLOSED_BY_PEER}
     */
    default void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause) {
    }

    /**
     * Starts a listener built from lambdas, with messages dispatched by type.
     *
     * @return a new builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link ProtoClientListener} from lambdas, with per-type message dispatch (exact class match, no
     * allocation per message). Callbacks that aren't set do nothing.
     */
    final class Builder {
        private Consumer<ProtoClient> onConnected = client -> { };
        private final Map<Class<?>, MessageHandler<?>> handlers = new HashMap<>();
        private MessageHandler<MessageLite> onOtherMessage;
        private SendFailureHandler onSendFailure = (client, message, cause) -> { };
        private DisconnectHandler onDisconnected = (client, reason, cause) -> { };

        private Builder() {
        }

        /**
         * Sets {@link ProtoClientListener#onConnected}.
         *
         * @param handler called with the client once it's open
         * @return this builder
         */
        public Builder onConnected(Consumer<ProtoClient> handler) {
            onConnected = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Handles messages whose class is exactly {@code type} (subclasses and other types don't match).
         *
         * @param type    the generated message class
         * @param handler called on the reader thread for each message of that type
         * @param <T>     the message type
         * @return this builder
         * @throws IllegalArgumentException if a handler for this type is already registered
         */
        public <T extends MessageLite> Builder onMessage(Class<T> type, MessageHandler<? super T> handler) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(handler, "handler");
            if (handlers.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException("A handler for " + type.getName() + " is already registered");
            }
            return this;
        }

        /**
         * Handles messages of types without their own handler. Without it, they're logged at WARN once per type and
         * dropped.
         *
         * @param handler called on the reader thread for each such message
         * @return this builder
         */
        public Builder onOtherMessage(MessageHandler<MessageLite> handler) {
            onOtherMessage = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Sets {@link ProtoClientListener#onSendFailure}.
         *
         * @param handler called for each message that wasn't written
         * @return this builder
         */
        public Builder onSendFailure(SendFailureHandler handler) {
            onSendFailure = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Sets {@link ProtoClientListener#onDisconnected}.
         *
         * @param handler called once when the client closes
         * @return this builder
         */
        public Builder onDisconnected(DisconnectHandler handler) {
            onDisconnected = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Builds the listener. The builder can be changed and reused afterwards without affecting it.
         *
         * @return a new listener
         */
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

    /**
     * Handles received messages of one type; see {@link Builder#onMessage}.
     *
     * @param <T> the message type
     */
    @FunctionalInterface
    interface MessageHandler<T extends MessageLite> {
        /**
         * Called on the reader thread, like {@link ProtoClientListener#onMessage}.
         *
         * @param client  the client that received the message
         * @param message the parsed message
         */
        void onMessage(ProtoClient client, T message);
    }

    /** Lambda form of {@link ProtoClientListener#onSendFailure}. */
    @FunctionalInterface
    interface SendFailureHandler {
        /**
         * See {@link ProtoClientListener#onSendFailure}.
         *
         * @param client  the client the message was sent on
         * @param message the message that wasn't written
         * @param cause   why
         */
        void onSendFailure(ProtoClient client, MessageLite message, SendException cause);
    }

    /**
     * Optional low-garbage path for one message type, registered with {@link ProtoClient.Builder#rawHandler}:
     * messages of that type skip parsing and never reach {@code onMessage}.
     */
    @FunctionalInterface
    interface RawMessageHandler {
        /**
         * Called on the reader thread, like {@code onMessage}.
         *
         * @param client  the client that received the message
         * @param payload the unparsed protobuf bytes, from its position to its limit. To avoid allocating per message,
         *                it's a view over the read buffer that's reused: it's valid only during the call, and its
         *                bytes mustn't be modified. It's a heap buffer, so {@code payload.array()},
         *                {@code arrayOffset() + position()} and {@code remaining()} give the bytes without copying,
         *                for example to parse into a reused builder. Copy what you need to keep.
         */
        void onMessage(ProtoClient client, ByteBuffer payload);
    }

    /** Lambda form of {@link ProtoClientListener#onDisconnected}. */
    @FunctionalInterface
    interface DisconnectHandler {
        /**
         * See {@link ProtoClientListener#onDisconnected}.
         *
         * @param client the client that closed
         * @param reason why it closed
         * @param cause  the exception behind the reason, or {@code null} for a normal close
         */
        void onDisconnected(ProtoClient client, DisconnectReason reason, Throwable cause);
    }
}
