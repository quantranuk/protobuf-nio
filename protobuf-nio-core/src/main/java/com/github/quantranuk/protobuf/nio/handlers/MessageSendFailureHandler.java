package com.github.quantranuk.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

/**
 * The handler to handle outgoing messages that are failed to be written to the socket
 */
@FunctionalInterface
public interface MessageSendFailureHandler {

    /**
     * This method is called when a message has failed to be written to the socket channel
     * @param socketAddress address of the remote host
     * @param message the protobuf message
     * @param t the exception thrown when attempting to write to the socket
     */
    void onMessageSendFailure(SocketAddress socketAddress, Message message, Throwable t);
}
