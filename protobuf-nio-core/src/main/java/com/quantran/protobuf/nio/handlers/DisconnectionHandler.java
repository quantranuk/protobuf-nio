package com.quantran.protobuf.nio.handlers;

import java.net.SocketAddress;

@FunctionalInterface
public interface DisconnectionHandler {
    void onDisconnected(SocketAddress socketAddress);
}
