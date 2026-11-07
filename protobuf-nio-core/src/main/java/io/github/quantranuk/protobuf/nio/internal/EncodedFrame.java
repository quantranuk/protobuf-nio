package io.github.quantranuk.protobuf.nio.internal;

import com.google.protobuf.MessageLite;

/**
 * A message already encoded as a frame, so that a broadcast encodes it once for every connection. The frame must not be
 * modified once queued.
 */
public record EncodedFrame(MessageLite message, byte[] frame) {

    public static EncodedFrame of(MessageLite message) {
        return new EncodedFrame(message, ProtobufSerializer.serialize(message));
    }
}
