package com.quantran.protobuf.nio;

import com.quantran.protobuf.nio.impl.AsyncProtoServerSocketChannel;
import com.quantran.protobuf.nio.impl.AsyncProtoSocketChannel;
import com.quantran.protobuf.nio.utils.DefaultSetting;

import java.util.concurrent.ExecutorService;

public final class ProtoChannelFactory {

    private ProtoChannelFactory() {
    }

    public static ClientBuilder newClient(String host, int port) {
        return new ClientBuilder(host, port);
    }

    public static ServerBuilder newServer( int port) {
        return new ServerBuilder(port);
    }

    public final static class ClientBuilder {
        private final String host;
        private final int port;
        private int readBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private int writeBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private long readTimeoutMillis = DefaultSetting.DEFAULT_READ_TIMEOUT_MILLIS;
        private long writeTimeoutMillis = DefaultSetting.DEFAULT_WRITE_TIMEOUT_MILLIS;
        private ExecutorService connectExecutor = null;
        private ExecutorService readExecutor = null;
        private ExecutorService writeExecutor = null;

        private ClientBuilder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public ClientBuilder setReadBufferSize(int readBufferSize) {
            this.readBufferSize = readBufferSize;
            return this;
        }

        public ClientBuilder setWriteBufferSize(int writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
            return this;
        }

        public ClientBuilder setReadTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public ClientBuilder setWriteTimeoutMillis(long writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        public ClientBuilder setConnectExecutor(ExecutorService connectExecutor) {
            this.connectExecutor = connectExecutor;
            return this;
        }

        public ClientBuilder setReadExecutor(ExecutorService readExecutor) {
            this.readExecutor = readExecutor;
            return this;
        }

        public ClientBuilder setWriteExecutor(ExecutorService writeExecutor) {
            this.writeExecutor = writeExecutor;
            return this;
        }

        public ProtoSocketChannel build() {
            AsyncProtoSocketChannel channel = new AsyncProtoSocketChannel(host, port);
            channel.setReadBufferSize(readBufferSize);
            channel.setWriteBufferSize(writeBufferSize);
            channel.setReadTimeoutMillis(readTimeoutMillis);
            channel.setWriteTimeoutMillis(writeTimeoutMillis);
            channel.setConnectExecutor(connectExecutor);
            channel.setReadExecutor(readExecutor);
            channel.setWriteExecutor(writeExecutor);
            channel.init();
            return channel;
        }
    }


    public final static class ServerBuilder {
        private final int port;
        private int readBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private int writeBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private long readTimeoutMillis = DefaultSetting.DEFAULT_READ_TIMEOUT_MILLIS;
        private long writeTimeoutMillis = DefaultSetting.DEFAULT_WRITE_TIMEOUT_MILLIS;

        private ServerBuilder(int port) {
            this.port = port;
        }

        public ServerBuilder setReadBufferSize(int readBufferSize) {
            this.readBufferSize = readBufferSize;
            return this;
        }

        public ServerBuilder setWriteBufferSize(int writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
            return this;
        }

        public ServerBuilder setReadTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public ServerBuilder setWriteTimeoutMillis(long writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        public ProtoServerSocketChannel build() {
            AsyncProtoServerSocketChannel channel = new AsyncProtoServerSocketChannel(port);
            channel.setReadBufferSize(readBufferSize);
            channel.setWriteBufferSize(writeBufferSize);
            channel.setReadTimeoutMillis(readTimeoutMillis);
            channel.setWriteTimeoutMillis(writeTimeoutMillis);
            channel.init();
            return channel;
        }
    }

}
