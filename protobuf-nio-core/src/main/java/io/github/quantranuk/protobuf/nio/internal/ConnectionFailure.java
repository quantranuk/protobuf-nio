package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;

import java.io.IOException;

/**
 * A failure detected by the library itself (bad frame or preamble, timeout), with the reason the connection closes.
 */
public final class ConnectionFailure extends IOException {

    private final DisconnectReason reason;

    public ConnectionFailure(DisconnectReason reason, String message) {
        this(reason, message, null);
    }

    public ConnectionFailure(DisconnectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public DisconnectReason reason() {
        return reason;
    }
}
