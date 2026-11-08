package io.github.quantranuk.protobuf.nio.sample;

import io.github.quantranuk.protobuf.nio.ProtoServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Answers every {@code HeartBeatRequest} with a {@code HeartBeatResponse}. Start it before the sample clients; stop it
 * with Ctrl+C.
 */
public class SampleServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleServer.class);

    static final int PORT = 3456;

    public static void main(String[] args) throws IOException, InterruptedException {
        ProtoServer server = ProtoServer.builder(PORT)
                // Runs on the connection's own reader thread: replies go back on the same connection
                .onMessage(HeartBeat.HeartBeatRequest.class,
                        (connection, request) -> connection.send(MessageFactory.heartBeatResponse(request)))
                .onSendFailure((connection, message, cause) ->
                        LOGGER.warn("Couldn't send {} to {}: {}", message.getClass().getSimpleName(), connection, cause.reason()))
                .onDisconnected((connection, reason, cause) -> {
                    if (cause != null) {
                        LOGGER.warn("{} disconnected: {}", connection, reason, cause);
                    }
                })
                .build();
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOGGER.info("Listening on {}", server.localAddress());
        server.awaitClosed();
    }

}
