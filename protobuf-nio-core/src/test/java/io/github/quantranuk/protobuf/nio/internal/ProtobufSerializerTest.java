package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProtobufSerializerTest {

    @Test
    public void testFrameLayoutRoundTrip() throws Exception {
        TestHeartBeat.HeartBeatRequest message = TestHeartBeat.HeartBeatRequest.newBuilder()
                .setRequestTimeMillis(42)
                .setRequestMessage("HB_REQUEST")
                .build();

        byte[] frame = ProtobufSerializer.serialize(message);
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        assertEquals(ProtobufSerializer.SIGNATURE, buffer.getInt());
        int nameLength = ProtobufSerializer.extractProtobufClassNameLength(frame);
        int payloadLength = ProtobufSerializer.extractProtobufPayloadLength(frame);
        assertEquals(message.getClass().getName().length(), nameLength);
        assertEquals(message.getSerializedSize(), payloadLength);
        assertEquals(ProtobufSerializer.HEADER_LENGTH + nameLength + payloadLength, frame.length);

        var parser = MessageTypes.forClassLoader(getClass().getClassLoader()).parser(frame, ProtobufSerializer.HEADER_LENGTH, nameLength);
        Object parsed = parser.parseFrom(frame, ProtobufSerializer.HEADER_LENGTH + nameLength, payloadLength);
        assertEquals(message, parsed);
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
}
