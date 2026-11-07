package com.github.quantranuk.protobuf.nio.impl;

import com.github.quantranuk.protobuf.nio.serializer.ProtobufSerializer;
import com.github.quantranuk.protobuf.nio.utils.ByteArrayDequeue;
import com.google.protobuf.Message;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class SocketChannelWriter {

    private final AsynchronousSocketChannel socketChannel;
    private final CompletionHandler<Integer, Message> messageWriteCompletionHandler;
    private final WriteCompletionHandler writeCompletionHandler;
    private final ExecutorService writeExecutor;
    private final Queue<Message> outboundMessageQueue;
    private final long writeTimeoutMillis;
    private final ByteArrayDequeue writeBytesQueue;
    private final ByteBuffer writeBuffer;
    private final int writeBufferCapacity;
    private final List<Message> messagesBeingWritten;
    private final AtomicBoolean isWritingInProgress;
    private final int maxMessageWriteQueueSize;
    private final Consumer<Throwable> writeFailureListener;
    private volatile boolean stopped;

    SocketChannelWriter(AsynchronousSocketChannel socketChannel,
                        long writeTimeoutMillis,
                        int writeBufferCapacity,
                        int maxMessageWriteQueueSize,
                        ExecutorService writeExecutor,
                        CompletionHandler<Integer, Message> messageWriteCompletionHandler,
                        Consumer<Throwable> writeFailureListener) {
        this.socketChannel = socketChannel;
        this.writeFailureListener = writeFailureListener;
        this.maxMessageWriteQueueSize = maxMessageWriteQueueSize;
        this.messageWriteCompletionHandler = messageWriteCompletionHandler;
        this.writeCompletionHandler = new WriteCompletionHandler();
        this.writeExecutor = writeExecutor;
        this.outboundMessageQueue = new ArrayDeque<>();
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.isWritingInProgress = new AtomicBoolean();
        this.writeBytesQueue = new ByteArrayDequeue();
        this.writeBufferCapacity = writeBufferCapacity;
        this.messagesBeingWritten = new ArrayList<>();
        this.writeBuffer = ByteBuffer.allocate(writeBufferCapacity);
    }

    void addToWriteQueue(Message message) {
        if (outboundMessageQueue.size() > maxMessageWriteQueueSize) {
            throw new IllegalStateException("Unable to accept more message due to outbound message queue is too large (" + outboundMessageQueue.size() + ")");
        }

        writeExecutor.execute(() -> {
            if (stopped) {
                messageWriteCompletionHandler.failed(new ClosedChannelException(), message);
                return;
            }
            outboundMessageQueue.add(message);
            if (!isWritingInProgress.getAndSet(true)) {
                checkMessageQueue();
            }
        });
    }

    /**
     * Stop writing. Messages that are still queued are reported as failed. Can be called from any thread.
     */
    void stop() {
        stopped = true;
        try {
            writeExecutor.execute(() -> failQueuedMessages(new ClosedChannelException()));
        } catch (RejectedExecutionException e) {
            // Executor already shut down: nothing more will run on it, so queued messages cannot be reported
        }
    }

    private void checkMessageQueue() {
        pollNextBatch();
        if (messagesBeingWritten.isEmpty()) {
            isWritingInProgress.set(false);
            return;
        }
        writeMessages(messagesBeingWritten);
    }

    private void pollNextBatch() {
        int bytesToWrite = 0;
        int nextMessageSize = 0;
        messagesBeingWritten.clear();
        while (bytesToWrite + nextMessageSize < writeBufferCapacity) {
            Message message = outboundMessageQueue.poll();
            if (message == null) {
                break;
            }
            messagesBeingWritten.add(message);
            bytesToWrite += ProtobufSerializer.getSerializedSize(message);
            nextMessageSize = outboundMessageQueue.isEmpty() ? 0 : ProtobufSerializer.getSerializedSize(outboundMessageQueue.peek());
        }
    }

    private void writeMessages(List<Message> messages) {
        writeBytesQueue.clear();
        messages.forEach(message -> writeBytesQueue.push(ProtobufSerializer.serialize(message)));
        writeNextBlock(messages);
    }

    private void writeNextBlock(List<Message> messages) {
        ByteBuffer nextBlock = writeBytesQueue.popMaximum(writeBufferCapacity);
        if (nextBlock == null) {
            messages.forEach(message -> messageWriteCompletionHandler.completed(ProtobufSerializer.getSerializedSize(message), message));
            checkMessageQueue();
            return;
        }

        writeBuffer.clear();
        writeBuffer.put(nextBlock);
        writeBuffer.flip();
        try {
            socketChannel.write(writeBuffer, writeTimeoutMillis, TimeUnit.MILLISECONDS, messages, writeCompletionHandler);
        } catch (RuntimeException e) {
            handleWriteFailure(e, messages);
        }
    }

    /**
     * Must run on the write executor. After a failed or timed-out write the channel is in an undefined state,
     * so every in-flight and queued message is failed and the writer stops accepting new work.
     */
    private void handleWriteFailure(Throwable exc, List<Message> messages) {
        boolean wasStopped = stopped;
        stopped = true;
        messages.forEach(message -> messageWriteCompletionHandler.failed(exc, message));
        messages.clear();
        failQueuedMessages(exc);
        isWritingInProgress.set(false);
        if (!wasStopped) {
            writeFailureListener.accept(exc);
        }
    }

    private void failQueuedMessages(Throwable exc) {
        Message message;
        while ((message = outboundMessageQueue.poll()) != null) {
            messageWriteCompletionHandler.failed(exc, message);
        }
    }

    private final class WriteCompletionHandler implements CompletionHandler<Integer, List<Message>> {

        @Override
        public void completed(Integer result, List<Message> messages) {
            try {
                writeExecutor.execute(() -> {
                    int unwrittenBytes = writeBuffer.limit() - result;
                    if (unwrittenBytes > 0) {
                        writeBytesQueue.pushLast(writeBuffer.array(), result, unwrittenBytes);
                    }
                    writeNextBlock(messages);
                });
            } catch (RejectedExecutionException e) {
                // Executor already shut down (channel disconnected): these messages will not be sent
                messages.forEach(message -> messageWriteCompletionHandler.failed(new ClosedChannelException(), message));
            }
        }

        @Override
        public void failed(Throwable exc, List<Message> messages) {
            try {
                writeExecutor.execute(() -> handleWriteFailure(exc, messages));
            } catch (RejectedExecutionException e) {
                // Executor already shut down (channel disconnected): report directly, nothing else touches writer state now
                messages.forEach(message -> messageWriteCompletionHandler.failed(exc, message));
            }
        }

    }

}
