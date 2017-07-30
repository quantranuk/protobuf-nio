package com.quantran.protobuf.nio.sample;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoServerSocketChannel;
import com.quantran.protobuf.nio.impl.AsyncProtoServerSocketChannel;
import org.quantran.tools.sbm.proto.HeartBeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

public class SampleServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleServer.class);
    private static final int PORT = 3456;

    private static ProtoServerSocketChannel serverSocketChannel;
    private static MessageFactory messageFactory;

    public static void main(String[] args) {
        messageFactory = new MessageFactory();
        serverSocketChannel = createServer();
        try {
            serverSocketChannel.start();
        } catch (IOException e) {
            LOGGER.error("An error has occurred while starting " + serverSocketChannel);
        }
    }

    private static ProtoServerSocketChannel createServer() {
        AsyncProtoServerSocketChannel channel = new AsyncProtoServerSocketChannel(PORT);
        channel.addMessageReceivedHandler(SampleServer::onMsgReceived);
        channel.addMessageSendFailureHandler((address, message, e) -> LOGGER.error("An error has occurred while sending message " + message.getClass().getName(), e));
        channel.init();
        return channel;
    }

    private static void onMsgReceived(SocketAddress address, Message message) {
        if (message instanceof HeartBeat.HeartBeatRequest) {
            HeartBeat.HeartBeatRequest heartBeatRequest = (HeartBeat.HeartBeatRequest) message;
            long heartBeatRequestRequestTimeMillis = heartBeatRequest.getRequestTimeMillis();
            HeartBeat.HeartBeatResponse heartBeatResponse = messageFactory.createHeartBeatResponse(heartBeatRequestRequestTimeMillis);
            serverSocketChannel.sendMessage(address, heartBeatResponse);
        } else {
            LOGGER.info("Received unknown message: " + message.toString());
        }
    }

}
