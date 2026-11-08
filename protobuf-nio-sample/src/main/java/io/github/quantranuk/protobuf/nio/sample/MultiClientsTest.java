package io.github.quantranuk.protobuf.nio.sample;

import io.github.quantranuk.protobuf.nio.ProtoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connects 15,000 clients to {@link SampleServer}; each sends one request and waits for its response.
 * <p>
 * Each connection uses two virtual threads and one file descriptor on each side, so both processes need a file
 * descriptor limit above 15,000 ({@code ulimit -n}). The clients also use 15,000 ephemeral ports.
 */
public class MultiClientsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiClientsTest.class);

    private static final int NO_OF_CLIENTS = 15_000;
    private static final int CONNECT_BATCH = 500;

    public static void main(String[] args) throws Exception {
        CountDownLatch responses = new CountDownLatch(NO_OF_CLIENTS);
        // One builder for every client: they share its callbacks, which run concurrently for different clients, so they
        // must be thread-safe
        ProtoClient.Builder builder = ProtoClient.builder("localhost", SampleServer.PORT)
                .onMessage(HeartBeat.HeartBeatResponse.class, (client, response) -> responses.countDown())
                .onSendFailure((client, message, cause) -> LOGGER.error("{} couldn't send: {}", client, cause.reason()));

        List<ProtoClient> clients = new ArrayList<>(NO_OF_CLIENTS);
        long start = System.nanoTime();
        try {
            // Connect in batches, so that the server's accept backlog (1024 by default) doesn't overflow
            List<CompletableFuture<ProtoClient>> batch = new ArrayList<>(CONNECT_BATCH);
            for (int i = 0; i < NO_OF_CLIENTS; i++) {
                ProtoClient client = builder.build();
                clients.add(client);
                batch.add(client.connectAsync());
                if (batch.size() == CONNECT_BATCH || i == NO_OF_CLIENTS - 1) {
                    CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
                    batch.clear();
                }
            }
            LOGGER.info("All {} clients are connected ({} ms)", NO_OF_CLIENTS, millisSince(start));

            start = System.nanoTime();
            LOGGER.info("Sending a heartbeat request from every client...");
            for (ProtoClient client : clients) {
                client.send(MessageFactory.heartBeatRequest(client.id()));
            }
            if (responses.await(60, TimeUnit.SECONDS)) {
                LOGGER.info("All {} responses received ({} ms)", NO_OF_CLIENTS, millisSince(start));
            } else {
                LOGGER.error("Only {} of {} responses received", NO_OF_CLIENTS - responses.getCount(), NO_OF_CLIENTS);
            }
        } finally {
            start = System.nanoTime();
            // close() waits for the peer to close too, so close the clients in parallel
            try (ExecutorService closer = Executors.newVirtualThreadPerTaskExecutor()) {
                clients.forEach(client -> closer.execute(client::close));
            }
            LOGGER.info("Closed {} clients ({} ms)", clients.size(), millisSince(start));
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

}
