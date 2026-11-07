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
     * Queues the message. Thread-safe; messages from one thread are sent in order.
     * When the queue is full ({@code maxPendingMessages} or {@code maxPendingBytes}), the {@link OverflowPolicy}
     * decides: {@code BLOCK} waits up to {@code sendTimeout} for room, except on this connection's writer thread (that is,
     * from {@code onSendFailure}), where it rejects at once.
     * Until closing starts, every message passed to send is either written or reported to {@code onSendFailure}: when
     * this returns false because the queue is full, {@code onSendFailure(REJECTED)} has already run on the calling
     * thread, and {@code DROP_OLDEST} reports what it drops as {@code DROPPED}, also on the calling thread.
     * Once closing has started, send returns false without calling the listener ({@code onDisconnected} stays last).
     *
     * @return true if queued; false if not (queue full, or closing or closed). If interrupted while waiting for room,
     *         returns false with the interrupt flag kept.
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
