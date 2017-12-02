package com.github.quantranuk.protobuf.nio.sample;

import com.github.quantranuk.protobuf.nio.ProtoChannelFactory;
import com.github.quantranuk.protobuf.nio.ProtoServerSocketChannel;
import com.github.quantranuk.protonio.sample.HeartBeat;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

public class SampleServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleServer.class);
    private static final int PORT = 3456;

    private static ProtoServerSocketChannel serverChannel;
    private static MessageFactory messageFactory;

    public static void main(String[] args) throws IOException {
        messageFactory = new MessageFactory();
        serverChannel = ProtoChannelFactory.newServer(PORT).build();
        serverChannel.addMessageReceivedHandler(SampleServer::onMsgReceived);
        serverChannel.addMessageSendFailureHandler(SampleServer::onMsgSendFailure);

        serverChannel.start();
    }

    private static void onMsgReceived(SocketAddress address, Message message) {
        if (message instanceof HeartBeat.HeartBeatRequest) {
            HeartBeat.HeartBeatRequest heartBeatRequest = (HeartBeat.HeartBeatRequest) message;
            long heartBeatRequestRequestTimeMillis = heartBeatRequest.getRequestTimeMillis();
            HeartBeat.HeartBeatResponse heartBeatResponse = messageFactory.createHeartBeatResponse(heartBeatRequestRequestTimeMillis);
            serverChannel.sendMessage(address, heartBeatResponse);
        } else {
            LOGGER.info("Received unknown message: " + message.toString());
        }
    }

    private static void onMsgSendFailure(SocketAddress socketAddress, Message message, Throwable t) {
        LOGGER.error("An error has occurred while sending message " + message.getClass().getName(), t);
    }

}
