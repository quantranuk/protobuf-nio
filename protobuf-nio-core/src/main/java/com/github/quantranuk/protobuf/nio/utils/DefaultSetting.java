package com.github.quantranuk.protobuf.nio.utils;

public final class DefaultSetting {

    public static final int DEFAULT_SERVER_BUFFER_SIZE = 65536;
    public static final int DEFAULT_CLIENT_BUFFER_SIZE = 65536;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 0;
    public static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10000;

    public static final int MAX_WRITE_MESSAGE_QUEUE_SIZE = 10_000_000;

    /** Default upper bound for the protobuf payload of a single incoming message (64 MiB) */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 64 * 1024 * 1024;

    /** Upper bound for the length of the protobuf class name in an incoming message header */
    public static final int MAX_PROTO_CLASS_NAME_LENGTH = 4096;

    private DefaultSetting() {
    }

}
