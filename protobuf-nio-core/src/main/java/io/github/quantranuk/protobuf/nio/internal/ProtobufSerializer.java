package io.github.quantranuk.protobuf.nio.internal;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
    private static final Map<ByteBuffer, Parser<? extends Message>> CACHED_PARSERS = new ConcurrentHashMap<>();
    // The ISO-8859-1 class name sent with every message, encoded once per class
    private static final ClassValue<byte[]> ENCODED_CLASS_NAMES = new ClassValue<>() {
        @Override
        protected byte[] computeValue(Class<?> type) {
            return type.getName().getBytes(CHARSET);
        }
    };

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
        byte[] bytes = new byte[getSerializedSize(message)];
        serializeInto(message, ByteBuffer.wrap(bytes));
        return bytes;
    }

    /**
     * Serialize a protobuf message, in the same format as {@link #serialize(Message)}, directly into a heap buffer, without
     * intermediate copies. The buffer must have at least {@link #getSerializedSize(Message)} bytes remaining, and its
     * position is advanced by that many bytes.
     * @param message the protobuf message
     * @param target the buffer to write into
     */
    public static void serializeInto(Message message, ByteBuffer target) {
        byte[] encodedProtobufClassName = ENCODED_CLASS_NAMES.get(message.getClass());
        int protobufPayloadLength = message.getSerializedSize();
        target.putInt(SIGNATURE);
        target.putInt(encodedProtobufClassName.length);
        target.putInt(protobufPayloadLength);
        target.put(encodedProtobufClassName);
        int payloadStart = target.position();
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(target);
            message.writeTo(output);
            output.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize " + message.getClass().getName(), e);
        }
        if (target.position() - payloadStart != protobufPayloadLength) {
            throw new IllegalStateException("Serialized size of " + message.getClass().getName() + " changed while serializing");
        }
    }

    /**
     * Get the size (in number of bytes) of a fully serialized protobuf message, including all the header information.
     * @param message the protobuf message
     * @return the size of a fully serialized message in bytes (including the header size)
     */
    public static int getSerializedSize(Message message) {
        return HEADER_LENGTH + ENCODED_CLASS_NAMES.get(message.getClass()).length + message.getSerializedSize();
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
        return parse(getParser(protobufClassNameBuffer), protobufClassNameBuffer, protobufPayloadBuffer);
    }

    /**
     * Parse a protobuf payload with a parser obtained from {@link #getParser(ByteBuffer)}
     * @param parser the parser of the message class
     * @param protobufClassNameBuffer the buffer that contains the class name of the protobuf (used in error messages)
     * @param protobufPayloadBuffer the buffer that contains the protobuf payload
     * @return the protobuf message
     */
    public static Message parse(Parser<? extends Message> parser, ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer) {
        try {
            return parser.parseFrom(protobufPayloadBuffer);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to parse protobuf payload of " + CHARSET.decode(protobufClassNameBuffer.duplicate()), e);
        }
    }

    /**
     * Get the parser of the protobuf class named in the buffer. The buffer is not modified and is never retained, so it can
     * be a view over a reusable buffer.
     * @param protobufClassNameBuffer the buffer that contains the class name of the protobuf
     * @return the parser of that protobuf class
     */
    public static Parser<? extends Message> getParser(ByteBuffer protobufClassNameBuffer) {
        Parser<? extends Message> parser = CACHED_PARSERS.get(protobufClassNameBuffer);
        if (parser == null) {
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
                Message defaultInstance = (Message) protobufClass.getMethod("getDefaultInstance").invoke(null);
                parser = defaultInstance.getParserForType();
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Unable to get parser of : " + protobufClassName, e);
            }

            CACHED_PARSERS.putIfAbsent(copyOf(protobufClassNameBuffer), parser);
        }
        return parser;
    }

    /**
     * Copy the remaining bytes of a buffer into a new read-only buffer, without changing the source buffer
     * @param buffer the source buffer
     * @return an independent read-only copy
     */
    public static ByteBuffer copyOf(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

}
