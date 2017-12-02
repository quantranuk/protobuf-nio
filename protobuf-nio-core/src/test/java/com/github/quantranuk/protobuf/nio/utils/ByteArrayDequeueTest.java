package com.github.quantranuk.protobuf.nio.utils;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ByteArrayDequeueTest {

    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    ByteArrayDequeue underTest;

    @Before
    public void setUp() {
        underTest = new ByteArrayDequeue();
    }

    @Test
    public void testCombineArrays() {
        for (int i = 0; i < 200; i++) {
            push("ABCDEFGH");
            push("IJK");
            assertEquals("ABCDE", pop(5));
            assertEquals("FGHIJK", pop(6));
        }
    }

    @Test
    public void testPushingBigArray() {
        ByteArrayDequeue stack = new ByteArrayDequeue(5);
        String longText = "ABCDEFGHIJKLMLOP";
        stack.push(CHARSET.encode(longText).array());
        assertEquals(longText, CHARSET.decode(stack.popExactly(longText.length())).toString());
    }

    @Test
    public void testPushingArrayLast() {
        push("ABC");
        push("DEF");
        pushLast("GHK");
        assertEquals("GHKABCDEF", pop(9));
    }

    @Test(expected = IllegalStateException.class)
    public void popEmptyArray() {
        underTest.push(CHARSET.encode("ABCD").array());
        underTest.popExactly(10);
        fail();
    }

    private void push(String str) {
        underTest.push(CHARSET.encode(str).array());
    }

    private void pushLast(String str) {
        underTest.pushLast(CHARSET.encode(str).array());
    }

    private String pop(int length) {
        return CHARSET.decode(underTest.popExactly(length)).toString();
    }

}
