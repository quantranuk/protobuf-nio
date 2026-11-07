package io.github.quantranuk.protobuf.nio;

/**
 * Why a connection closed. Reported once per connection, to {@code onDisconnected} and by {@code awaitClosed()}.
 */
public enum DisconnectReason {
    /** {@code close()} was called on the client, connection or server. */
    CLOSED_LOCALLY,
    /** The peer closed the connection (end of stream). */
    CLOSED_BY_PEER,
    /** Nothing was received for {@code readTimeout}. */
    READ_TIMEOUT,
    /** A write blocked for longer than {@code writeTimeout}, typically because the peer stopped reading. */
    WRITE_TIMEOUT,
    /** The send queue was full under {@link OverflowPolicy#DISCONNECT}. */
    SEND_QUEUE_OVERFLOW,
    /** The peer sent a bad frame, an unknown, disallowed or non-protobuf class name, or a payload that didn't parse. */
    PROTOCOL_ERROR,
    /** The peer's protocol preamble was bad or missing, or the two sides have no protocol version in common. */
    PROTOCOL_MISMATCH,
    /** A TLS failure after connecting. */
    TLS_ERROR,
    /** Any other socket error. */
    IO_ERROR
}
