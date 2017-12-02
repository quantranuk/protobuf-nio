package com.github.quantranuk.protobuf.nio.handlers;

import java.net.SocketAddress;

/**
 * The handler to handle new connection
 */
@FunctionalInterface
public interface ConnectionHandler {

    /**
     * This method will be called when a new connection is established
     * @param socketAddress the address of the remote host
     */
    void onConnected(SocketAddress socketAddress);
}
