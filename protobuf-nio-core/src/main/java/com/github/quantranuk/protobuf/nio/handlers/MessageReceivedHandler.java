package com.github.quantranuk.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

/**
 * The handler to handle incoming messages
 */
@FunctionalInterface
public interface MessageReceivedHandler {

    /**
     * Handle an incoming message
     * @param socketAddress address of the remote host that sent the message
     * @param message the protobuf message
     */
    void onMessageReceived(SocketAddress socketAddress, Message message);
}
