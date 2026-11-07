package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * One protobuf-nio connection over a connected blocking {@link Socket}, client or server side.
 * <p>
 * <b>Threads.</b> A reader thread reads the peer's preamble, then frames, and calls the listener. A writer thread writes
 * this side's preamble, then drains the send queue in batches (one socket write per batch). Both are virtual threads
 * unless a thread factory is configured. Reader state is touched only by the reader, writer state only by the writer;
 * what's shared is the send queue, the counters, the state and the termination.
 * <p>
 * <b>Handshake.</b> Each side sends its preamble first and may send frames straight away. The reader checks the peer's
 * preamble within the handshake timeout, then reports {@code onConnected}. If the handshake fails, the listener is never
 * called and {@link #handshake()} fails.
 * <p>
 * <b>Closing.</b> {@link #close()} stops accepting sends, lets the writer flush the queue, shuts down the output (FIN)
 * and waits for the peer to close its side, all within the close timeout; after that the socket is closed regardless.
 * Waiting for the peer's FIN avoids a TCP reset that could make the peer lose data it hasn't read yet. Any failure
 * (end of stream, bad frame, timeout, I/O error) closes the socket at once. The first reason recorded wins; the reader
 * then waits for the writer, fails what's left in the queue, and reports {@code onDisconnected} last.
 * <p>
 * <b>Memory.</b> The read buffer starts at 64 bytes, grows from 8 KB up to the read buffer size only while reads fill it,
 * and goes back to 64 bytes after the idle shrink delay. The write buffer is allocated on the first batch and released
 * after the same delay. Blocking writes have no timeout in the JDK, so the {@link Watchdog} enforces the write timeout.
 */
public final class Connection {

    private static final Logger LOG = LoggerFactory.getLogger(Connection.class);
    private static final AtomicLong IDS = new AtomicLong();
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    static final int INITIAL_BUFFER_SIZE = 8192;
    static final int IDLE_READ_BUFFER_SIZE = 64;
    private static final int PARSER_CACHE_SIZE = 8;
    private static final int HEADER_LENGTH = ProtobufSerializer.HEADER_LENGTH;

    private static final int HANDSHAKING = 0;
    private static final int OPEN = 1;
    private static final int CLOSING = 2;
    private static final int CLOSED = 3;

    private record Termination(DisconnectReason reason, Throwable cause) {
    }

    private static final Termination CLOSED_LOCALLY = new Termination(DisconnectReason.CLOSED_LOCALLY, null);
    private static final Termination CLOSED_BY_PEER = new Termination(DisconnectReason.CLOSED_BY_PEER, null);
    private static final SendException CLOSED_FAILURE = new SendException(SendException.Reason.CLOSED, null);

    private final long id;
    private final Socket socket;
    private final ConnectionConfig config;
    private final ConnectionListener listener;
    private final MessageTypes messageTypes;
    private final Thread reader;
    private final Thread writer;

    private final AtomicInteger state = new AtomicInteger(HANDSHAKING);
    private final AtomicReference<Termination> termination = new AtomicReference<>();
    private final CompletableFuture<Void> handshake = new CompletableFuture<>();
    private final CompletableFuture<DisconnectReason> closed = new CompletableFuture<>();
    private final CountDownLatch socketClosed = new CountDownLatch(1);
    private final CountDownLatch writerDone = new CountDownLatch(1);

    // Send queue of MessageLite or EncodedFrame: any thread adds, the writer removes
    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingMessages = new AtomicInteger();
    private final AtomicLong pendingBytes = new AtomicLong();
    private volatile boolean writerParked;
    private volatile long messagesWritten;

    // Deadlines enforced by the watchdog
    private volatile long writeStartedNanos;
    private volatile boolean writing;
    private volatile long closeDeadlineNanos;
    private volatile boolean closeDeadlineArmed;

    // Reader thread only
    private byte[] readBuffer = new byte[IDLE_READ_BUFFER_SIZE];
    private int readStart;
    private int readEnd;
    private boolean growReadBuffer;
    private boolean handshaking = true;
    private long handshakeDeadlineNanos;
    private long lastReceivedNanos;
    private int soTimeoutMillis = -1;
    private final byte[][] cachedNames = new byte[PARSER_CACHE_SIZE][];
    private final Parser<?>[] cachedParsers = new Parser<?>[PARSER_CACHE_SIZE];
    private int nextCacheSlot;

    // Writer thread only
    private byte[] writeBuffer;
    private boolean growWriteBuffer;
    private final ArrayList<Object> inFlight = new ArrayList<>();
    private long inFlightBytes;
    private long lastWrittenNanos;
    private long lastBatchNanos;

    /**
     * @param socket   a connected socket; owned by this connection from now on
     * @param config   settings
     * @param listener events of this connection
     */
    public Connection(Socket socket, ConnectionConfig config, ConnectionListener listener) {
        this(nextId(), socket, config, listener);
    }

    /**
     * @param id       from {@link #nextId()}
     * @param socket   a socket, connected before {@link #start()}; owned by this connection from now on
     * @param config   settings
     * @param listener events of this connection
     */
    public Connection(long id, Socket socket, ConnectionConfig config, ConnectionListener listener) {
        this.id = id;
        this.socket = Objects.requireNonNull(socket, "socket");
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.messageTypes = MessageTypes.forClassLoader(config.classLoader);
        this.reader = newThread(this::readLoop, "reader");
        this.writer = newThread(this::writeLoop, "writer");
    }

    /** Next value of the id sequence shared by every client and connection in the JVM. */
    public static long nextId() {
        return IDS.incrementAndGet();
    }

    /**
     * Applies the options the socket supports (a server's options may include server-socket-only ones) and ignores the
     * others.
     */
    public static void applySocketOptions(Socket socket, Map<SocketOption<?>, Object> options) throws IOException {
        Set<SocketOption<?>> supported = socket.supportedOptions();
        for (Map.Entry<SocketOption<?>, Object> option : options.entrySet()) {
            if (supported.contains(option.getKey())) {
                setOption(socket, option.getKey(), option.getValue());
            }
        }
    }

    /**
     * Applies the socket options and starts the reader and writer threads.
     * @throws IOException if the connection was closed before starting, or a socket option can't be set; the
     *                     connection is then {@linkplain #abort aborted}
     */
    public void start() throws IOException {
        try {
            if (state.get() == CLOSED) {
                throw new SocketException("Closed before the connection started");
            }
            applySocketOptions(socket, config.socketOptions);
        } catch (IOException | RuntimeException e) {
            abort(e);
            throw e;
        }
        long now = System.nanoTime();
        handshakeDeadlineNanos = now + config.handshakeTimeoutNanos;
        lastReceivedNanos = now;
        lastWrittenNanos = now;
        lastBatchNanos = now;
        Watchdog.INSTANCE.register(this, shortestDeadlineNanos());
        writer.start();
        reader.start();
    }

    public long id() {
        return id;
    }

    public Socket socket() {
        return socket;
    }

    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    /** @return true after the handshake, until closing starts */
    public boolean isOpen() {
        return state.get() == OPEN;
    }

    /** Completes when the peer's preamble has been accepted (before {@code onConnected}), or fails with an IOException. */
    public CompletableFuture<Void> handshake() {
        return handshake;
    }

    /** Completes with the reason after {@code onDisconnected} has returned (or after a failed handshake). */
    public CompletableFuture<DisconnectReason> closedFuture() {
        return closed;
    }

    public boolean isWriterThread() {
        return Thread.currentThread() == writer;
    }

    public boolean isReaderThread() {
        return Thread.currentThread() == reader;
    }

    public int pendingMessages() {
        return pendingMessages.get();
    }

    public long pendingBytes() {
        return pendingBytes.get();
    }

    /** Messages written to the socket so far. */
    public long messagesWritten() {
        return messagesWritten;
    }

    /**
     * Queues a message. Thread-safe; messages from one thread are written in order. Messages sent during the handshake
     * are written after this side's preamble.
     * @return false if the connection is closing or closed
     */
    public boolean send(MessageLite message) {
        Objects.requireNonNull(message, "message");
        return enqueue(message, ProtobufSerializer.getSerializedSize(message));
    }

    /** Same as {@link #send(MessageLite)}, for a message encoded once for several connections. */
    public boolean send(EncodedFrame frame) {
        return enqueue(frame, frame.frame().length);
    }

    private boolean enqueue(Object item, int size) {
        int s = state.get();
        if (s != OPEN && s != HANDSHAKING) {
            return false;
        }
        pendingMessages.incrementAndGet();
        pendingBytes.addAndGet(size);
        queue.offer(item);
        if (writerParked) {
            LockSupport.unpark(writer);
        }
        return true;
    }

    /**
     * Stops accepting sends, writes what's queued and closes, within the close timeout; then closes regardless, and
     * messages still queued fail with {@code CLOSED}. During the handshake, or with a zero close timeout, closes at once.
     * Idempotent.
     * <p>
     * Blocks until the socket is closed, except on this connection's own threads: on the writer thread it returns at
     * once, and on the reader thread it waits only for the flush (the reader is the one that sees the peer's FIN).
     */
    public void close() {
        if (config.closeTimeoutNanos == 0 || state.get() == HANDSHAKING) {
            terminate(CLOSED_LOCALLY);
            return;
        }
        if (state.compareAndSet(OPEN, CLOSING)) {
            termination.compareAndSet(null, CLOSED_LOCALLY);
            closeDeadlineNanos = System.nanoTime() + config.closeTimeoutNanos;
            closeDeadlineArmed = true;
            LockSupport.unpark(writer);
        } else {
            while (!closeDeadlineArmed && state.get() == CLOSING) {
                Thread.onSpinWait();   // another thread is arming the deadline
            }
        }
        if (isWriterThread()) {
            return;
        }
        if (!closeDeadlineArmed) {
            return;   // closed by a failure: the socket is already closed
        }
        CountDownLatch done = isReaderThread() ? writerDone : socketClosed;
        if (!awaitUntil(done, closeDeadlineNanos)) {
            terminate(CLOSED_LOCALLY);
        }
    }

    /**
     * Closes a connection whose {@link #start()} was never called or failed: the handshake fails, queued messages are
     * dropped without listener calls, and {@link #closedFuture()} completes. Idempotent.
     */
    public void abort(Throwable cause) {
        terminate(new Termination(DisconnectReason.IO_ERROR, cause));
        Termination t = termination.get();
        handshake.completeExceptionally(handshakeFailure(t));
        Watchdog.INSTANCE.unregister(this);
        failQueued();
        closed.complete(t.reason());
    }

    /** Blocks until closed and {@code onDisconnected} has returned. */
    public DisconnectReason awaitClosed() throws InterruptedException {
        try {
            return closed.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "Connection#" + id + "[" + socket.getRemoteSocketAddress() + "]";
    }

    // ------------------------------------------------------------------ termination and deadlines

    /** Records the reason (first one wins) and closes the socket at once. Safe from any thread, idempotent. */
    private void terminate(Termination t) {
        termination.compareAndSet(null, t);
        if (state.getAndSet(CLOSED) != CLOSED) {
            try {
                socket.close();
            } catch (IOException e) {
                LOG.debug("{}: error closing the socket", this, e);
            }
            socketClosed.countDown();
            LockSupport.unpark(writer);
        }
    }

    /** Called periodically by the watchdog. */
    void checkDeadlines(long now) {
        if (writing && config.writeTimeoutNanos > 0 && now - writeStartedNanos > config.writeTimeoutNanos) {
            terminate(new Termination(DisconnectReason.WRITE_TIMEOUT, new SocketTimeoutException(
                    "A write blocked for more than " + TimeUnit.NANOSECONDS.toMillis(config.writeTimeoutNanos) + " ms")));
        }
        if (closeDeadlineArmed && now - closeDeadlineNanos >= 0) {
            terminate(CLOSED_LOCALLY);
        }
    }

    private long shortestDeadlineNanos() {
        long shortest = Long.MAX_VALUE;
        if (config.writeTimeoutNanos > 0) {
            shortest = config.writeTimeoutNanos;
        }
        if (config.closeTimeoutNanos > 0) {
            shortest = Math.min(shortest, config.closeTimeoutNanos);
        }
        return shortest == Long.MAX_VALUE ? 0 : shortest;
    }

    // ------------------------------------------------------------------ reader thread

    private void readLoop() {
        boolean connected = false;
        try {
            InputStream in = socket.getInputStream();
            while (readEnd - readStart < Protocol.PREAMBLE_LENGTH) {
                if (!fill(in, Protocol.PREAMBLE_LENGTH)) {
                    terminate(CLOSED_BY_PEER);
                    return;
                }
            }
            Protocol.negotiate(readBuffer, readStart);
            readStart += Protocol.PREAMBLE_LENGTH;
            handshaking = false;
            if (!state.compareAndSet(HANDSHAKING, OPEN)) {
                return;   // closed during the handshake
            }
            connected = true;
            handshake.complete(null);
            notifyConnected();
            readFrames(in);
            terminate(CLOSED_BY_PEER);
        } catch (ProtocolException e) {
            // Bad preamble, or bad frame after it
            terminate(new Termination(handshaking ? DisconnectReason.PROTOCOL_MISMATCH : DisconnectReason.PROTOCOL_ERROR, e));
        } catch (SocketTimeoutException e) {
            // Raised by onReadTimeout (socket read timeouts are handled in fill): no preamble, or the read timeout
            terminate(new Termination(handshaking ? DisconnectReason.PROTOCOL_MISMATCH : DisconnectReason.READ_TIMEOUT, e));
        } catch (Throwable e) {
            terminate(new Termination(DisconnectReason.IO_ERROR, e));
        } finally {
            finish(connected);
        }
    }

    /** Reads frames until the end of stream. */
    private void readFrames(InputStream in) throws IOException {
        while (true) {
            int available = readEnd - readStart;
            if (available < HEADER_LENGTH) {
                if (!fill(in, HEADER_LENGTH)) {
                    return;
                }
                continue;
            }
            byte[] buffer = readBuffer;
            int position = readStart;
            int signature = (int) INT.get(buffer, position);
            int nameLength = (int) INT.get(buffer, position + Integer.BYTES);
            int payloadLength = (int) INT.get(buffer, position + 2 * Integer.BYTES);
            if (signature != ProtobufSerializer.SIGNATURE) {
                throw new ProtocolException(String.format("Bad frame signature 0x%08X", signature));
            }
            if (nameLength == 0 && payloadLength == 0) {
                readStart += HEADER_LENGTH;   // heartbeat
                continue;
            }
            if (nameLength < 1 || nameLength > Protocol.MAX_CLASS_NAME_LENGTH) {
                throw new ProtocolException("Class name length out of range: " + nameLength);
            }
            if (payloadLength < 0 || payloadLength > config.maxMessageSize) {
                throw new ProtocolException(
                        "Payload length out of range: " + payloadLength + " (maxMessageSize " + config.maxMessageSize + ")");
            }
            int frameLength = HEADER_LENGTH + nameLength + payloadLength;
            if (available < frameLength) {
                if (!fill(in, frameLength)) {
                    return;
                }
                continue;
            }
            MessageLite message = parse(buffer, position + HEADER_LENGTH, nameLength, payloadLength);
            readStart += frameLength;
            notifyMessage(message);
        }
    }

    private MessageLite parse(byte[] buffer, int nameOffset, int nameLength, int payloadLength) throws ProtocolException {
        Parser<?> parser = parser(buffer, nameOffset, nameLength);
        try {
            // Not aliased: the message never references the read buffer, which gets reused
            return (MessageLite) parser.parseFrom(buffer, nameOffset + nameLength, payloadLength);
        } catch (IOException | RuntimeException e) {
            throw MessageTypes.protocolException(
                    "Unable to parse a " + new String(buffer, nameOffset, nameLength, ProtobufSerializer.CHARSET), e);
        }
    }

    // Streams usually carry few message types, and comparing a few names is cheaper than hashing the name on every message
    private Parser<?> parser(byte[] buffer, int offset, int length) throws ProtocolException {
        for (int i = 0; i < PARSER_CACHE_SIZE; i++) {
            byte[] name = cachedNames[i];
            if (name == null) {
                break;
            }
            if (name.length == length && Arrays.equals(name, 0, length, buffer, offset, offset + length)) {
                return cachedParsers[i];
            }
        }
        Parser<?> parser = messageTypes.parser(buffer, offset, length);
        cachedNames[nextCacheSlot] = Arrays.copyOfRange(buffer, offset, offset + length);
        cachedParsers[nextCacheSlot] = parser;
        nextCacheSlot = (nextCacheSlot + 1) % PARSER_CACHE_SIZE;
        return parser;
    }

    /**
     * Makes room for {@code needed} bytes from {@code readStart}, then reads at least one more byte.
     * @return false at the end of stream
     */
    private boolean fill(InputStream in, int needed) throws IOException {
        byte[] buffer = readBuffer;
        int pending = readEnd - readStart;
        int capacity = buffer.length;
        if (needed > capacity || (growReadBuffer && capacity < config.readBufferSize)) {
            int grown = capacity < INITIAL_BUFFER_SIZE ? INITIAL_BUFFER_SIZE : (int) Math.min(2L * capacity, Integer.MAX_VALUE);
            grown = Math.max(needed, Math.min(grown, config.readBufferSize));
            replaceReadBuffer(new byte[grown], pending);
        } else if (pending == 0) {
            if (capacity > config.readBufferSize) {
                readBuffer = new byte[config.readBufferSize];   // grown for one big frame: don't keep it
            }
            readStart = 0;
            readEnd = 0;
        } else if (readStart + needed > capacity || capacity - readEnd < capacity / 8) {
            replaceReadBuffer(buffer, pending);   // compact
        }
        while (true) {
            armReadTimeout();
            buffer = readBuffer;   // may have shrunk after a timeout
            int offered = buffer.length - readEnd;
            int n;
            try {
                n = in.read(buffer, readEnd, offered);
            } catch (SocketTimeoutException e) {
                onReadTimeout();
                continue;
            }
            if (n < 0) {
                return false;
            }
            lastReceivedNanos = System.nanoTime();
            readEnd += n;
            if (n == offered) {
                growReadBuffer = true;
            }
            return true;
        }
    }

    private void replaceReadBuffer(byte[] target, int pending) {
        System.arraycopy(readBuffer, readStart, target, 0, pending);
        readBuffer = target;
        readStart = 0;
        readEnd = pending;
        growReadBuffer = false;
    }

    private boolean readBufferShrinkable() {
        return readStart == readEnd && readBuffer.length > IDLE_READ_BUFFER_SIZE;
    }

    /** Sets SO_TIMEOUT to wake the reader for the next deadline: handshake, read timeout or idle shrink. */
    private void armReadTimeout() throws SocketException {
        long wait = Long.MAX_VALUE;
        if (handshaking) {
            wait = handshakeDeadlineNanos - System.nanoTime();
        } else {
            // Armed whenever the buffer could shrink, not only when it's empty: re-arming on every partial frame would
            // mean a setSoTimeout call on most reads. A mid-frame stall just wakes the reader once per delay, harmlessly.
            boolean shrinkTimer = readBuffer.length > IDLE_READ_BUFFER_SIZE;
            if (config.readTimeoutNanos > 0 || shrinkTimer) {
                long now = System.nanoTime();
                if (config.readTimeoutNanos > 0) {
                    wait = lastReceivedNanos + config.readTimeoutNanos - now;
                }
                if (shrinkTimer) {
                    long untilShrink = lastReceivedNanos + config.idleShrinkDelayNanos - now;
                    if (untilShrink > 0 || readBufferShrinkable()) {   // past due mid-frame: don't spin on 1 ms timeouts
                        wait = Math.min(wait, untilShrink);
                    }
                }
            }
        }
        int millis = wait == Long.MAX_VALUE ? 0 : (int) Math.clamp(Math.ceilDiv(wait, 1_000_000L), 1L, Integer.MAX_VALUE);
        int current = soTimeoutMillis;
        // Small differences don't matter (waking a little late or early is harmless), and skipping them avoids a
        // setSoTimeout call on every read
        if (millis != current && (millis == 0 || current <= 0 || Math.abs(millis - current) > current / 16 + 1)) {
            socket.setSoTimeout(millis);
            soTimeoutMillis = millis;
        }
    }

    private void onReadTimeout() throws SocketTimeoutException {
        long now = System.nanoTime();
        if (handshaking) {
            if (now - handshakeDeadlineNanos >= 0) {
                throw new SocketTimeoutException("No protocol preamble received within "
                        + TimeUnit.NANOSECONDS.toMillis(config.handshakeTimeoutNanos) + " ms");
            }
            return;
        }
        long idle = now - lastReceivedNanos;
        if (config.readTimeoutNanos > 0 && idle >= config.readTimeoutNanos) {
            throw new SocketTimeoutException(
                    "Nothing received for " + TimeUnit.NANOSECONDS.toMillis(config.readTimeoutNanos) + " ms");
        }
        if (readBufferShrinkable() && idle >= config.idleShrinkDelayNanos) {
            readBuffer = new byte[IDLE_READ_BUFFER_SIZE];
            readStart = 0;
            readEnd = 0;
            growReadBuffer = false;
        }
    }

    /** Last step of the reader thread: wait for the writer, fail leftovers, report the disconnect. */
    private void finish(boolean connected) {
        terminate(CLOSED_BY_PEER);   // no-op unless the loop exited without recording a reason
        Termination t = termination.get();
        if (!connected) {
            // Before waiting for the writer: it waits for the handshake outcome before reporting failures
            handshake.completeExceptionally(handshakeFailure(t));
        }
        awaitUninterruptibly(writerDone);
        failQueued();   // messages that raced with closing
        Watchdog.INSTANCE.unregister(this);
        readBuffer = null;
        if (connected) {
            LOG.debug("{} closed: {}", this, t.reason(), t.cause());
            notifyDisconnected(t);
        } else {
            LOG.debug("{} handshake failed: {}", this, t.reason(), t.cause());
        }
        closed.complete(t.reason());
    }

    private static IOException handshakeFailure(Termination t) {
        if (t.cause() instanceof IOException e) {
            return e;
        }
        return switch (t.reason()) {
            case CLOSED_LOCALLY -> new IOException("Closed during the handshake");
            case CLOSED_BY_PEER -> new SocketException("Connection closed by the peer during the handshake");
            default -> new IOException("Handshake failed: " + t.reason(), t.cause());
        };
    }

    // ------------------------------------------------------------------ writer thread

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            timedWrite(out, Protocol.PREAMBLE, Protocol.PREAMBLE.length);
            lastWrittenNanos = System.nanoTime();
            while (state.get() != CLOSED) {
                if (!queue.isEmpty()) {
                    writeBatch(out);
                } else if (state.get() == CLOSING) {
                    socket.shutdownOutput();   // flushed: send FIN, the reader waits for the peer's
                    return;
                } else {
                    awaitWork(out);
                }
            }
        } catch (Throwable e) {
            terminate(new Termination(DisconnectReason.IO_ERROR, e));
            failInFlight(e);
        } finally {
            writeBuffer = null;
            failQueued();
            writerDone.countDown();
        }
    }

    private void writeBatch(OutputStream out) throws IOException {
        byte[] buffer = writeBuffer != null ? writeBuffer : new byte[Math.min(INITIAL_BUFFER_SIZE, config.writeBufferSize)];
        int length = 0;
        Object item;
        while ((item = queue.peek()) != null) {
            int size = sizeOf(item);
            if (length + size > buffer.length) {
                if (length > 0) {
                    growWriteBuffer = buffer.length < config.writeBufferSize;
                    break;
                }
                // The first message doesn't fit: an oversized one gets a buffer of its own, released after the write
                buffer = new byte[size > config.writeBufferSize ? size : Math.max(size, nextWriteBufferSize(buffer.length))];
            }
            queue.poll();
            if (item instanceof EncodedFrame frame) {
                System.arraycopy(frame.frame(), 0, buffer, length, size);
            } else {
                MessageLite message = (MessageLite) item;
                try {
                    ProtobufSerializer.serializeInto(message, ByteBuffer.wrap(buffer, length, size));
                } catch (RuntimeException e) {
                    pendingMessages.decrementAndGet();
                    pendingBytes.addAndGet(-size);
                    if (handshakeSucceeded()) {
                        notifySendFailure(message, new SendException(SendException.Reason.WRITE_FAILED, e));
                    }
                    continue;
                }
            }
            length += size;
            inFlightBytes += size;
            inFlight.add(item);
        }
        if (length > 0) {
            timedWrite(out, buffer, length);
            int count = inFlight.size();
            inFlight.clear();
            messagesWritten += count;
            pendingMessages.addAndGet(-count);
            pendingBytes.addAndGet(-inFlightBytes);
            inFlightBytes = 0;
            lastWrittenNanos = System.nanoTime();
            lastBatchNanos = lastWrittenNanos;
        }
        if (buffer.length > config.writeBufferSize) {
            buffer = null;
        } else if (growWriteBuffer) {
            buffer = new byte[nextWriteBufferSize(buffer.length)];
            growWriteBuffer = false;
        }
        writeBuffer = buffer;
    }

    private int nextWriteBufferSize(int current) {
        return (int) Math.min(2L * current, config.writeBufferSize);
    }

    /** Parks until a message is queued, the connection closes, a heartbeat is due or the buffers can be released. */
    private void awaitWork(OutputStream out) throws IOException {
        long now = System.nanoTime();
        long wait = Long.MAX_VALUE;
        if (config.heartbeatIntervalNanos > 0) {
            long untilHeartbeat = lastWrittenNanos + config.heartbeatIntervalNanos - now;
            if (untilHeartbeat <= 0) {
                timedWrite(out, Protocol.HEARTBEAT_FRAME, Protocol.HEARTBEAT_FRAME.length);
                lastWrittenNanos = System.nanoTime();
                return;
            }
            wait = untilHeartbeat;
        }
        if (writeBuffer != null) {
            long untilShrink = lastBatchNanos + config.idleShrinkDelayNanos - now;
            if (untilShrink <= 0) {
                writeBuffer = null;
                inFlight.trimToSize();
            } else {
                wait = Math.min(wait, untilShrink);
            }
        }
        writerParked = true;
        try {
            int s = state.get();
            if (queue.isEmpty() && (s == OPEN || s == HANDSHAKING)) {
                if (wait == Long.MAX_VALUE) {
                    LockSupport.park(this);
                } else {
                    LockSupport.parkNanos(this, wait);
                }
            }
        } finally {
            writerParked = false;
        }
    }

    private void timedWrite(OutputStream out, byte[] bytes, int length) throws IOException {
        writeStartedNanos = System.nanoTime();
        writing = true;
        try {
            out.write(bytes, 0, length);
        } finally {
            writing = false;
        }
    }

    private void failInFlight(Throwable cause) {
        if (inFlight.isEmpty()) {
            return;
        }
        boolean report = handshakeSucceeded();
        SendException failure = new SendException(SendException.Reason.WRITE_FAILED, cause);
        pendingMessages.addAndGet(-inFlight.size());
        pendingBytes.addAndGet(-inFlightBytes);
        if (report) {
            for (Object item : inFlight) {
                notifySendFailure(messageOf(item), failure);
            }
        }
        inFlight.clear();
        inFlightBytes = 0;
    }

    /** Fails everything queued with CLOSED. Runs on the writer, then on the reader after the writer has finished. */
    private void failQueued() {
        Object item = queue.poll();
        if (item == null) {
            return;
        }
        boolean report = handshakeSucceeded();
        do {
            pendingMessages.decrementAndGet();
            pendingBytes.addAndGet(-sizeOf(item));
            if (report) {
                notifySendFailure(messageOf(item), CLOSED_FAILURE);
            }
        } while ((item = queue.poll()) != null);
    }

    private static int sizeOf(Object item) {
        return item instanceof EncodedFrame frame ? frame.frame().length : ProtobufSerializer.getSerializedSize((MessageLite) item);
    }

    private static MessageLite messageOf(Object item) {
        return item instanceof EncodedFrame frame ? frame.message() : (MessageLite) item;
    }

    /** Waits for the handshake outcome: send failures are reported only for connections that were reported connected. */
    private boolean handshakeSucceeded() {
        try {
            handshake.join();
            return true;
        } catch (CompletionException | CancellationException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ listener calls

    private void notifyConnected() {
        try {
            listener.onConnected();
        } catch (Throwable t) {
            LOG.warn("{}: onConnected threw", this, t);
        }
    }

    private void notifyMessage(MessageLite message) {
        try {
            listener.onMessage(message);
        } catch (Throwable t) {
            LOG.warn("{}: onMessage threw for a {}", this, message.getClass().getName(), t);
        }
    }

    private void notifySendFailure(MessageLite message, SendException cause) {
        try {
            listener.onSendFailure(message, cause);
        } catch (Throwable t) {
            LOG.warn("{}: onSendFailure threw", this, t);
        }
    }

    private void notifyDisconnected(Termination t) {
        try {
            listener.onDisconnected(t.reason(), t.cause());
        } catch (Throwable e) {
            LOG.warn("{}: onDisconnected threw", this, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Thread newThread(Runnable task, String role) {
        if (config.threadFactory == null) {
            return Thread.ofVirtual().name("protobuf-nio-" + role + "-" + id).unstarted(task);
        }
        Thread thread = config.threadFactory.newThread(task);
        if (thread == null) {
            throw new IllegalStateException("The thread factory returned null");
        }
        return thread;
    }

    @SuppressWarnings("unchecked")
    private static <T> void setOption(Socket socket, SocketOption<T> option, Object value) throws IOException {
        socket.setOption(option, (T) value);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return true if the latch opened before the deadline; the interrupt flag is kept */
    private static boolean awaitUntil(CountDownLatch latch, long deadlineNanos) {
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return latch.getCount() == 0;
                }
                try {
                    return latch.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
