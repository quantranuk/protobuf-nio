package com.github.quantranuk.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

/**
 * The handler to handle outgoing messages that have been successfully written to the socket
 */
@FunctionalInterface
public interface MessageSentHandler {

    /**
     * This method is called when a message has been completely and successfully written to the socket channel
     * @param socketAddress address of the remote host
     * @param message the protobuf message
     */
    void onMessageSent(SocketAddress socketAddress, Message message);
}
