package com.github.quantranuk.protobuf.nio.handlers;

import java.net.SocketAddress;

/**
 * The handler to handle connection that are disconnected. In case of a client side, this handler is also used for handling connection failure
 */
@FunctionalInterface
public interface DisconnectionHandler {

    /**
     * This method will be called when a connection is disconnected. In case of a client side, this method is also called when a connection attempt to the server has failed.
     * @param socketAddress the address of the remote host
     */
    void onDisconnected(SocketAddress socketAddress);
}
