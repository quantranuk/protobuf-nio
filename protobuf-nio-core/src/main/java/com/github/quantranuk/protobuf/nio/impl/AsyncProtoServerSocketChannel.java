package com.github.quantranuk.protobuf.nio.impl;

import com.github.quantranuk.protobuf.nio.ProtoServerSocketChannel;
import com.github.quantranuk.protobuf.nio.ProtoSocketChannel;
import com.github.quantranuk.protobuf.nio.handlers.ConnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.DisconnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageReceivedHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSendFailureHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSentHandler;
import com.github.quantranuk.protobuf.nio.utils.DefaultSetting;
import com.github.quantranuk.protobuf.nio.utils.NamedThreadFactory;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncProtoServerSocketChannel implements ProtoServerSocketChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncProtoServerSocketChannel.class);

    private final SocketAddress serverSocketAddress;
    private final int serverPort;
    private final List<ConnectionHandler> connectionHandlers = new CopyOnWriteArrayList<>();
    private final List<DisconnectionHandler> disconnectionHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageReceivedHandler> messageReceivedHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSentHandler> messageSentHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSendFailureHandler> messageSendFailureHandlers = new CopyOnWriteArrayList<>();
    private final Map<SocketAddress, ProtoSocketChannel> socketChannels = new ConcurrentHashMap<>();

    private boolean isInitialized = false;
    private int readBufferSize = DefaultSetting.DEFAULT_SERVER_BUFFER_SIZE;
    private int writeBufferSize = DefaultSetting.DEFAULT_SERVER_BUFFER_SIZE;
    private long readTimeoutMillis = DefaultSetting.DEFAULT_READ_TIMEOUT_MILLIS;
    private long writeTimeoutMillis = DefaultSetting.DEFAULT_WRITE_TIMEOUT_MILLIS;
    private AsynchronousServerSocketChannel serverSocketChannel;
    private ExecutorService acceptExecutor;
    private ExecutorService readExecutor;
    private ExecutorService writeExecutor;

    public AsyncProtoServerSocketChannel(int port) {
        this.serverPort = port;
        this.serverSocketAddress = new InetSocketAddress(port);
    }

    @PostConstruct
    public void init() {
        if (isInitialized) {
            return;
        }
        isInitialized = true;
        acceptExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoServerSocketChannel.class.getSimpleName() + "-Acceptor-" + serverPort));
        readExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoServerSocketChannel.class.getSimpleName() + "-Reader-" + serverPort));
        writeExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoServerSocketChannel.class.getSimpleName() + "-Writer-" + serverPort));
        try {
            serverSocketChannel = AsynchronousServerSocketChannel.open(AsynchronousChannelGroup.withThreadPool(acceptExecutor));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open server socket channel", e);
        }
    }

    @Override
    public void start() throws IOException {
        serverSocketChannel.bind(serverSocketAddress);
        LOGGER.info("Bind to port " + serverPort);
        acceptExecutor.execute(this::acceptNewConnection);
    }

    private void acceptNewConnection() {
        if (!serverSocketChannel.isOpen()) {
            return;
        }
        serverSocketChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
            @Override
            public void completed(AsynchronousSocketChannel socketChannel, Object attachment) {
                SocketAddress remoteAddress = getRemoteAddress(socketChannel);
                LOGGER.info("Accepted connection from " + remoteAddress);
                AsyncProtoSocketChannel protobufSocketChannel = createProtobufSocketChannel(socketChannel, remoteAddress);
                connectionHandlers.forEach(handler -> handler.onConnected(remoteAddress));
                socketChannels.put(remoteAddress, protobufSocketChannel);
                protobufSocketChannel.startReading();
                acceptNewConnection();
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                LOGGER.error("Unable to accept new connection at port " + serverPort, exc);
                if (serverSocketChannel.isOpen()) {
                    acceptNewConnection();
                }
            }
        });
    }

    private AsyncProtoSocketChannel createProtobufSocketChannel(AsynchronousSocketChannel socketChannel, SocketAddress remoteAddress) {
        AsyncProtoSocketChannel protobufSocketChannel = new AsyncProtoSocketChannel(remoteAddress);
        protobufSocketChannel.setReadBufferSize(readBufferSize);
        protobufSocketChannel.setWriteBufferSize(writeBufferSize);
        protobufSocketChannel.setReadExecutor(readExecutor);
        protobufSocketChannel.setWriteExecutor(writeExecutor);
        protobufSocketChannel.setReadTimeoutMillis(readTimeoutMillis);
        protobufSocketChannel.setWriteTimeoutMillis(writeTimeoutMillis);
        protobufSocketChannel.setSocketChannel(socketChannel);
        protobufSocketChannel.addDisconnectionHandler((socketAddress) -> {
            LOGGER.info("Disconnected from " + socketAddress);
            socketChannels.remove(socketAddress);
            disconnectionHandlers.forEach(handler -> handler.onDisconnected(socketAddress));
        });
        protobufSocketChannel.addMessageReceivedHandler((socketAddress, message) -> messageReceivedHandlers.forEach(handler -> handler.onMessageReceived(socketAddress, message)));
        protobufSocketChannel.addMessageSentHandler((socketAddress, message) -> messageSentHandlers.forEach(handler -> handler.onMessageSent(socketAddress, message)));
        protobufSocketChannel.addMessageSendFailureHandler((socketAddress, message, t) -> messageSendFailureHandlers.forEach(handler -> handler.onMessageSendFailure(socketAddress, message, t)));
        protobufSocketChannel.init();
        return protobufSocketChannel;
    }

    private SocketAddress getRemoteAddress(AsynchronousSocketChannel socketChannel) {
        try {
            return socketChannel.getRemoteAddress();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to get Remote Address from socket channel", e);
        }
    }

    @Override
    @PreDestroy
    public void stop() {
        socketChannels.values().forEach(ProtoSocketChannel::disconnect);
        socketChannels.clear();
        try {
            serverSocketChannel.close();
        } catch (IOException e) {
            LOGGER.error("Unable to close server socket channel at port " + serverPort, e);
        }
        if (!acceptExecutor.isShutdown()) {
            acceptExecutor.shutdown();
        }
        if (!readExecutor.isShutdown()) {
            readExecutor.shutdown();
        }
        if (!writeExecutor.isShutdown()) {
            writeExecutor.shutdown();
        }
    }


    @Override
    public void sendMessage(SocketAddress socketAddress, Message message) {
        ProtoSocketChannel protoSocketChannel = socketChannels.get(socketAddress);
        protoSocketChannel.sendMessage(message);
    }

    @Override
    public void sendMessageToAll(Message message) {
        socketChannels.values().forEach(channel -> channel.sendMessage(message));
    }

    @Override
    public Collection<SocketAddress> getConnectedAddresses() {
        return Collections.unmodifiableCollection(socketChannels.keySet());
    }

    @Override
    public boolean isConnected(SocketAddress socketAddress) {
        return socketChannels.containsKey(socketAddress);
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

}
