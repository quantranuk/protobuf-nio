package com.quantran.protobuf.nio.serializer;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.utils.ByteUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtobufSerializer {

    public static final int SIGNATURE = 0x7A6B5C4D;
    public static final int SIGNATURE_LENGTH = Integer.BYTES;
    public static final int PROTO_CLASSNAME_LENGTH = Integer.BYTES;
    public static final int PROTO_PAYLOAD_LENGTH = Integer.BYTES;
    public static final int HEADER_LENGTH = SIGNATURE_LENGTH + PROTO_CLASSNAME_LENGTH + PROTO_PAYLOAD_LENGTH;

    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;
    private static final Map<ByteBuffer, Method> CACHED_PARSE_PROTOBUF_METHODS = new ConcurrentHashMap<>();

    public static byte[] serialize(Message message) {
        ByteBuffer encodedProtobufClassName = CHARSET.encode(message.getClass().getName());
        int protobufClassNameLength = encodedProtobufClassName.capacity();

        byte[] protbufPayload = message.toByteArray();
        int protbufPayloadLength = protbufPayload.length;

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + protobufClassNameLength + protbufPayloadLength);
        buffer.putInt(SIGNATURE);
        buffer.putInt(protobufClassNameLength);
        buffer.putInt(protbufPayloadLength);
        buffer.put(encodedProtobufClassName);
        buffer.put(protbufPayload);
        return buffer.array();
    }

    public static int getSerializedSize(Message message) {
        return HEADER_LENGTH + message.getClass().getName().length() + message.getSerializedSize();
    }

    public static boolean hasValidHeaderSignature(byte[] header) {
        return ByteUtils.readInteger(header, 0) == SIGNATURE;
    }

    public static int extractProtobufClassnameLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES);
    }

    public static int extractProtobufPayloadLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES + Integer.BYTES);
    }

    public static Message deserialize(ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer) {
        final Method protobufParseMethod = getParseMethod(protobufClassNameBuffer);
        try {
            return (Message) protobufParseMethod.invoke(null, (Object) protobufPayloadBuffer);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to parse protobuf payload of " + CHARSET.decode(protobufClassNameBuffer).toString(), e);
        }
    }

    private static Method getParseMethod(ByteBuffer protobufClassNameBuffer) {
        Method parseMethod = CACHED_PARSE_PROTOBUF_METHODS.get(protobufClassNameBuffer);
        if (parseMethod == null) {
            String protobufClassName = CHARSET.decode(protobufClassNameBuffer).toString();
            final Class<?> protobufClass;
            try {
                protobufClass = Class.forName(protobufClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Invalid protobuf class name: " + protobufClassName, e);
            }

            if (!Message.class.isAssignableFrom(protobufClass)) {
                throw new IllegalStateException(protobufClassName + " is not a protobuf class");
            }

            try {
                parseMethod = protobufClass.getMethod("parseFrom", ByteBuffer.class);
                protobufClassNameBuffer.flip();
                CACHED_PARSE_PROTOBUF_METHODS.put(protobufClassNameBuffer, parseMethod);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Unable to get parse method from : " + protobufClassName, e);
            }
        }
        return parseMethod;
    }

}
