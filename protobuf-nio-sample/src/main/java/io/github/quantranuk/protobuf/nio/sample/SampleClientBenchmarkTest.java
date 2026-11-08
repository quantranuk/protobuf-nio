package io.github.quantranuk.protobuf.nio.sample;

import io.github.quantranuk.protobuf.nio.ProtoClient;
import io.github.quantranuk.protobuf.nio.ProtoClientListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Pipelined request/response throughput over one connection to {@link SampleServer}: each round sends 1,000,000
 * {@code HeartBeatRequest}s without waiting, then waits for all 1,000,000 responses.
 */
public class SampleClientBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleClientBenchmarkTest.class);

    private static final int WARM_UP = 200_000;
    private static final int MESSAGES_PER_ROUND = 1_000_000;
    private static final int ROUNDS = 10;

    // Written by the main thread before each round, read by the client's reader thread
    private static volatile CountDownLatch roundDone;
    private static volatile long expectedResponses;
    // Reader thread only: onMessage calls for one connection never overlap
    private static long responses;

    public static void main(String[] args) throws Exception {
        ProtoClientListener listener = ProtoClientListener.builder()
                .onMessage(HeartBeat.HeartBeatResponse.class, (client, response) -> {
                    if (++responses == expectedResponses) {
                        roundDone.countDown();
                    }
                })
                .onSendFailure((client, message, cause) -> LOGGER.error("Couldn't send a message: {}", cause.reason()))
                .build();

        try (ProtoClient client = ProtoClient.builder("localhost", SampleServer.PORT).listener(listener).build()) {
            client.connect();
            run(client, WARM_UP);
            LOGGER.info("Warmed up with {} round trips", WARM_UP);
            for (int round = 1; round <= ROUNDS; round++) {
                long nanos = run(client, MESSAGES_PER_ROUND);
                LOGGER.info("Round {}: {} round trips in {} ms, {} round trips per millisecond", round,
                        MESSAGES_PER_ROUND, nanos / 1_000_000, Math.round(MESSAGES_PER_ROUND * 1e6 / nanos));
            }
        }
    }

    private static long run(ProtoClient client, int messages) throws InterruptedException {
        roundDone = new CountDownLatch(1);
        expectedResponses += messages;
        long start = System.nanoTime();
        for (int i = 0; i < messages; i++) {
            // Under the default OverflowPolicy.BLOCK, send waits for room when the queue is full
            client.send(MessageFactory.heartBeatRequest(i));
        }
        roundDone.await();
        return System.nanoTime() - start;
    }

}
