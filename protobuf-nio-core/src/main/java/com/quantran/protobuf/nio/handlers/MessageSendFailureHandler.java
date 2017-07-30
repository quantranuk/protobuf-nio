package com.quantran.protobuf.nio.handlers;

import com.google.protobuf.Message;

import java.net.SocketAddress;

@FunctionalInterface
public interface MessageSendFailureHandler {
    void onMessageSendFailure(SocketAddress socketAddress, Message message, Throwable t);
}
