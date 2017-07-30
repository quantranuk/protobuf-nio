package com.quantran.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

@FunctionalInterface
public interface MessageSentHandler {
    void onMessageSent(SocketAddress socketAddress, Message message);
}
