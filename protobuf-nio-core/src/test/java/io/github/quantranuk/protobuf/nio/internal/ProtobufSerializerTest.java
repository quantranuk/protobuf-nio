package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ProtobufSerializerTest {

    @Test
    public void testRoundTripSerialization() {
        long requestTimeMillis = System.currentTimeMillis();
        String requestMessage = "HB_REQUEST_" + requestTimeMillis;

        TestHeartBeat.HeartBeatRequest message = TestHeartBeat.HeartBeatRequest.newBuilder()
                .setRequestTimeMillis(requestTimeMillis)
                .setRequestMessage(requestMessage)
                .build();

        byte[] serializedBytes = ProtobufSerializer.serialize(message);

        byte[] header = new byte[ProtobufSerializer.HEADER_LENGTH];
        ByteBuffer serializedByteBuffer = ByteBuffer.wrap(serializedBytes);
        serializedByteBuffer.get(header);
        int protobufClassNameLength = ProtobufSerializer.extractProtobufClassNameLength(header);
        int protobufPayloadLength = ProtobufSerializer.extractProtobufPayloadLength(header);

        assertEquals(message.getClass().getName().length(), protobufClassNameLength);
        assertEquals(message.getSerializedSize(), protobufPayloadLength);

        byte[] protobufClassNameBytes = new byte[protobufClassNameLength];
        byte[] protobufPayloadBytes = new byte[protobufPayloadLength];
        serializedByteBuffer.get(protobufClassNameBytes);
        serializedByteBuffer.get(protobufPayloadBytes);

        Message deserializedMessage = ProtobufSerializer.deserialize(ByteBuffer.wrap(protobufClassNameBytes), ByteBuffer.wrap(protobufPayloadBytes));
        assertInstanceOf(TestHeartBeat.HeartBeatRequest.class, deserializedMessage);

        assertEquals(requestTimeMillis, ((TestHeartBeat.HeartBeatRequest) deserializedMessage).getRequestTimeMillis());
        assertEquals(requestMessage, ((TestHeartBeat.HeartBeatRequest) deserializedMessage).getRequestMessage());
    }

    @Test
    public void testSerializeIntoMatchesSerializeAtAnyBufferPosition() {
        TestHeartBeat.HeartBeatRequest message = TestHeartBeat.HeartBeatRequest.newBuilder()
                .setRequestTimeMillis(123456789L)
                .setRequestMessage("payload")
                .build();
        byte[] expected = ProtobufSerializer.serialize(message);
        assertEquals(expected.length, ProtobufSerializer.getSerializedSize(message));

        byte[] backing = new byte[expected.length + 20];
        ByteBuffer target = ByteBuffer.wrap(backing, 7, expected.length + 5);  // non-zero position, spare room at the end
        ProtobufSerializer.serializeInto(message, target);

        assertEquals(7 + expected.length, target.position());
        assertArrayEquals(expected, Arrays.copyOfRange(backing, 7, 7 + expected.length));
        assertEquals(0, backing[6]);
        assertEquals(0, backing[7 + expected.length]);
    }

    @Test
    public void testParseMethodCacheIsNotCorruptedByReusedBuffer() throws Exception {
        TestHeartBeat.HeartBeatResponse message = TestHeartBeat.HeartBeatResponse.getDefaultInstance();
        byte[] frame = ProtobufSerializer.serialize(message);
        int nameLength = ProtobufSerializer.extractProtobufClassNameLength(frame);

        // Mimic the reader: the class name is a view over a buffer that gets reused at varying offsets
        byte[] reusedBuffer = new byte[512];
        for (int i = 0; i < 1000; i++) {
            int offset = (i * 37) % (reusedBuffer.length - nameLength);
            Arrays.fill(reusedBuffer, (byte) 0);
            System.arraycopy(frame, ProtobufSerializer.HEADER_LENGTH, reusedBuffer, offset, nameLength);
            Message deserialized = ProtobufSerializer.deserialize(ByteBuffer.wrap(reusedBuffer, offset, nameLength).slice(), ByteBuffer.allocate(0));
            assertInstanceOf(TestHeartBeat.HeartBeatResponse.class, deserialized);
        }

        Field cacheField = ProtobufSerializer.class.getDeclaredField("CACHED_PARSERS");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        assertTrue(cache.size() <= 2, "Expected one cache entry per class but found " + cache.size());
    }

}
