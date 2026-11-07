package com.github.quantranuk.protobuf.nio.impl;

import com.github.quantranuk.protobuf.nio.serializer.ProtobufSerializer;
import com.github.quantranuk.protobuf.nio.utils.ByteArrayDequeue;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * <p>Writes queued messages to the socket in batches.</p>
 * <p>Any thread can call {@link #addToWriteQueue(Message)}: it only appends to a lock-free queue. The write executor is
 * scheduled once per active period, guarded by {@code writerActive}, rather than once per message. While the writer is
 * active it keeps draining the queue, one batch of up to {@code maxBatchBytes} at a time, and releases the flag only
 * when the queue is empty. All other state is touched only on the write executor.</p>
 */
class SocketChannelWriter {

    private final AsynchronousSocketChannel socketChannel;
    private final CompletionHandler<Integer, Message> messageWriteCompletionHandler;
    private final Consumer<Throwable> writeFailureListener;
    private final WriteCompletionHandler writeCompletionHandler;
    private final ExecutorService writeExecutor;
    private final long writeTimeoutMillis;
    private final int maxBatchBytes;
    private final int maxMessageWriteQueueSize;

    // Shared with the threads calling addToWriteQueue
    private final Queue<Message> outboundMessageQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outboundMessageCount = new AtomicInteger();
    private final AtomicBoolean writerActive = new AtomicBoolean();
    private volatile Throwable stopCause;

    // Write executor only
    private final ByteArrayDequeue writeBytesQueue = new ByteArrayDequeue();
    private final List<Message> messagesBeingWritten = new ArrayList<>();
    private ByteBuffer batchBuffer;
    private int batchEnd;

    SocketChannelWriter(AsynchronousSocketChannel socketChannel,
                        long writeTimeoutMillis,
                        int maxBatchBytes,
                        int maxMessageWriteQueueSize,
                        ExecutorService writeExecutor,
                        CompletionHandler<Integer, Message> messageWriteCompletionHandler,
                        Consumer<Throwable> writeFailureListener) {
        this.socketChannel = socketChannel;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.maxBatchBytes = maxBatchBytes;
        this.maxMessageWriteQueueSize = maxMessageWriteQueueSize;
        this.writeExecutor = writeExecutor;
        this.messageWriteCompletionHandler = messageWriteCompletionHandler;
        this.writeFailureListener = writeFailureListener;
        this.writeCompletionHandler = new WriteCompletionHandler();
    }

    void addToWriteQueue(Message message) {
        if (outboundMessageCount.incrementAndGet() > maxMessageWriteQueueSize) {
            outboundMessageCount.decrementAndGet();
            throw new IllegalStateException("Unable to accept more message due to outbound message queue is too large (" + maxMessageWriteQueueSize + ")");
        }
        outboundMessageQueue.add(message);
        activateWriter();
    }

    /**
     * Stop writing. Messages that are still queued are reported as failed. Can be called from any thread.
     */
    void stop() {
        if (stopCause == null) {
            stopCause = new ClosedChannelException();
        }
        activateWriter();
    }

    private void activateWriter() {
        if (!writerActive.compareAndSet(false, true)) {
            return;  // the active writer will pick the message up before it goes idle
        }
        try {
            writeExecutor.execute(this::checkMessageQueue);
        } catch (RejectedExecutionException e) {
            // Executor shut down (channel disconnected) and the writer is idle: report queued messages from this thread
            if (stopCause == null) {
                stopCause = new ClosedChannelException();
            }
            failQueuedMessages(stopCause);
            writerActive.set(false);
        }
    }

    /**
     * Runs on the write executor while {@code writerActive} is set: write the next batch, or go idle if there is none.
     */
    private void checkMessageQueue() {
        while (true) {
            Throwable cause = stopCause;
            if (cause != null) {
                failQueuedMessages(cause);
            } else {
                pollNextBatch();
                if (!messagesBeingWritten.isEmpty()) {
                    writeMessages();
                    return;
                }
            }
            writerActive.set(false);
            // A message added after the queue looked empty but before the flag was released would otherwise be stranded
            if (outboundMessageQueue.isEmpty() || !writerActive.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void pollNextBatch() {
        messagesBeingWritten.clear();
        int batchBytes = 0;
        Message message;
        while ((message = outboundMessageQueue.peek()) != null) {
            int size = ProtobufSerializer.getSerializedSize(message);
            if (!messagesBeingWritten.isEmpty() && batchBytes + size > maxBatchBytes) {
                break;
            }
            outboundMessageQueue.poll();
            outboundMessageCount.decrementAndGet();
            messagesBeingWritten.add(message);
            batchBytes += size;
        }
    }

    private void writeMessages() {
        writeBytesQueue.clear();
        for (Message message : messagesBeingWritten) {
            // Encode straight into the batch storage: no per-message frame array or payload copy
            int size = ProtobufSerializer.getSerializedSize(message);
            ProtobufSerializer.serializeInto(message, writeBytesQueue.prepareWrite(size));
            writeBytesQueue.commitWrite(size);
        }
        // Write straight from the queue's storage; nothing modifies it until the whole batch is written
        batchBuffer = writeBytesQueue.popMaximum(writeBytesQueue.getRemaining());
        batchEnd = batchBuffer.limit();
        writeNextChunk();
    }

    private void writeNextChunk() {
        if (batchBuffer.position() == batchEnd) {
            batchBuffer = null;
            for (Message message : messagesBeingWritten) {
                messageWriteCompletionHandler.completed(ProtobufSerializer.getSerializedSize(message), message);
            }
            checkMessageQueue();
            return;
        }
        // Bound each write call: the JDK copies a heap buffer into a temporary direct buffer of the same size
        batchBuffer.limit(Math.min(batchEnd, batchBuffer.position() + maxBatchBytes));
        try {
            socketChannel.write(batchBuffer, writeTimeoutMillis, TimeUnit.MILLISECONDS, null, writeCompletionHandler);
        } catch (RuntimeException e) {
            handleWriteFailure(e);
        }
    }

    /**
     * Runs on the write executor. After a failed or timed-out write the channel is in an undefined state, so every
     * in-flight and queued message is failed and the writer stops.
     */
    private void handleWriteFailure(Throwable exc) {
        boolean isFirstFailure = stopCause == null;
        if (isFirstFailure) {
            stopCause = exc;
        }
        batchBuffer = null;
        for (Message message : messagesBeingWritten) {
            messageWriteCompletionHandler.failed(exc, message);
        }
        messagesBeingWritten.clear();
        if (isFirstFailure) {
            writeFailureListener.accept(exc);
        }
        checkMessageQueue();  // fails whatever is still queued and releases the writer
    }

    private void failQueuedMessages(Throwable exc) {
        Message message;
        while ((message = outboundMessageQueue.poll()) != null) {
            outboundMessageCount.decrementAndGet();
            messageWriteCompletionHandler.failed(exc, message);
        }
    }

    private final class WriteCompletionHandler implements CompletionHandler<Integer, Object> {

        @Override
        public void completed(Integer result, Object attachment) {
            // The channel has already advanced batchBuffer's position by the number of bytes written
            try {
                writeExecutor.execute(SocketChannelWriter.this::writeNextChunk);
            } catch (RejectedExecutionException e) {
                failInFlightAfterShutdown();
            }
        }

        @Override
        public void failed(Throwable exc, Object attachment) {
            try {
                writeExecutor.execute(() -> handleWriteFailure(exc));
            } catch (RejectedExecutionException e) {
                failInFlightAfterShutdown();
            }
        }

        private void failInFlightAfterShutdown() {
            // Executor already shut down (channel disconnected): nothing else touches writer state now
            ClosedChannelException exc = new ClosedChannelException();
            messagesBeingWritten.forEach(message -> messageWriteCompletionHandler.failed(exc, message));
            messagesBeingWritten.clear();
            failQueuedMessages(exc);
            writerActive.set(false);  // later sends then hit the rejected executor and are failed there
        }
    }

}
