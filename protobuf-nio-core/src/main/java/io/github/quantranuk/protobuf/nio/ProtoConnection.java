package io.github.quantranuk.protobuf.nio;

import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * One client connection accepted by a {@link ProtoServer}. It's passed to every callback set on the server's builder
 * and listed by {@link ProtoServer#connections()}. All methods are thread-safe.
 * <p>
 * {@code equals}/{@code hashCode} are identity; {@code toString()} is {@code "ProtoConnection#<id>[<remote address>]"}.
 */
public interface ProtoConnection extends AutoCloseable {

    /**
     * Identifies the connection, for example in logs.
     *
     * @return an id unique within the JVM and increasing; the sequence is shared with clients
     */
    long id();

    /**
     * The client's address.
     *
     * @return the remote address of the socket
     */
    InetSocketAddress remoteAddress();

    /**
     * The server's address for this connection.
     *
     * @return the local address of the socket
     */
    InetSocketAddress localAddress();

    /**
     * Whether messages can be sent.
     *
     * @return true after {@code onConnected}, until closing starts
     */
    boolean isOpen();

    /**
     * The TLS session, with the negotiated protocol, cipher suite and the client's certificates (if it presented any).
     *
     * @return the TLS session, or {@code null} without TLS
     */
    SSLSession tlsSession();

    /**
     * Queues the message, to be written by this connection's writer thread. Thread-safe; messages from one thread are
     * sent in order.
     * <p>
     * When the queue is full ({@code maxPendingMessages} or {@code maxPendingBytes}), the {@link OverflowPolicy}
     * decides: {@code BLOCK} waits up to {@code sendTimeout} for room, except on this connection's writer thread (that is,
     * from {@code onSendFailure}), where it rejects at once.
     * Until closing starts, every message passed to send is either written or reported to {@code onSendFailure}: when
     * this returns false because the queue is full, {@code onSendFailure(REJECTED)} has already run on the calling
     * thread, and {@code DROP_OLDEST} reports what it drops as {@code DROPPED}, also on the calling thread.
     * Once closing has started, send returns false without any callback ({@code onDisconnected} stays last).
     *
     * @param message the message to send
     * @return true if queued; false if not (queue full, or closing or closed). If interrupted while waiting for room,
     *         returns false with the interrupt flag kept.
     * @throws NullPointerException if {@code message} is null
     */
    boolean send(MessageLite message);

    /**
     * How far behind the connection is, in messages.
     *
     * @return the number of messages queued and not yet written
     */
    int pendingMessages();

    /**
     * How far behind the connection is, in bytes.
     *
     * @return the encoded size of the messages queued and not yet written
     */
    long pendingBytes();

    /**
     * Stops accepting sends, writes what's queued for up to {@code closeTimeout}, then closes; messages still queued then
     * fail with {@link SendException.Reason#CLOSED}. Blocks, except when called from this connection's own threads
     * (from its writer thread it returns at once; from its reader thread, for example in {@code onMessage}, it waits only
     * for the queue to be written). Idempotent.
     */
    @Override
    void close();

    /**
     * Blocks until the connection has closed and {@code onDisconnected} has returned.
     *
     * @return why it closed
     * @throws InterruptedException if interrupted while waiting
     */
    DisconnectReason awaitClosed() throws InterruptedException;

    /**
     * Your own per-connection state, not used by the library (for example a session after login). Visible to every
     * thread once {@link #attach} has returned.
     *
     * @param <T> the type to cast the attachment to
     * @return the last object passed to {@link #attach}, or {@code null}
     * @throws ClassCastException if the attachment isn't a {@code T}, when the result is assigned
     */
    <T> T attachment();

    /**
     * Sets the object returned by {@link #attachment()}, replacing the previous one.
     *
     * @param attachment any object, or {@code null}
     */
    void attach(Object attachment);
}
