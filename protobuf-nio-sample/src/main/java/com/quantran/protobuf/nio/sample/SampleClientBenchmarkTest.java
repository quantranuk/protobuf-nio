package com.quantran.protobuf.nio.sample;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoChannelFactory;
import com.quantran.protobuf.nio.ProtoSocketChannel;
import org.quantran.tools.sbm.proto.HeartBeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;

public class SampleClientBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleClientBenchmarkTest.class);

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 3456;
    private static final int WARM_UP = 200;
    private static final int BENCHMARK_ITERATIONS = 1_000_000;

    private static MessageFactory messageFactory;
    private static ProtoSocketChannel clientChannel;
    private static long heartBeatResponseReceived = 0;
    private static long benchmarkStartTime = 0;
    private static CountDownLatch warmUpLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        messageFactory = new MessageFactory();
        clientChannel = ProtoChannelFactory.newClient(SERVER_HOST, SERVER_PORT).build();
        clientChannel.addMessageReceivedHandler(SampleClientBenchmarkTest::onMsgReceived);
        clientChannel.addMessageSendFailureHandler(SampleClientBenchmarkTest::onMsgSendFailure);
        clientChannel.connect();
        startBenchmark();
    }

    private static void startBenchmark() {
        warmUp();
        benchmarkStartTime = System.currentTimeMillis();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            clientChannel.sendMessage(messageFactory.createHeartBeatRequest());
        }
    }

    private static void warmUp() {
        for (int i = 0; i < WARM_UP; i++) {
            clientChannel.sendMessage(messageFactory.createHeartBeatRequest());
        }
        try {
            warmUpLatch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException("Unexpected interruption while waiting for warming up");
        }
    }

    private static void onMsgReceived(SocketAddress socketAddress, Message message) {
        if (!(message instanceof HeartBeat.HeartBeatResponse)) {
            LOGGER.warn("Received unknown message from {}:\n{}", socketAddress, message);
            return;
        }

        heartBeatResponseReceived++;
        if (heartBeatResponseReceived == WARM_UP) {
            warmUpLatch.countDown();
        } else if (heartBeatResponseReceived == WARM_UP + BENCHMARK_ITERATIONS) {
            long benchmarkEndTime = System.currentTimeMillis();
            BigDecimal benchmarkIterationBd = BigDecimal.valueOf(BENCHMARK_ITERATIONS);
            long totalMillis = benchmarkEndTime - benchmarkStartTime;
            LOGGER.info("Sending and receiving {} message took {} milliseconds", BENCHMARK_ITERATIONS, totalMillis);
            LOGGER.info("Throughput: {} messages per millisecond (round-trip)", benchmarkIterationBd.divide(BigDecimal.valueOf(totalMillis), 2, BigDecimal.ROUND_HALF_UP));
            LOGGER.info("Average: {} microseconds per message", BigDecimal.valueOf(totalMillis * 1000).divide(benchmarkIterationBd, 2, BigDecimal.ROUND_HALF_UP));
            clientChannel.disconnect();
        }
    }

    private static void onMsgSendFailure(SocketAddress socketAddress, Message message, Throwable t) {
        LOGGER.error("An error has occurred while sending msg " + message.getClass().getName() + " to " + socketAddress, t);
    }

}
