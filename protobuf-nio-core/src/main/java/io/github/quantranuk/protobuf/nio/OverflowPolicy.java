package io.github.quantranuk.protobuf.nio;

/**
 * What {@code send()} does when the send queue is full ({@code maxPendingMessages} or {@code maxPendingBytes}).
 * Whenever {@code send()} returns {@code false} because of a full queue, the message has been reported as
 * {@code REJECTED} on the calling thread.
 */
public enum OverflowPolicy {
    /**
     * Wait up to {@code sendTimeout} for room; return {@code false} if the queue is still full. Never waits in
     * {@code broadcast()} or on the connection's writer thread, which reject at once instead.
     */
    BLOCK,
    /** Return {@code false} at once. */
    REJECT,
    /**
     * Drop queued messages, oldest first, until the new one fits (each reported as {@code DROPPED}) and return
     * {@code true}. Messages already being written can't be dropped: if that leaves no room, the new message is
     * rejected.
     */
    DROP_OLDEST,
    /** Report the message as {@code REJECTED}, then close at once with {@code SEND_QUEUE_OVERFLOW}, without flushing. */
    DISCONNECT
}
