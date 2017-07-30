package com.quantran.protobuf.nio.sample;

import org.quantran.tools.sbm.proto.HeartBeat;

public final class MessageFactory {

    private final HeartBeat.HeartBeatRequest.Builder heartbeatRequestBuilder;
    private final HeartBeat.HeartBeatResponse.Builder heartbeatResponseBuilder;
    private long requestCount = 0;
    private long responseCount = 0;

    public MessageFactory() {
        heartbeatRequestBuilder = HeartBeat.HeartBeatRequest.newBuilder();
        heartbeatResponseBuilder = HeartBeat.HeartBeatResponse.newBuilder();
    }

    public HeartBeat.HeartBeatRequest createHeartBeatRequest() {
        return heartbeatRequestBuilder
                .setRequestTimeMillis(System.currentTimeMillis())
                .setRequestMessage("Ping-" + requestCount++)
                .build();
    }

    public HeartBeat.HeartBeatResponse createHeartBeatResponse(long heartBeatRequestRequestTimeMillis) {
        return heartbeatResponseBuilder
                .setRequestTimeMillis(heartBeatRequestRequestTimeMillis)
                .setResponseTimeMillis(System.currentTimeMillis())
                .setResponseMessage("Pong-" + responseCount++)
                .build();
    }

}
