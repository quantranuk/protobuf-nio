package io.github.quantranuk.protobuf.nio;

import java.io.Serial;
import java.util.Objects;

/**
 * Passed to {@code onSendFailure} for a message that wasn't written. The cause is the I/O error for
 * {@link Reason#WRITE_FAILED} and {@code null} otherwise.
 * <p>
 * It has no stack trace, and one instance may be shared by all the messages that failed for the same cause (for example
 * everything still queued when a connection closed), so failing many messages stays cheap.
 */
public final class SendException extends RuntimeException {

    /** Why a message wasn't written. */
    public enum Reason {
        /** {@code send()} returned {@code false} because the queue was full. */
        REJECTED,
        /** The connection closed before the message was written. */
        CLOSED,
        /** The message was evicted from the queue by {@link OverflowPolicy#DROP_OLDEST}. */
        DROPPED,
        /** The socket write failed. */
        WRITE_FAILED
    }

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the message wasn't written. */
    private final Reason reason;

    /**
     * Creates the exception. The library creates these itself; the constructor is public for tests of your own
     * callbacks.
     *
     * @param reason why the message wasn't written
     * @param cause  the I/O error for {@link Reason#WRITE_FAILED}, otherwise usually {@code null}
     */
    public SendException(Reason reason, Throwable cause) {
        super(describe(Objects.requireNonNull(reason, "reason"), cause), cause, false, false);
        this.reason = reason;
    }

    /**
     * Tells the failure cases apart.
     *
     * @return why the message wasn't written
     */
    public Reason reason() {
        return reason;
    }

    private static String describe(Reason reason, Throwable cause) {
        return cause == null ? reason.name() : reason.name() + ": " + cause;
    }
}
