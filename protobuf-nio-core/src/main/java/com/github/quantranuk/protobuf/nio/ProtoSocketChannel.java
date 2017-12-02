package com.github.quantranuk.protobuf.nio;

import com.github.quantranuk.protobuf.nio.handlers.ConnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.DisconnectionHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageReceivedHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSendFailureHandler;
import com.github.quantranuk.protobuf.nio.handlers.MessageSentHandler;
import com.google.protobuf.Message;

/**
 * A client to send and receive protobuf messages
 */
public interface ProtoSocketChannel {

    /**
     * <p>
     *     Connect to a server. The implementation of this interface should provide the setters of the destination (host/port)
     * </p>
     * <p>
     *     This is a non-blocking call. To handle when the connection is actually established/failed to be established,
     *     please use {@link #addConnectionHandler(ConnectionHandler)} and {@link #addDisconnectionHandler(DisconnectionHandler)}
     * </p>
     */
    void connect();

    /**
     * <p>
     * Disconnect from the host/port. After the connection is disconnected, this object cannot be reused and must be discarded. </p>
     *
     * <p>
     * This is a non-blocking call. To handle when the connection is actually established/failed to be established,
     * please use {@link #addConnectionHandler(ConnectionHandler)} and {@link #addDisconnectionHandler(DisconnectionHandler)} </p>
     */
    void disconnect();

    /**
     * Send a protobuf message to the server
     * @param message the protobuf message
     */
    void sendMessage(Message message);

    /**
     * Register a handler to be called when the connection is established. Handler must be registered before {@link #connect()} is called
     * @param handler connection handler
     */
    void addConnectionHandler(ConnectionHandler handler);

    /**
     * Register a handler to be called when the connection is either disconnected or failed to be connected. Handler must be registered before {@link #connect()} is called
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