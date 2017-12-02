package com.github.quantranuk.protobuf.nio;

import com.github.quantranuk.protobuf.nio.handlers.ConnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.DisconnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageReceivedHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSendFailureHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSentHandler;
import com.google.protobuf.Message;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collection;

/**
 * A server to send and receive protobuf messages
 */
public interface ProtoServerSocketChannel {

    /**
     * Start listening to the port and accepting new connections. Use the method {@link #addConnectionHandler(ConnectionHandler)} to handle new accepted connections
     * @throws IOException if the server is unable to bind to the port
     */
    void start() throws IOException;

    /**
     * Disconnect all established connection and stop listening to the port. After the server is stopped it will not be started again and should be discarded
     */
    void stop();

    /**
     * Send a message to a client
     * @param socketAddress the socket address of the client
     * @param message the protobuf message
     * @throws IllegalStateException if the socket address is not connected
     */
    void sendMessage(SocketAddress socketAddress, Message message);

    /**
     * <p>Send a message to all connected clients.</p>
     * @param message the protobuf message
     */
    void sendMessageToAll(Message message);

    /**
     * Get the list of all connected clients
     * @return the list of all connected addresses
     */
    Collection<SocketAddress> getConnectedAddresses();

    /**
     * Check if a socket address is connected
     * @param socketAddress socketAddress
     * @return true if the server is connected to the address
     */
    boolean isConnected(SocketAddress socketAddress);

    /**
     * Register a handler to be called when a new connection is accepted. Handler must be registered before {@link #start()} method is called
     * @param handler connection handler
     */
    void addConnectionHandler(ConnectionHandler handler);

    /**
     * Register a handler to be called when a connection is disconnected or failed to be accepted for any reason. Handler must be registered before {@link #start()} method is called
     * @param handler disconnection handler
     */
    void addDisconnectionHandler(DisconnectionHandler handler);

    /**
     * Register a handler to be called when a message is received from the server
     * @param handler handler for incoming messages
     */
    void addMessageReceivedHandler(MessageReceivedHandler handler);

    /**
     * Register a handler to be called after a message has been successfully written to the socket
     * @param handler handler for successfully sent messages
     */
    void addMessageSentHandler(MessageSentHandler handler);

    /**
     * Register a handler to be called if a message cannot be written to the socket for any reason
     * @param handler handler for messages that are failed to be sent
     */
    void addMessageSendFailureHandler(MessageSendFailureHandler handler);

    /**
     * Remove the connection handler
     * @param handler connection handler
     */
    void removeConnectionHandler(ConnectionHandler handler);

    /**
     * Remove the disconnection handler
     * @param handler disconnection handler
     */
    void removeDisconnectionHandler(DisconnectionHandler handler);

    /**
     * Remove the handler for incoming messages
     * @param handler handler
     */
    void removeMessageReceivedHandler(MessageReceivedHandler handler);

    /**
     * Remove the handler for successfully sent messages
     * @param handler handler
     */
    void removeMessageSentHandler(MessageSentHandler handler);

    /**
     * Remove the handler for messages that are failed to be sent
     * @param handler handler
     */
    void removeMessageSendFailureHandler(MessageSendFailureHandler handler);

}