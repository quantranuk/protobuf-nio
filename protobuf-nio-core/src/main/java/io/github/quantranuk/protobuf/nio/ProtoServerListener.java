package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.internal.TypeDispatcher;
import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Events of the connections accepted by a {@link ProtoServer}. Every method is an optional no-op: implement the ones
 * you need, or build a listener from lambdas with {@link #builder()}.
 * <p>
 * Threads:
 * <ul>
 *     <li>Reader thread of each connection: {@link #onConnected}, {@link #onMessage} and raw handlers,
 *     {@link #onDisconnected}, in that order and never overlapping for one connection.</li>
 *     <li>Writer thread of each connection: {@link #onSendFailure}, except {@code REJECTED} and {@code DROPPED}, which
 *     run on the thread that called {@code send()} or {@code broadcast()}, before it returns.</li>
 *     <li>{@link #onDisconnected} is a connection's last callback, exactly once, after every {@link #onSendFailure}.</li>
 *     <li>A connection whose handshake fails is never reported.</li>
 * </ul>
 * <b>Callbacks for different connections run concurrently</b>, each on its connection's own virtual thread, so the
 * listener must be thread-safe. Handlers may block; that stalls only their own connection. An exception thrown by a
 * callback is logged and ignored, and the connection carries on.
 */
public interface ProtoServerListener {

    /**
     * A connection is open: the TLS handshake (when configured) and the protocol preamble have succeeded. Runs on the
     * connection's reader thread, before any {@link #onMessage}. Just before this runs, the connection joins
     * {@link ProtoServer#connections()}, so it may already receive broadcasts while this runs.
     *
     * @param connection the new connection
     */
    default void onConnected(ProtoConnection connection) {
    }

    /**
     * A message was received and parsed. Runs on the connection's reader thread, in the order the peer sent the
     * messages; the connection's next message isn't read until this returns. Not called for types that have a
     * {@linkplain ProtoServer.Builder#rawHandler raw handler}.
     *
     * @param connection the connection that received the message; reply with {@link ProtoConnection#send}
     * @param message    the parsed message
     */
    default void onMessage(ProtoConnection connection, MessageLite message) {
    }

    /**
     * A message passed to {@link ProtoConnection#send} or {@link ProtoServer#broadcast} wasn't written. Runs on the
     * connection's writer thread, except for {@link SendException.Reason#REJECTED} and
     * {@link SendException.Reason#DROPPED}, which run on the thread that called {@code send()} or {@code broadcast()}.
     *
     * @param connection the connection the message was sent on
     * @param message    the message that wasn't written
     * @param cause      why; {@link SendException#reason()} tells the cases apart
     */
    default void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause) {
    }

    /**
     * A connection closed. Its last callback, called exactly once on its reader thread, after every
     * {@link #onSendFailure}.
     *
     * @param connection the connection that closed
     * @param reason     why it closed
     * @param cause      the exception behind the reason, or {@code null} for {@link DisconnectReason#CLOSED_LOCALLY}
     *                   and {@link DisconnectReason#CLOSED_BY_PEER}
     */
    default void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause) {
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
     * Builds a {@link ProtoServerListener} from lambdas, with per-type message dispatch (exact class match, no
     * allocation per message). Callbacks that aren't set do nothing. The handlers are called for every connection,
     * concurrently, so they must be thread-safe.
     */
    final class Builder {
        private Consumer<ProtoConnection> onConnected = connection -> { };
        private final Map<Class<?>, MessageHandler<?>> handlers = new HashMap<>();
        private MessageHandler<MessageLite> onOtherMessage;
        private SendFailureHandler onSendFailure = (connection, message, cause) -> { };
        private DisconnectHandler onDisconnected = (connection, reason, cause) -> { };

        private Builder() {
        }

        /**
         * Sets {@link ProtoServerListener#onConnected}.
         *
         * @param handler called with each connection once it's open
         * @return this builder
         */
        public Builder onConnected(Consumer<ProtoConnection> handler) {
            onConnected = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Handles messages whose class is exactly {@code type} (subclasses and other types don't match).
         *
         * @param type    the generated message class
         * @param handler called on the connection's reader thread for each message of that type
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
         * @param handler called on the connection's reader thread for each such message
         * @return this builder
         */
        public Builder onOtherMessage(MessageHandler<MessageLite> handler) {
            onOtherMessage = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Sets {@link ProtoServerListener#onSendFailure}.
         *
         * @param handler called for each message that wasn't written
         * @return this builder
         */
        public Builder onSendFailure(SendFailureHandler handler) {
            onSendFailure = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Sets {@link ProtoServerListener#onDisconnected}.
         *
         * @param handler called once when each connection closes
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

    /**
     * Handles received messages of one type; see {@link Builder#onMessage}.
     *
     * @param <T> the message type
     */
    @FunctionalInterface
    interface MessageHandler<T extends MessageLite> {
        /**
         * Called on the connection's reader thread, like {@link ProtoServerListener#onMessage}.
         *
         * @param connection the connection that received the message
         * @param message    the parsed message
         */
        void onMessage(ProtoConnection connection, T message);
    }

    /** Lambda form of {@link ProtoServerListener#onSendFailure}. */
    @FunctionalInterface
    interface SendFailureHandler {
        /**
         * See {@link ProtoServerListener#onSendFailure}.
         *
         * @param connection the connection the message was sent on
         * @param message    the message that wasn't written
         * @param cause      why
         */
        void onSendFailure(ProtoConnection connection, MessageLite message, SendException cause);
    }

    /**
     * Optional low-garbage path for one message type, registered with {@link ProtoServer.Builder#rawHandler}:
     * messages of that type skip parsing and never reach {@code onMessage}.
     */
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

    /** Lambda form of {@link ProtoServerListener#onDisconnected}. */
    @FunctionalInterface
    interface DisconnectHandler {
        /**
         * See {@link ProtoServerListener#onDisconnected}.
         *
         * @param connection the connection that closed
         * @param reason     why it closed
         * @param cause      the exception behind the reason, or {@code null} for a normal close
         */
        void onDisconnected(ProtoConnection connection, DisconnectReason reason, Throwable cause);
    }
}
