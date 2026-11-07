package io.github.quantranuk.protobuf.nio;

/** What {@code send()} does when the send queue is full. */
public enum OverflowPolicy {
    /** Wait up to {@code sendTimeout} for room; return {@code false} if the queue is still full. */
    BLOCK,
    /** Return {@code false} at once. */
    REJECT,
    /** Drop the oldest queued message to make room (reported as {@code DROPPED}) and return {@code true}. */
    DROP_OLDEST,
    /** Report the message as {@code REJECTED}, then close at once with {@code SEND_QUEUE_OVERFLOW}, without flushing. */
    DISCONNECT
}
