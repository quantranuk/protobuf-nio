package com.github.quantranuk.protobuf.nio.sample;

import com.github.quantranuk.protobuf.nio.ProtoChannelFactory;
import com.github.quantranuk.protobuf.nio.ProtoSocketChannel;
import com.github.quantranuk.protonio.sample.HeartBeat;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MultiClientsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiClientsTest.class);

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 3456;
    private static final int NO_OF_CLIENTS = 15000;
    private static final List<ProtoSocketChannel> CLIENT_CHANNELS = new ArrayList<>(NO_OF_CLIENTS);

    private static MessageFactory messageFactory = new MessageFactory();
    private static long heartBeatResponseReceived = 0;

    private static ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private static ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        for (int i = 0; i < NO_OF_CLIENTS; i++) {
            ProtoSocketChannel clientChannel = ProtoChannelFactory
                    .newClient(SERVER_HOST, SERVER_PORT)
                    .setReadExecutor(readExecutor)
                    .setWriteExecutor(writeExecutor)
                    .build();

            final int clientCount = i;
            clientChannel.addConnectionHandler(address -> LOGGER.info("Client {} is connected", clientCount));
            clientChannel.addMessageReceivedHandler(MultiClientsTest::onMessageReceived);
            CLIENT_CHANNELS.add(clientChannel);
            clientChannel.connect();
        }
        LOGGER.info("All {} clients are connected", NO_OF_CLIENTS);

        LOGGER.info("Sending heartbeat requests from all clients...");
        HeartBeat.HeartBeatRequest heartBeatRequest = messageFactory.createHeartBeatRequest();
        CLIENT_CHANNELS.forEach(channel -> channel.sendMessage(heartBeatRequest));
    }

    private static void onMessageReceived(SocketAddress socketAddress, Message message) {
        if (message instanceof HeartBeat.HeartBeatResponse) {
            heartBeatResponseReceived++;
            if (heartBeatResponseReceived == NO_OF_CLIENTS) {
                LOGGER.info("All HeartBeat response messages have been received. Closing all clients..");
                CLIENT_CHANNELS.forEach(ProtoSocketChannel::disconnect);
                readExecutor.shutdown();
                writeExecutor.shutdown();
            }
        } else {
            LOGGER.warn("Received unknown message from {}:\n{}", socketAddress, message);
        }

    }

}
