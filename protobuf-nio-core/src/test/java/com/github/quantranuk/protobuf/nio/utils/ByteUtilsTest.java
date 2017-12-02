package com.github.quantranuk.protobuf.nio.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ByteUtilsTest {

    @Test
    public void testParsingLong() {
        byte[] b = new byte[16];
        int startPosition = 3;

        ByteUtils.writeLong(b, startPosition, 31);
        assertEquals(31, ByteUtils.readLong(b, startPosition));

        ByteUtils.writeLong(b, startPosition, -31);
        assertEquals(-31, ByteUtils.readLong(b, startPosition));

        ByteUtils.writeLong(b, startPosition, 0);
        assertEquals(0, ByteUtils.readLong(b, startPosition));

        ByteUtils.writeLong(b, startPosition, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, ByteUtils.readLong(b, startPosition));

        ByteUtils.writeLong(b, startPosition, Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, ByteUtils.readLong(b, startPosition));
    }

    @Test
    public void testParsingInteger() {
        byte[] b = new byte[16];
        int startPosition = 3;

        ByteUtils.writeInteger(b, startPosition, 31);
        assertEquals(31, ByteUtils.readInteger(b, startPosition));

        ByteUtils.writeInteger(b, startPosition, -31);
        assertEquals(-31, ByteUtils.readInteger(b, startPosition));

        ByteUtils.writeInteger(b, startPosition, 0);
        assertEquals(0, ByteUtils.readInteger(b, startPosition));

        ByteUtils.writeInteger(b, startPosition, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ByteUtils.readInteger(b, startPosition));

        ByteUtils.writeInteger(b, startPosition, Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, ByteUtils.readInteger(b, startPosition));
    }

    @Test
    public void testParsingString() {
        byte[] b = new byte[512];
        int startPosition = 3;

        String msg = "test123¬!\"£$%^&*()_+{}:@~<>?,./;'#[]`";
        ByteUtils.writeString(b, startPosition, msg);
        assertEquals(msg, ByteUtils.readString(b, startPosition, msg.length()));

        msg = "";
        ByteUtils.writeString(b, startPosition, msg);
        assertEquals(msg, ByteUtils.readString(b, startPosition, msg.length()));
    }

}
