package io.github.quantranuk.protobuf.nio.sample;

/**
 * Builds the sample messages. Stateless, so it's safe to call from any connection's thread: in 2.0, handlers for
 * different connections run concurrently.
 */
public final class MessageFactory {

    private MessageFactory() {
    }

    public static HeartBeat.HeartBeatRequest heartBeatRequest(long sequence) {
        return HeartBeat.HeartBeatRequest.newBuilder()
                .setRequestTimeMillis(System.currentTimeMillis())
                .setRequestMessage("Ping-" + sequence)
                .build();
    }

    public static HeartBeat.HeartBeatResponse heartBeatResponse(HeartBeat.HeartBeatRequest request) {
        return HeartBeat.HeartBeatResponse.newBuilder()
                .setRequestTimeMillis(request.getRequestTimeMillis())
                .setResponseTimeMillis(System.currentTimeMillis())
                .setResponseMessage("Pong")
                .build();
    }

}
