package com.quantran.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

@FunctionalInterface
public interface MessageReceivedHandler {
    void onMessageReceived(SocketAddress socketAddress, Message message);
}
