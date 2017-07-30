package com.quantran.protobuf.nio.impl;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoSocketChannel;
import com.quantran.protobuf.nio.handlers.ConnectionHandler;
import com.quantran.protobuf.nio.handlers.DisconnectionHandler;
import com.quantran.protobuf.nio.handlers.MessageReceivedHandler;
import com.quantran.protobuf.nio.handlers.MessageSentHandler;
import com.quantran.protobuf.nio.handlers.MessageSendFailureHandler;
import com.quantran.protobuf.nio.utils.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncProtoSocketChannel implements ProtoSocketChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncProtoSocketChannel.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 4096;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 0;
    private static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10000;

    private final List<ConnectionHandler> connectionHandlers = new CopyOnWriteArrayList<>();
    private final List<DisconnectionHandler> disconnectionHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageReceivedHandler> messageReceivedHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSentHandler> messageSentHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSendFailureHandler> messageSendFailureHandlers = new CopyOnWriteArrayList<>();
    private final SocketAddress socketAddress;

    private AsynchronousSocketChannel socketChannel;
    private SocketChannelReader reader;
    private SocketChannelWriter writer;

    private int readBufferSize = DEFAULT_BUFFER_CAPACITY;
    private int writeBufferSize = DEFAULT_BUFFER_CAPACITY;
    private long readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
    private long writeTimeoutMillis = DEFAULT_WRITE_TIMEOUT_MILLIS;
    private boolean isShuttingDown = false;
    private boolean isInjectedReadExecutor = false;
    private boolean isInjectedWriteExecutor = false;
    private ExecutorService readExecutor;
    private ExecutorService writeExecutor;

    public AsyncProtoSocketChannel(SocketAddress socketAddress) {
        this.socketAddress = socketAddress;
    }

    public AsyncProtoSocketChannel(String host, int port) {
        this.socketAddress = new InetSocketAddress(host, port);
    }

    @PostConstruct
    public void init() {
        if (readExecutor == null) {
            readExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoSocketChannel.class.getSimpleName() + "-Reader"));
        }
        if (writeExecutor == null) {
            writeExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoSocketChannel.class.getSimpleName() + "-Writer"));
        }
        if (socketChannel == null) {
            try {
                socketChannel = AsynchronousSocketChannel.open();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to open socket channel", e);
            }
        }
        reader = new SocketChannelReader(socketChannel, socketAddress, readTimeoutMillis, readBufferSize, readExecutor, new MessageReadCompletionHandler());
        writer = new SocketChannelWriter(socketChannel, writeTimeoutMillis, writeBufferSize, writeExecutor, new MessageWriteCompletionHandler());
    }

    @Override
    public void connect() {
        try {
            socketChannel.connect(socketAddress).get();
            LOGGER.debug("Connected to "+ socketAddress);
            connectionHandlers.forEach(handler -> handler.onConnected(socketAddress));
            reader.start();
        } catch (InterruptedException e) {
            LOGGER.debug("Interrupted while connecting to "+ socketAddress, e);
            Thread.currentThread().interrupt();
        }catch (ExecutionException e) {
            LOGGER.error("An error has occurred while trying connect to " + socketAddress, e);
            disconnect();
        }
    }

    void startReading() {
        reader.start();
    }

    @Override
    @PreDestroy
    public void disconnect() {
        isShuttingDown = true;
        if (reader != null) {
            reader.stop();
        }
        if (socketChannel != null && socketChannel.isOpen()) {
            try {
                socketChannel.close();
            } catch (IOException e) {
                LOGGER.error("Unable to close socket channel to " + socketAddress, e);
            }
        }
        LOGGER.debug("Disconnected from " + socketAddress);
        disconnectionHandlers.forEach(handler -> handler.onDisconnected(socketAddress));
        if (!isInjectedReadExecutor) {
            readExecutor.shutdown();
        }
        if (!isInjectedWriteExecutor) {
            writeExecutor.shutdown();
        }
    }

    @Override
    public void sendMessage(Message message) {
        if (!socketChannel.isOpen()) {
            throw new IllegalStateException("Socket channel " + socketAddress + " is closed");
        }
        writer.addToWriteQueue(message);
    }

    @Override
    public void addConnectionHandler(ConnectionHandler handler) {
        connectionHandlers.add(handler);
    }

    @Override
    public void removeConnectionHandler(ConnectionHandler handler) {
        connectionHandlers.remove(handler);
    }

    @Override
    public void addDisconnectionHandler(DisconnectionHandler handler) {
        disconnectionHandlers.add(handler);
    }

    @Override
    public void removeDisconnectionHandler(DisconnectionHandler handler) {
        disconnectionHandlers.remove(handler);
    }

    @Override
    public void addMessageReceivedHandler(MessageReceivedHandler handler) {
        messageReceivedHandlers.add(handler);
    }

    @Override
    public void removeMessageReceivedHandler(MessageReceivedHandler handler) {
        messageReceivedHandlers.remove(handler);
    }

    @Override
    public void addMessageSentHandler(MessageSentHandler handler) {
        messageSentHandlers.add(handler);
    }

    @Override
    public void removeMessageSentHandler(MessageSentHandler handler) {
        messageSentHandlers.remove(handler);
    }

    @Override
    public void addMessageSendFailureHandler(MessageSendFailureHandler handler) {
        messageSendFailureHandlers.add(handler);
    }

    @Override
    public void removeMessageSendFailureHandler(MessageSendFailureHandler handler) {
        messageSendFailureHandlers.remove(handler);
    }

    public void setReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
    }

    public void setWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
    }

    public void setReadTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    public void setSocketChannel(AsynchronousSocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    void setReadExecutor(ExecutorService executor) {
        this.readExecutor = executor;
        this.isInjectedReadExecutor = true;
    }

    void setWriteExecutor(ExecutorService executor) {
        this.writeExecutor = executor;
        this.isInjectedWriteExecutor = true;
    }

    private class MessageReadCompletionHandler implements CompletionHandler<Long, Message> {

        @Override
        public void completed(Long readBytes, Message message) {
            messageReceivedHandlers.forEach(handler -> handler.onMessageReceived(socketAddress, message));
        }

        @Override
        public void failed(Throwable exc, Message message) {
            if (!isShuttingDown) {
                LOGGER.debug("Unable to read from " + socketAddress, exc);
                disconnect();
            }
        }
    }

    private class MessageWriteCompletionHandler implements CompletionHandler<Long, Message> {

        @Override
        public void completed(Long sentBytes, Message message) {
            messageSentHandlers.forEach(handler -> handler.onMessageSent(socketAddress, message));
        }

        @Override
        public void failed(Throwable exc, Message message) {
            messageSendFailureHandlers.forEach(handler -> handler.onMessageSendFailure(socketAddress, message, exc));
        }
    }

}
