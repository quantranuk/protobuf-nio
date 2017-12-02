package com.github.quantranuk.protobuf.nio.utils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ByteUtils {

    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;
    private static final String EMPTY_STRING = "";

    private ByteUtils() {}

    public static void writeString(byte[] b, int startPosition, String str) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition);
        } else if (str == null || str.isEmpty()) {
            return;
        } else if (b.length < (startPosition + str.length())) {
            throw new IllegalArgumentException("Invalid bytes array length of " + b.length + " for start position: " + startPosition);
        }
        System.arraycopy(CHARSET.encode(str).array(), 0, b, startPosition, str.length());
    }

    public static void writeInteger(byte[] b, int startPosition, int integer) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition);
        } else if (b.length < (startPosition + Integer.BYTES)) {
            throw new IllegalArgumentException("Invalid bytes array length of " + b.length + " for start position: " + startPosition);
        }

        ByteBuffer buffer = ByteBuffer.wrap(b);
        buffer.putInt(startPosition, integer);
    }

    public static void writeLong(byte[] b, int startPosition, long lng) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition);
        } else if (b.length < (startPosition + Long.BYTES)) {
            throw new IllegalArgumentException("Invalid bytes array length of " + b.length + " for start position: " + startPosition);
        }

        long l = lng;
        for (int i = Long.BYTES - 1 + startPosition; i >= startPosition; i--) {
            b[i] = (byte)(l & 0xFF);
            l >>= Long.BYTES;
        }
    }

    public static String readString(byte[] bytes, int startPosition, int length) {
        if (startPosition < 0 || length < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition + " length: " + length);
        } else if (bytes.length < (startPosition + length)) {
            throw new IllegalArgumentException("Invalid bytes array length of " + bytes.length + " for start position: " + startPosition);
        } else if (length == 0) {
            return EMPTY_STRING;
        }

        return CHARSET.decode(ByteBuffer.wrap(bytes, startPosition, length)).toString();
    }

    public static long readLong(byte[] b, int startPosition) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition);
        } else if (b.length < (startPosition + Long.BYTES)) {
            throw new IllegalArgumentException("Invalid bytes array length of " + b.length + " for start position: " + startPosition);
        }

        long result = 0;
        for (int i = startPosition; i < Long.BYTES + startPosition; i++) {
            result <<= Long.BYTES;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    public static int readInteger(byte[] b, int startPosition) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("Invalid start position: " + startPosition);
        } else if (b.length < (startPosition + Integer.BYTES)) {
            throw new IllegalArgumentException("Invalid bytes array length of " + b.length + " for start position: " + startPosition);
        }

        ByteBuffer buffer = ByteBuffer.wrap(b);
        return buffer.getInt(startPosition);
    }

}
