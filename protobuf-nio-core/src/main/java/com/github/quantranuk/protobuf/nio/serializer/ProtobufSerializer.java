package com.github.quantranuk.protobuf.nio.serializer;

import com.github.quantranuk.protobuf.nio.utils.ByteUtils;
import com.google.protobuf.Message;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>A serializer to serialize Protobuf messages into bytes array and deserialize bytes array back into Protobuf message</p>
 * <p>The class name of the protobuf is also serialized as part of the message. This is so that the deserialization process will be able to use the class name
 * to re-construct the protobuf message using reflection.</p>
 */
public final class ProtobufSerializer {

    public static final int SIGNATURE = 0x7A6B5C4D;
    public static final int SIGNATURE_LENGTH = Integer.BYTES;
    public static final int PROTO_CLASS_NAME_LENGTH = Integer.BYTES;
    public static final int PROTO_PAYLOAD_LENGTH = Integer.BYTES;
    public static final int HEADER_LENGTH = SIGNATURE_LENGTH + PROTO_CLASS_NAME_LENGTH + PROTO_PAYLOAD_LENGTH;

    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;
    private static final Map<ByteBuffer, Method> CACHED_PARSE_PROTOBUF_METHODS = new ConcurrentHashMap<>();

    /**
     * <p>Serialize a protobuf message into bytes array. The bytes array will contain in this order:</p>
     * <ul>
     *     <li>Integer: A simple signature so that the deserialization can quickly detect corrupted data</li>
     *     <li>Integer: The length of the protobuf class name</li>
     *     <li>Integer: The length of the protobuf payload</li>
     *     <li>bytes[]: The decoded protobuf class name in bytes (ISO_8859_1)</li>
     *     <li>bytes[]: The protobuf payload in bytes</li>
     * </ul>
     * @param message the protobuf message
     * @return serialized byte arrays
     */
    public static byte[] serialize(Message message) {
        ByteBuffer encodedProtobufClassName = CHARSET.encode(message.getClass().getName());
        int protobufClassNameLength = encodedProtobufClassName.capacity();

        byte[] protobufPayload = message.toByteArray();
        int protobufPayloadLength = protobufPayload.length;

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + protobufClassNameLength + protobufPayloadLength);
        buffer.putInt(SIGNATURE);
        buffer.putInt(protobufClassNameLength);
        buffer.putInt(protobufPayloadLength);
        buffer.put(encodedProtobufClassName);
        buffer.put(protobufPayload);
        return buffer.array();
    }

    /**
     * Get the size (in number of bytes) of a fully serialized protobuf message, including all the header information.
     * @param message the protobuf message
     * @return the size of a fully serialized message in bytes (including the header size)
     */
    public static int getSerializedSize(Message message) {
        return HEADER_LENGTH + message.getClass().getName().length() + message.getSerializedSize();
    }

    /**
     * Check if the header started with a valid signature
     * @param header the message header
     * @return true if the signature of the header is valid
     */
    public static boolean hasValidHeaderSignature(byte[] header) {
        return ByteUtils.readInteger(header, 0) == SIGNATURE;
    }

    /**
     * Get the length of the protobuf class name
     * @param header the message header
     * @return the length of the protobuf class name
     */
    public static int extractProtobufClassNameLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES);
    }

    /**
     * Get the length of the protobuf payload
     * @param header the message header
     * @return the length of the protobuf payload
     */
    public static int extractProtobufPayloadLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES + Integer.BYTES);
    }

    /**
     * Deserialized a protobuf message using protobuf payload and the class name information
     * @param protobufClassNameBuffer the buffer that contains the class name of the protobuf
     * @param protobufPayloadBuffer the buffer that contains the protobuf payload
     * @return the protobuf message
     */
    public static Message deserialize(ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer) {
        final Method protobufParseMethod = getParseMethod(protobufClassNameBuffer);
        try {
            return (Message) protobufParseMethod.invoke(null, protobufPayloadBuffer);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to parse protobuf payload of " + CHARSET.decode(protobufClassNameBuffer.duplicate()), e);
        }
    }

    private static Method getParseMethod(ByteBuffer protobufClassNameBuffer) {
        // The lookup buffer may be a view over a reusable read buffer, so it must never be stored as a key.
        Method parseMethod = CACHED_PARSE_PROTOBUF_METHODS.get(protobufClassNameBuffer);
        if (parseMethod == null) {
            String protobufClassName = CHARSET.decode(protobufClassNameBuffer.duplicate()).toString();
            final Class<?> protobufClass;
            try {
                // Do not initialize the class until it is known to be a protobuf message: the name comes from the network.
                protobufClass = Class.forName(protobufClassName, false, ProtobufSerializer.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Invalid protobuf class name: " + protobufClassName, e);
            }

            if (!Message.class.isAssignableFrom(protobufClass)) {
                throw new IllegalStateException(protobufClassName + " is not a protobuf class");
            }

            try {
                parseMethod = protobufClass.getMethod("parseFrom", ByteBuffer.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Unable to get parse method from : " + protobufClassName, e);
            }

            byte[] keyBytes = new byte[protobufClassNameBuffer.remaining()];
            protobufClassNameBuffer.duplicate().get(keyBytes);
            CACHED_PARSE_PROTOBUF_METHODS.putIfAbsent(ByteBuffer.wrap(keyBytes).asReadOnlyBuffer(), parseMethod);
        }
        return parseMethod;
    }

}
