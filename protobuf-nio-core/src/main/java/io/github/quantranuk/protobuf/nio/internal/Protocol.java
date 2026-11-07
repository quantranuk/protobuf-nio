package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;

import java.nio.ByteBuffer;

/**
 * Connection-level protocol: the preamble each side sends once, before any frame, and the heartbeat frame.
 * <p>
 * The preamble is 8 bytes: the int {@link #PREAMBLE_MAGIC}, the short highest protocol version the sender supports, and
 * a short of flags (reserved; sent as 0 and ignored on receipt). Each side may send frames right after its own preamble,
 * without waiting for the peer's. Both sides then use the lower of the two versions; a peer is rejected if that version
 * is one this side doesn't support.
 */
public final class Protocol {

    /** "PNIO" in ASCII. Differs from the frame signature, so a 1.x peer (which sends frames at once) is rejected. */
    public static final int PREAMBLE_MAGIC = 0x504E494F;
    public static final int PREAMBLE_LENGTH = 8;
    /** Highest protocol version this side supports. */
    public static final int VERSION = 1;
    /** Lowest protocol version this side supports. */
    public static final int MIN_VERSION = 1;
    /** Upper bound for the class name length of a frame. */
    public static final int MAX_CLASS_NAME_LENGTH = 4096;

    static final byte[] PREAMBLE = preamble(VERSION, 0);
    static final byte[] HEARTBEAT_FRAME = ByteBuffer.allocate(ProtobufSerializer.HEADER_LENGTH)
            .putInt(ProtobufSerializer.SIGNATURE).putInt(0).putInt(0).array();

    private Protocol() {
    }

    /** @return a preamble with the given fields (tests use other values) */
    public static byte[] preamble(int version, int flags) {
        return ByteBuffer.allocate(PREAMBLE_LENGTH).putInt(PREAMBLE_MAGIC).putShort((short) version).putShort((short) flags).array();
    }

    /**
     * @param bytes  buffer holding the peer's preamble
     * @param offset start of the preamble
     * @return the protocol version to use with this peer
     * @throws ConnectionFailure ({@link DisconnectReason#PROTOCOL_MISMATCH}) for a bad magic or no common version
     */
    static int negotiate(byte[] bytes, int offset) throws ConnectionFailure {
        ByteBuffer preamble = ByteBuffer.wrap(bytes, offset, PREAMBLE_LENGTH);
        int magic = preamble.getInt();
        if (magic != PREAMBLE_MAGIC) {
            throw new ConnectionFailure(DisconnectReason.PROTOCOL_MISMATCH,
                    String.format("Bad protocol preamble 0x%08X: the peer isn't a protobuf-nio 2.x endpoint", magic));
        }
        int peerVersion = Short.toUnsignedInt(preamble.getShort());
        int version = Math.min(VERSION, peerVersion);
        if (version < MIN_VERSION) {
            throw new ConnectionFailure(DisconnectReason.PROTOCOL_MISMATCH,
                    "No common protocol version: the peer supports up to " + peerVersion + ", this side " + MIN_VERSION + " to " + VERSION);
        }
        return version;
    }
}
