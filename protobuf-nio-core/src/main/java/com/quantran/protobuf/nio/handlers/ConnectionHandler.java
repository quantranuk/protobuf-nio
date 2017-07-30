package com.quantran.protobuf.nio.handlers;

import java.net.SocketAddress;

@FunctionalInterface
public interface ConnectionHandler {
    void onConnected(SocketAddress socketAddress);
}
