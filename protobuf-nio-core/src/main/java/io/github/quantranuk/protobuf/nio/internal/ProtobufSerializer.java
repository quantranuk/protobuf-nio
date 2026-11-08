package io.github.quantranuk.protobuf.nio.internal;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Encodes protobuf messages as frames. Each frame is:
 * <ul>
 *     <li>int: {@link #SIGNATURE}, so the receiver can quickly detect corrupt or foreign data</li>
 *     <li>int: the length of the class name</li>
 *     <li>int: the length of the protobuf payload</li>
 *     <li>bytes: the fully qualified Java class name of the message, in ISO-8859-1</li>
 *     <li>bytes: the protobuf payload</li>
 * </ul>
 * Integers are big-endian. A header with both lengths 0 is a heartbeat (see {@link Protocol#HEARTBEAT_FRAME}).
 * Decoding is done by {@link Connection}'s reader, with class names resolved by {@link MessageTypes}.
 */
public final class ProtobufSerializer {

    public static final int SIGNATURE = 0x7A6B5C4D;
    public static final int HEADER_LENGTH = 3 * Integer.BYTES;

    static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    // The ISO-8859-1 class name sent with every message, encoded once per class
    private static final ClassValue<byte[]> ENCODED_CLASS_NAMES = new ClassValue<>() {
        @Override
        protected byte[] computeValue(Class<?> type) {
            return type.getName().getBytes(CHARSET);
        }
    };

    private ProtobufSerializer() {
    }

    /**
     * @param message the protobuf message
     * @return the whole frame
     */
    public static byte[] serialize(MessageLite message) {
        byte[] bytes = new byte[getSerializedSize(message)];
        serializeInto(message, ByteBuffer.wrap(bytes));
        return bytes;
    }

    /**
     * Encodes a frame directly into a heap buffer, without intermediate copies. The buffer must have at least
     * {@link #getSerializedSize(MessageLite)} bytes remaining, and its position is advanced by that many bytes.
     * @param message the protobuf message
     * @param target the buffer to write into
     */
    public static void serializeInto(MessageLite message, ByteBuffer target) {
        byte[] encodedClassName = ENCODED_CLASS_NAMES.get(message.getClass());
        int payloadLength = message.getSerializedSize();
        target.putInt(SIGNATURE);
        target.putInt(encodedClassName.length);
        target.putInt(payloadLength);
        target.put(encodedClassName);
        int payloadStart = target.position();
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(target);
            message.writeTo(output);
            output.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize " + message.getClass().getName(), e);
        }
        if (target.position() - payloadStart != payloadLength) {
            throw new IllegalStateException("Serialized size of " + message.getClass().getName() + " changed while serializing");
        }
    }

    /**
     * Writes a frame through an encoder that the caller reuses for a whole batch (no allocation per message).
     * @param out          an encoder over the batch buffer, with at least {@code expectedSize} bytes of space left
     * @param message      the protobuf message
     * @param expectedSize {@link #getSerializedSize(MessageLite)} of the message, computed when it was queued
     * @throws IOException           if the message no longer fits (its size grew after it was queued)
     * @throws IllegalStateException if it wrote a different number of bytes; the encoder must then be discarded
     */
    static void writeFrame(CodedOutputStream out, MessageLite message, int expectedSize) throws IOException {
        int start = out.getTotalBytesWritten();
        byte[] encodedClassName = ENCODED_CLASS_NAMES.get(message.getClass());
        // Big-endian header ints: fixed32 is little-endian, so reverse the bytes
        out.writeFixed32NoTag(Integer.reverseBytes(SIGNATURE));
        out.writeFixed32NoTag(Integer.reverseBytes(encodedClassName.length));
        out.writeFixed32NoTag(Integer.reverseBytes(message.getSerializedSize()));
        out.writeRawBytes(encodedClassName);
        message.writeTo(out);
        if (out.getTotalBytesWritten() - start != expectedSize) {
            throw new IllegalStateException("Serialized size of " + message.getClass().getName() + " changed after it was queued");
        }
    }

    /**
     * @param message the protobuf message
     * @return the size of the whole frame in bytes, header included
     */
    public static int getSerializedSize(MessageLite message) {
        return HEADER_LENGTH + ENCODED_CLASS_NAMES.get(message.getClass()).length + message.getSerializedSize();
    }
}
