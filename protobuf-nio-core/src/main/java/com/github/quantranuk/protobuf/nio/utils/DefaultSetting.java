package com.github.quantranuk.protobuf.nio.utils;

public final class DefaultSetting {

    public static final int DEFAULT_SERVER_BUFFER_SIZE = 8192;
    public static final int DEFAULT_CLIENT_BUFFER_SIZE = 8192;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 0;
    public static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10000;

    public static final int MAX_WRITE_MESSAGE_QUEUE_SIZE = 10_000_000;

    private DefaultSetting() {
    }

}
