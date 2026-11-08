package io.github.quantranuk.protobuf.nio.sample;

import io.github.quantranuk.protobuf.nio.ProtoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Sends one {@code HeartBeatRequest} to {@link SampleServer} and prints the response. */
public class SampleClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleClient.class);

    public static void main(String[] args) throws Exception {
        CompletableFuture<HeartBeat.HeartBeatResponse> response = new CompletableFuture<>();
        ProtoClient.Builder builder = ProtoClient.builder("localhost", SampleServer.PORT)
                .onMessage(HeartBeat.HeartBeatResponse.class, (client, message) -> response.complete(message))
                .onSendFailure((client, message, cause) ->
                        LOGGER.error("Couldn't send {}: {}", message.getClass().getSimpleName(), cause.reason()))
                .onDisconnected((client, reason, cause) ->
                        response.completeExceptionally(new IOException("Disconnected: " + reason, cause)));

        try (ProtoClient client = builder.build()) {
            client.connect();
            client.send(MessageFactory.heartBeatRequest(1));
            HeartBeat.HeartBeatResponse heartBeatResponse = response.get(10, TimeUnit.SECONDS);
            LOGGER.info("Received from {} after {} ms:\n{}", client.remoteAddress(),
                    System.currentTimeMillis() - heartBeatResponse.getRequestTimeMillis(), heartBeatResponse);
        } catch (TimeoutException e) {
            LOGGER.error("No response within 10 seconds");
        }
    }

}
