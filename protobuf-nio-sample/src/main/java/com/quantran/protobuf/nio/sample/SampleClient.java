package com.quantran.protobuf.nio.sample;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoChannelFactory;
import com.quantran.protobuf.nio.ProtoSocketChannel;
import org.quantran.tools.sbm.proto.HeartBeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;

public class SampleClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleClient.class);

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 3456;

    private static MessageFactory messageFactory;
    private static ProtoSocketChannel clientChannel;

    public static void main(String[] args) {
        messageFactory = new MessageFactory();
        clientChannel = ProtoChannelFactory.newClient(SERVER_HOST, SERVER_PORT).build();
        clientChannel.addMessageReceivedHandler(SampleClient::onMsgReceived);
        clientChannel.addMessageSendFailureHandler(SampleClient::onMsgSendFailure);
        clientChannel.connect();
        clientChannel.sendMessage(messageFactory.createHeartBeatRequest());
    }

    private static void onMsgReceived(SocketAddress socketAddress, Message message) {
        if (message instanceof HeartBeat.HeartBeatResponse) {
            HeartBeat.HeartBeatResponse heartBeatResponse = (HeartBeat.HeartBeatResponse) message;
            LOGGER.info("Received message from {}:\n{}", socketAddress, heartBeatResponse);
            clientChannel.disconnect();
        } else {
            LOGGER.warn("Received unknown message from {}:\n{}", socketAddress, message);
        }
    }

    private static void onMsgSendFailure(SocketAddress socketAddress, Message message, Throwable t) {
        LOGGER.error("An error has occurred while sending msg " + message.getClass().getName(), t);
    }

}
