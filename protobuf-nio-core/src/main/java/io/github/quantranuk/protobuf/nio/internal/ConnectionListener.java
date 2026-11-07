package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;

import java.nio.ByteBuffer;

/**
 * Events of one {@link Connection}. Exceptions thrown by these methods are logged and ignored.
 * <ul>
 *     <li>Reader thread: {@link #onConnected()}, {@link #onMessage} and {@link #onRawMessage}, {@link #onDisconnected} (in that order, never overlapping).</li>
 *     <li>Writer thread: {@link #onSendFailure}, except for messages that raced with the connection closing, which are
 *     failed on the reader thread just before {@link #onDisconnected}.</li>
 *     <li>{@link #onDisconnected} is the last call, exactly once, after every {@link #onSendFailure}.</li>
 *     <li>If the handshake fails, no method is called at all.</li>
 * </ul>
 */
public interface ConnectionListener {

    default void onConnected() {
    }

    default void onMessage(MessageLite message) {
    }

    /**
     * A message of a type with a raw handler, instead of {@link #onMessage}. Reader thread.
     * @param handler the handler registered for the type ({@code ConnectionConfig.Builder.rawHandler})
     * @param payload the unparsed protobuf bytes, from position to limit; a view that's reused, valid only during the call
     */
    default void onRawMessage(Object handler, ByteBuffer payload) {
    }

    default void onSendFailure(MessageLite message, SendException cause) {
    }

    /** @param cause {@code null} for {@link DisconnectReason#CLOSED_LOCALLY} and {@link DisconnectReason#CLOSED_BY_PEER} */
    default void onDisconnected(DisconnectReason reason, Throwable cause) {
    }
}
