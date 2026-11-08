package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.ProtoConnection;
import io.github.quantranuk.protobuf.nio.ProtoServer;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;

import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.Socket;

/** {@link ProtoConnection} on top of a {@link Connection}, registered with its {@link DefaultProtoServer}. */
final class DefaultProtoConnection implements ProtoConnection {

    private final DefaultProtoServer server;
    private final Callbacks<ProtoConnection> callbacks;
    final Connection connection;
    private volatile Object attachment;

    DefaultProtoConnection(DefaultProtoServer server, Socket socket, ConnectionConfig config, Callbacks<ProtoConnection> callbacks) {
        this.server = server;
        this.callbacks = callbacks;
        this.connection = new Connection(socket, config, new Events());
    }

    @Override
    public long id() {
        return connection.id();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return connection.remoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return connection.localAddress();
    }

    @Override
    public boolean isOpen() {
        return connection.isOpen();
    }

    @Override
    public SSLSession tlsSession() {
        return connection.tlsSession();
    }

    @Override
    public boolean send(MessageLite message) {
        return connection.send(message);
    }

    boolean send(EncodedFrame frame) {
        return connection.send(frame);
    }

    @Override
    public int pendingMessages() {
        return connection.pendingMessages();
    }

    @Override
    public long pendingBytes() {
        return connection.pendingBytes();
    }

    @Override
    public void close() {
        connection.close();
    }

    /** For the server's close: a connection still in its handshake gets to finish it, then closes gracefully. */
    void closeForShutdown() {
        connection.closeAfterHandshake();
    }

    @Override
    public DisconnectReason awaitClosed() throws InterruptedException {
        return connection.awaitClosed();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attachment() {
        return (T) attachment;
    }

    @Override
    public void attach(Object attachment) {
        this.attachment = attachment;
    }

    boolean isOwnThread() {
        return connection.isListenerThread();
    }

    @Override
    public String toString() {
        return "ProtoConnection#" + id() + "[" + remoteAddress() + "]";
    }

    private final class Events implements ConnectionListener {
        @Override
        public void onConnected() {
            server.opened(DefaultProtoConnection.this);
            callbacks.connected(DefaultProtoConnection.this);
        }

        @Override
        public void onMessage(MessageLite message) {
            callbacks.message(DefaultProtoConnection.this, message);
        }

        @Override
        public void onRawMessage(Object handler, ByteBuffer payload) {
            ((ProtoServer.RawMessageHandler) handler).onMessage(DefaultProtoConnection.this, payload);
        }

        @Override
        public void onSendFailure(MessageLite message, SendException cause) {
            callbacks.sendFailure(DefaultProtoConnection.this, message, cause);
        }

        @Override
        public void onDisconnected(DisconnectReason reason, Throwable cause) {
            server.disconnected(DefaultProtoConnection.this);
            callbacks.disconnected(DefaultProtoConnection.this, reason, cause);
        }
    }
}
