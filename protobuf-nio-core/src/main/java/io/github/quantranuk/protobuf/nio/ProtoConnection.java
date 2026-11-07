package io.github.quantranuk.protobuf.nio;

import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * One client connection accepted by a {@link ProtoServer}.
 * <p>
 * {@code equals}/{@code hashCode} are identity; {@code toString()} is {@code "ProtoConnection#<id>[<remote address>]"}.
 */
public interface ProtoConnection extends AutoCloseable {

    /** Unique within the JVM and increasing; the sequence is shared with clients. */
    long id();

    InetSocketAddress remoteAddress();

    InetSocketAddress localAddress();

    /** @return true after {@code onConnected}, until closing starts */
    boolean isOpen();

    /** TLS session, or {@code null} without TLS. */
    SSLSession tlsSession();

    /**
     * Queues the message. Thread-safe and non-blocking; messages from one thread are sent in order.
     * Every message queued is either written or reported to {@code onSendFailure}.
     *
     * @return true if queued; false if the connection is closing or closed (the listener isn't called)
     */
    boolean send(MessageLite message);

    /** Messages queued and not yet written. */
    int pendingMessages();

    /** Encoded size of the messages queued and not yet written. */
    long pendingBytes();

    /**
     * Stops accepting sends, writes what's queued for up to {@code closeTimeout}, then closes; messages still queued then
     * fail with {@link SendException.Reason#CLOSED}. Blocks, except when called from this connection's own threads
     * (from its writer thread it returns at once; from its reader thread, for example in {@code onMessage}, it waits only
     * for the queue to be written). Idempotent.
     */
    @Override
    void close();

    /** Blocks until closed and {@code onDisconnected} has returned. @return why it closed */
    DisconnectReason awaitClosed() throws InterruptedException;

    /** Your own per-connection state, not used by the library (for example a session after login). */
    <T> T attachment();

    void attach(Object attachment);
}
