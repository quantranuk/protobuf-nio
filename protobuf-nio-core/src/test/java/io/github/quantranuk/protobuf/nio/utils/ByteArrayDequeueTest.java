package io.github.quantranuk.protobuf.nio.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ByteArrayDequeueTest {

    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    ByteArrayDequeue underTest;

    @BeforeEach
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
    public void testPrepareAndCommitWriteCompactsAndGrows() {
        ByteArrayDequeue queue = new ByteArrayDequeue(8);
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            String chunk = "chunk-" + i + ";";
            ByteBuffer target = queue.prepareWrite(chunk.length() + 3);  // offer more space than gets used
            assertEquals(chunk.length() + 3, target.remaining());
            target.put(CHARSET.encode(chunk));
            queue.commitWrite(chunk.length());
            expected.append(chunk);
            if (i % 3 == 0) {  // leave a growing backlog so both compaction and growth happen
                actual.append(CHARSET.decode(queue.popExactly(queue.getRemaining() / 2)));
            }
        }
        actual.append(CHARSET.decode(queue.popExactly(queue.getRemaining())));
        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    public void testCommitWriteRejectsMoreThanReserved() {
        ByteArrayDequeue queue = new ByteArrayDequeue(8);
        queue.prepareWrite(8);
        try {
            queue.commitWrite(9);
            fail("Expected an exception");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, queue.getRemaining());
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

    @Test
    public void popEmptyArray() {
        try {
            underTest.push(CHARSET.encode("ABCD").array());
            underTest.popExactly(10);
            fail();
        } catch (IllegalStateException e) {
            assertEquals("Not enough remaining bytes. Expect 10 but remaining is only 4", e.getMessage());
        }
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
