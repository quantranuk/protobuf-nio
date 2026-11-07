package io.github.quantranuk.protobuf.nio.impl;

import io.github.quantranuk.protobuf.nio.serializer.ProtobufSerializer;
import io.github.quantranuk.protobuf.nio.utils.ByteArrayDequeue;
import io.github.quantranuk.protobuf.nio.utils.DefaultSetting;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

class SocketChannelReader {

    private static final int INITIAL_READ_SIZE = 8192;
    private static final int PARSER_CACHE_SIZE = 8;

    private final int maxReadSize;
    private int readSize;
    // Small per-channel cache: streams usually carry few message types, and comparing a few names with the vectorized
    // ByteBuffer.equals is much cheaper than hashing the class name for the shared map on every message
    private final ByteBuffer[] cachedClassNames = new ByteBuffer[PARSER_CACHE_SIZE];
    private final Parser<?>[] cachedParsers = new Parser<?>[PARSER_CACHE_SIZE];
    private int nextParserCacheSlot;
    private final byte[] header;
    private final ByteArrayDequeue readByteQueue;
    private final AsynchronousSocketChannel socketChannel;
    private final SocketAddress socketAddress;
    private final CompletionHandler<Integer, Message> messageReadCompletionHandler;
    private final ReaderCompletionHandler readerCompletionHandler;
    private final long readTimeoutMillis;
    private final int maxMessageSize;
    private final ExecutorService readExecutor;
    private int protobufClassNameLength;
    private int protobufPayloadLength;

    private enum ReadState {READING_MESSAGE_HEADER, READING_MESSAGE_BODY, STOPPED}
    private volatile ReadState readState;  // also written by stop() and NIO failure callbacks on other threads

    SocketChannelReader(AsynchronousSocketChannel socketChannel,
                        SocketAddress socketAddress,
                        long readTimeoutMillis,
                        int readBufferCapacity,
                        int maxMessageSize,
                        ExecutorService readExecutor,
                        CompletionHandler<Integer, Message> messageReadCompletionHandler) {
        this.socketChannel = socketChannel;
        this.socketAddress = socketAddress;
        this.readExecutor = readExecutor;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxMessageSize = maxMessageSize;
        // Read straight into the queue, starting small and growing only while reads fill the space offered,
        // so that idle connections do not hold large buffers
        this.maxReadSize = readBufferCapacity;
        this.readSize = Math.min(INITIAL_READ_SIZE, readBufferCapacity);
        this.header = new byte[ProtobufSerializer.HEADER_LENGTH];
        this.readByteQueue = new ByteArrayDequeue();
        this.messageReadCompletionHandler = messageReadCompletionHandler;
        this.readerCompletionHandler = new ReaderCompletionHandler();
    }

    void start() {
        readState = ReadState.READING_MESSAGE_HEADER;
        readExecutor.execute(this::readNextBlock);
    }

    void stop() {
        readState = ReadState.STOPPED;
    }

    private void readNextBlock() {
        if (readState == ReadState.STOPPED || !socketChannel.isOpen()) {
            return;
        }
        ByteBuffer readTarget = readByteQueue.prepareWrite(readSize);
        if (readTimeoutMillis == 0) {
            socketChannel.read(readTarget, null, readerCompletionHandler);
        } else {
            socketChannel.read(readTarget, readTimeoutMillis, TimeUnit.MILLISECONDS, null, readerCompletionHandler);
        }
    }

    private boolean processHeader() {
        if (readByteQueue.getRemaining() < header.length) {
            return false;
        }
        readByteQueue.popExactly(header);
        if (!ProtobufSerializer.hasValidHeaderSignature(header)) {
            readerCompletionHandler.failed(new IllegalStateException("Invalid header signature from " + socketAddress), null);
            return false;
        }
        int classNameLength = ProtobufSerializer.extractProtobufClassNameLength(header);
        int payloadLength = ProtobufSerializer.extractProtobufPayloadLength(header);
        if (classNameLength <= 0 || classNameLength > DefaultSetting.MAX_PROTO_CLASS_NAME_LENGTH) {
            readerCompletionHandler.failed(new IllegalStateException("Invalid protobuf class name length " + classNameLength + " from " + socketAddress), null);
            return false;
        }
        if (payloadLength < 0 || payloadLength > maxMessageSize) {
            readerCompletionHandler.failed(new IllegalStateException("Invalid protobuf payload length " + payloadLength + " from " + socketAddress
                    + " (max message size is " + maxMessageSize + ")"), null);
            return false;
        }
        protobufClassNameLength = classNameLength;
        protobufPayloadLength = payloadLength;
        readState = ReadState.READING_MESSAGE_BODY;
        return true;
    }

    private boolean processBody() {
        if (readByteQueue.getRemaining() < protobufClassNameLength + protobufPayloadLength) {
            return false;
        }
        ByteBuffer protobufClassNameBytes = readByteQueue.popExactly(protobufClassNameLength);
        ByteBuffer protobufPayloadBytes = readByteQueue.popExactly(protobufPayloadLength);
        Message message = ProtobufSerializer.parse(getParser(protobufClassNameBytes), protobufClassNameBytes, protobufPayloadBytes);
        messageReadCompletionHandler.completed(protobufPayloadLength, message);
        readState = ReadState.READING_MESSAGE_HEADER;
        return true;
    }

    @SuppressWarnings("unchecked")
    private Parser<? extends Message> getParser(ByteBuffer protobufClassName) {
        for (int i = 0; i < PARSER_CACHE_SIZE && cachedClassNames[i] != null; i++) {
            if (cachedClassNames[i].equals(protobufClassName)) {
                return (Parser<? extends Message>) cachedParsers[i];
            }
        }
        Parser<? extends Message> parser = ProtobufSerializer.getParser(protobufClassName);
        cachedClassNames[nextParserCacheSlot] = ProtobufSerializer.copyOf(protobufClassName);
        cachedParsers[nextParserCacheSlot] = parser;
        nextParserCacheSlot = (nextParserCacheSlot + 1) % PARSER_CACHE_SIZE;
        return parser;
    }

    private final class ReaderCompletionHandler implements CompletionHandler<Integer, Object> {

        @Override
        public void completed(Integer readLength, Object attachment) {
            readExecutor.execute(() -> {
                if (readState == ReadState.STOPPED) {
                    return;
                }
                try {
                    if (readLength == -1) {
                        failed(new IllegalStateException("Reached end-of-stream of " + socketAddress), null);
                        return;
                    }
                    if (readLength > 0) {
                        readByteQueue.commitWrite(readLength);
                        if (readLength == readSize && readSize < maxReadSize) {
                            readSize = Math.min(readSize * 2, maxReadSize);
                        }

                        boolean hasRemainingData = true;
                        while (hasRemainingData) {
                            hasRemainingData = switch (readState) {
                                case READING_MESSAGE_HEADER -> processHeader();
                                case READING_MESSAGE_BODY -> processBody();
                                case STOPPED -> false;
                            };
                        }
                    }
                    readNextBlock();
                } catch (RuntimeException e) {
                    // A corrupt frame, unknown class or read error must not leave the channel open but never read again
                    failed(e, null);
                }
            });
        }

        @Override
        public void failed(Throwable t, Object attachment) {
            if (readState == ReadState.STOPPED) {
                return;
            }
            readState = ReadState.STOPPED;
            messageReadCompletionHandler.failed(t, null);
        }
    }

}
