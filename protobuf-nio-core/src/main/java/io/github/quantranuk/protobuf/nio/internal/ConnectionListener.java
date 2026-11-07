package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;

/**
 * Events of one {@link Connection}. Exceptions thrown by these methods are logged and ignored.
 * <ul>
 *     <li>Reader thread: {@link #onConnected()}, {@link #onMessage}, {@link #onDisconnected} (in that order, never overlapping).</li>
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

    default void onSendFailure(MessageLite message, SendException cause) {
    }

    /** @param cause {@code null} for {@link DisconnectReason#CLOSED_LOCALLY} and {@link DisconnectReason#CLOSED_BY_PEER} */
    default void onDisconnected(DisconnectReason reason, Throwable cause) {
    }
}
