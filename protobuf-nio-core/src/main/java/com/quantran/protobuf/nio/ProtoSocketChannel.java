package com.quantran.protobuf.nio;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.handlers.ConnectionHandler;
import com.quantran.protobuf.nio.handlers.DisconnectionHandler;
import com.quantran.protobuf.nio.handlers.MessageReceivedHandler;
import com.quantran.protobuf.nio.handlers.MessageSentHandler;
import com.quantran.protobuf.nio.handlers.MessageSendFailureHandler;

public interface ProtoSocketChannel {

    void connect();
    void disconnect();
    void sendMessage(Message message);

    void addConnectionHandler(ConnectionHandler handler);
    void addDisconnectionHandler(DisconnectionHandler handler);
    void addMessageReceivedHandler(MessageReceivedHandler handler);
    void addMessageSentHandler(MessageSentHandler handler);
    void addMessageSendFailureHandler(MessageSendFailureHandler handler);

    void removeConnectionHandler(ConnectionHandler handler);
    void removeDisconnectionHandler(DisconnectionHandler handler);
    void removeMessageReceivedHandler(MessageReceivedHandler handler);
    void removeMessageSentHandler(MessageSentHandler handler);
    void removeMessageSendFailureHandler(MessageSendFailureHandler handler);

}