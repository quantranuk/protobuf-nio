package com.github.quantranuk.protobuf.nio.utils;

import java.nio.ByteBuffer;

/**
 * A simple dequeue to store bytes. This class is not thread-safe. The pop action will not allocate new memory.
 */
public final class ByteArrayDequeue {

    private static final int INITIAL_CAPACITY = 8192;

    private byte[] primaryArray;
    private ByteBuffer primaryArrayView;  // reused to create popped slices with a single allocation
    private int position = 0;
    private int limit = 0;
    private int remaining = 0;

    public ByteArrayDequeue() {
        setPrimaryArray(new byte[INITIAL_CAPACITY]);
    }

    ByteArrayDequeue(int initalCapacity) {
        setPrimaryArray(new byte[initalCapacity]);
    }

    /**
     * Get the size of the remaining bytes in the queue
     * @return Remaining bytes size
     */
    public int getRemaining() {
        return remaining;
    }

    /**
     * Push a bytes array to the front of the queue
     * @param src bytes
     */
    public void push(byte[] src) {
        push(src, 0, src.length);
    }

    /**
     * Push a bytes array to the front of the queue
     * @param src source bytes
     * @param srcOffset source offset
     * @param srcLengthToPush length to push
     */
    public void push(byte[] src, int srcOffset, int srcLengthToPush) {
        assertLengthToPush(srcLengthToPush);
        int newLength = remaining + srcLengthToPush;

        if (primaryArray.length < newLength) {
            allocateMoreSpace(newLength);
        }
        if (primaryArray.length - limit < srcLengthToPush) {
            reallocate(0);
        }
        System.arraycopy(src, srcOffset, primaryArray, limit, srcLengthToPush);
        limit += srcLengthToPush;
        remaining += srcLengthToPush;
    }

    public void pushLast(byte[] src) {
        pushLast(src, 0, src.length);
    }

    public void pushLast(byte[] src, int srcOffset, int srcLengthToPush) {
        assertLengthToPush(srcLengthToPush);
        int newLength = remaining + srcLengthToPush;
        if (primaryArray.length < newLength) {
            allocateMoreSpace(newLength);
        }
        if (position < srcLengthToPush) {
            reallocate(srcLengthToPush);
        }
        position -= srcLengthToPush;
        System.arraycopy(src, srcOffset, primaryArray, position, srcLengthToPush);
        limit += srcLengthToPush;
        remaining += srcLengthToPush;
    }

    /**
     * Reserve {@code length} free bytes at the back of the queue, compacting or growing the storage as needed, and return
     * a buffer over them for a caller to fill (e.g. a socket read). Call {@link #commitWrite(int)} with the number of bytes
     * actually written. The queue must not be modified in between.
     * @param length number of bytes to reserve
     * @return a buffer whose position is at the back of the queue and whose remaining size is {@code length}
     */
    public ByteBuffer prepareWrite(int length) {
        assertLengthToPush(length);
        int newLength = remaining + length;
        if (primaryArray.length < newLength) {
            allocateMoreSpace(newLength);
        }
        if (primaryArray.length - limit < length) {
            reallocate(0);
        }
        return ByteBuffer.wrap(primaryArray, limit, length);
    }

    /**
     * Append the bytes written into the buffer returned by the last {@link #prepareWrite(int)} call
     * @param length number of bytes written
     */
    public void commitWrite(int length) {
        if (length < 0 || length > primaryArray.length - limit) {
            throw new IllegalArgumentException("Invalid committed length " + length + " (free space is " + (primaryArray.length - limit) + ")");
        }
        limit += length;
        remaining += length;
    }

    private void reallocate(int destPos) {
        System.arraycopy(primaryArray, position, primaryArray, destPos, remaining);
        position = destPos;
        limit = destPos + remaining;
    }

    public ByteBuffer popMaximum(int maximumLengthToPop) {
        if (remaining == 0) {
            return null;  // Not enough data
        }
        int lengthToPop = Integer.min(remaining, maximumLengthToPop);
        ByteBuffer result = primaryArrayView.slice(position, lengthToPop);
        position += lengthToPop;
        remaining -= lengthToPop;
        return result;
    }

    public ByteBuffer popExactly(int lengthToPop) {
        if (remaining < lengthToPop) {
            throw new IllegalStateException("Not enough remaining bytes. Expect " + lengthToPop + " but remaining is only " + remaining);
        }
        ByteBuffer result = primaryArrayView.slice(position, lengthToPop);
        position += lengthToPop;
        remaining -= lengthToPop;
        return result;
    }

    public void popExactly(byte[] into) {
        int lengthToPop = into.length;
        if (remaining < lengthToPop) {
            throw new IllegalStateException("Not enough remaining bytes. Expect " + lengthToPop + " but remaining is only " + remaining);
        }
        System.arraycopy(primaryArray, position, into, 0, lengthToPop);
        position += lengthToPop;
        remaining -= lengthToPop;
    }

    private void assertLengthToPush(int lengthToPush) {
        if (Integer.MAX_VALUE - lengthToPush < remaining) {
            throw new IllegalStateException("Max buffer capacity breached. Length to push: " + lengthToPush + " left-over length: " + remaining);
        }
    }

    private void allocateMoreSpace(int newLength) {
        byte[] newAllocation = new byte[newLength + Integer.min(newLength, Integer.MAX_VALUE - newLength)];
        System.arraycopy(primaryArray, position, newAllocation, 0, remaining);
        setPrimaryArray(newAllocation);
        position = 0;
        limit = remaining;
    }

    private void setPrimaryArray(byte[] array) {
        primaryArray = array;
        primaryArrayView = ByteBuffer.wrap(array);
    }

    public void clear() {
        position = 0;
        limit = 0;
        remaining = 0;
    }
}
