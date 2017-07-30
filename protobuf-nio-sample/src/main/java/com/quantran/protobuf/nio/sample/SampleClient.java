package com.quantran.protobuf.nio.sample;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoSocketChannel;
import com.quantran.protobuf.nio.impl.AsyncProtoSocketChannel;
import org.quantran.tools.sbm.proto.HeartBeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;

public class SampleClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleClient.class);

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 3456;

    private static MessageFactory messageFactory;
    private static ProtoSocketChannel protoSocketChannel;

    public static void main(String[] args) {
        messageFactory = new MessageFactory();
        protoSocketChannel = createProtobufSocketChannel(SERVER_HOST, SERVER_PORT);
        protoSocketChannel.connect();
        protoSocketChannel.sendMessage(messageFactory.createHeartBeatRequest());
    }

    private static ProtoSocketChannel createProtobufSocketChannel(String host, int port) {
        AsyncProtoSocketChannel channel = new AsyncProtoSocketChannel(host, port);
        channel.addMessageReceivedHandler(SampleClient::onMsgReceived);
        channel.addMessageSendFailureHandler((socketAddress, message, e) -> LOGGER.error("An error has occurred while sending msg " + message.getClass().getName(), e));
        channel.init();
        return channel;
    }

    private static void onMsgReceived(SocketAddress socketAddress, Message message) {
        if (message instanceof HeartBeat.HeartBeatResponse) {
            HeartBeat.HeartBeatResponse heartBeatResponse = (HeartBeat.HeartBeatResponse) message;
            LOGGER.info("Received message from {}:\n{}", socketAddress, heartBeatResponse);
        } else {
            LOGGER.warn("Received unknown message from {}:\n{}", socketAddress, message);
        }
    }

}
