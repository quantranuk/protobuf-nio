package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageTypesTest {

    private final MessageTypes types = MessageTypes.forClassLoader(getClass().getClassLoader());

    @Test
    public void sharedPerClassLoader() {
        assertSame(types, MessageTypes.forClassLoader(getClass().getClassLoader()));
    }

    @Test
    public void cacheIsNotCorruptedByReusedBuffer() throws Exception {
        byte[] name = TestHeartBeat.HeartBeatResponse.class.getName().getBytes(StandardCharsets.ISO_8859_1);
        // Mimic the reader: the class name is a view over a buffer that gets reused at varying offsets
        byte[] reusedBuffer = new byte[512];
        for (int i = 0; i < 1000; i++) {
            int offset = (i * 37) % (reusedBuffer.length - name.length);
            Arrays.fill(reusedBuffer, (byte) 0);
            System.arraycopy(name, 0, reusedBuffer, offset, name.length);
            assertSame(TestHeartBeat.HeartBeatResponse.parser(), types.parser(reusedBuffer, offset, name.length));
        }
        assertTrue(types.cachedParserCount() <= 2, "Expected one cache entry per class but found " + types.cachedParserCount());
    }

    @Test
    public void unknownClassIsAProtocolError() {
        byte[] name = "com.example.DoesNotExist".getBytes(StandardCharsets.ISO_8859_1);
        ConnectionFailure failure = assertThrows(ConnectionFailure.class, () -> types.parser(name, 0, name.length));
        assertEquals(DisconnectReason.PROTOCOL_ERROR, failure.reason());
    }

    @Test
    public void nonProtobufClassIsRejectedWithoutInitializingIt() {
        byte[] name = NotAMessage.class.getName().getBytes(StandardCharsets.ISO_8859_1);
        ConnectionFailure failure = assertThrows(ConnectionFailure.class, () -> types.parser(name, 0, name.length));
        assertEquals(DisconnectReason.PROTOCOL_ERROR, failure.reason());
        assertEquals(0, NOT_A_MESSAGE_INITIALIZED.get());
    }

    // Kept outside NotAMessage: reading a field of NotAMessage would initialise it
    static final AtomicInteger NOT_A_MESSAGE_INITIALIZED = new AtomicInteger();

    public static final class NotAMessage {
        static {
            NOT_A_MESSAGE_INITIALIZED.incrementAndGet();
        }
    }
}
