package com.github.quantranuk.protobuf.nio;

import com.github.quantranuk.protobuf.nio.impl.AsyncProtoServerSocketChannel;
import com.github.quantranuk.protobuf.nio.impl.AsyncProtoSocketChannel;
import com.github.quantranuk.protobuf.nio.utils.DefaultSetting;

import java.util.concurrent.ExecutorService;

/**
 * Factory for creating {@link ProtoSocketChannel} and @{@link ProtoServerSocketChannel}
 */
public final class ProtoChannelFactory {

    private ProtoChannelFactory() {
    }

    /**
     * Create a new builder for {@link ProtoSocketChannel}
     * @param host the host to connect to
     * @param port the port to connect to
     * @return a builder for {@link ProtoSocketChannel}
     */
    public static ClientBuilder newClient(String host, int port) {
        return new ClientBuilder(host, port);
    }

    /**
     * Create a new builder for {@link ProtoServerSocketChannel}
     * @param port the port to listen to
     * @return a builder for {@link ProtoServerSocketChannel}
     */
    public static ServerBuilder newServer( int port) {
        return new ServerBuilder(port);
    }

    /**
     * The builder to build {@link ProtoSocketChannel}
     */
    public final static class ClientBuilder {
        private final String host;
        private final int port;
        private int readBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private int writeBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private long readTimeoutMillis = DefaultSetting.DEFAULT_READ_TIMEOUT_MILLIS;
        private long writeTimeoutMillis = DefaultSetting.DEFAULT_WRITE_TIMEOUT_MILLIS;
        private ExecutorService readExecutor = null;
        private ExecutorService writeExecutor = null;

        private ClientBuilder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         * The size of the buffer used to read from the socket channel. The default value is 8192 (8 KB)
         * @param readBufferSize readBufferSize
         * @return builder
         */
        public ClientBuilder setReadBufferSize(int readBufferSize) {
            this.readBufferSize = readBufferSize;
            return this;
        }

        /**
         * The size of the buffer used to write to the socket channel. The default value is 8192 (8 KB)
         * @param writeBufferSize writeBufferSize
         * @return builder
         */
        public ClientBuilder setWriteBufferSize(int writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
            return this;
        }

        /**
         * The timeout in milliseconds for reading from the socket. The default value is 0 (no timeout)
         * @param readTimeoutMillis readTimeoutMillis
         * @return builder
         */
        public ClientBuilder setReadTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        /**
         * The timeout in milliseconds for reading from the socket. The default value is 10000 (10 seconds)
         * @param writeTimeoutMillis writeTimeoutMillis
         * @return builder
         */
        public ClientBuilder setWriteTimeoutMillis(long writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        /**
         * <p>The executor that will execute read activities. This must be a single thread executor only.
         * Use this method to share the same thread between multiple clients</p>
         * <p>If not set a new thread will be spawn by default</p>
         * @param readExecutor readExecutor
         * @return builder
         */
        public ClientBuilder setReadExecutor(ExecutorService readExecutor) {
            this.readExecutor = readExecutor;
            return this;
        }

        /**
         * <p>The executor that will execute write activities. This must be a single thread executor only.
         * Use this method to share the same thread between multiple clients</p>
         * <p>If not set a new thread will be spawn by default</p>
         * @param writeExecutor writeExecutor
         * @return builder
         */
        public ClientBuilder setWriteExecutor(ExecutorService writeExecutor) {
            this.writeExecutor = writeExecutor;
            return this;
        }

        /**
         * Build the {@link ProtoServerSocketChannel}
         * @return ProtoSocketChannel
         */
        public ProtoSocketChannel build() {
            AsyncProtoSocketChannel channel = new AsyncProtoSocketChannel(host, port);
            channel.setReadBufferSize(readBufferSize);
            channel.setWriteBufferSize(writeBufferSize);
            channel.setReadTimeoutMillis(readTimeoutMillis);
            channel.setWriteTimeoutMillis(writeTimeoutMillis);
            channel.setReadExecutor(readExecutor);
            channel.setWriteExecutor(writeExecutor);
            channel.init();
            return channel;
        }
    }

    /**
     * The builder to build @{@link ProtoServerSocketChannel}
     */
    public final static class ServerBuilder {
        private final int port;
        private int readBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private int writeBufferSize = DefaultSetting.DEFAULT_CLIENT_BUFFER_SIZE;
        private long readTimeoutMillis = DefaultSetting.DEFAULT_READ_TIMEOUT_MILLIS;
        private long writeTimeoutMillis = DefaultSetting.DEFAULT_WRITE_TIMEOUT_MILLIS;

        private ServerBuilder(int port) {
            this.port = port;
        }

        /**
         * The size of the buffer used to read from the socket channel. The default value is 8192 (8 KB)
         * @param readBufferSize readBufferSize
         * @return builder
         */
        public ServerBuilder setReadBufferSize(int readBufferSize) {
            this.readBufferSize = readBufferSize;
            return this;
        }

        /**
         * The size of the buffer used to write to the socket channel. The default value is 8192 (8 KB)
         * @param writeBufferSize writeBufferSize
         * @return builder
         */
        public ServerBuilder setWriteBufferSize(int writeBufferSize) {
            this.writeBufferSize = writeBufferSize;
            return this;
        }

        /**
         * The timeout in milliseconds for reading from the socket. The default value is 0 (no timeout)
         * @param readTimeoutMillis readTimeoutMillis
         * @return builder
         */
        public ServerBuilder setReadTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        /**
         * The timeout in milliseconds for reading from the socket. The default value is 10000 (10 seconds)
         * @param writeTimeoutMillis writeTimeoutMillis
         * @return builder
         */
        public ServerBuilder setWriteTimeoutMillis(long writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        /**
         * Build the {@link ProtoServerSocketChannel}
         * @return ProtoSocketChannel
         */
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
