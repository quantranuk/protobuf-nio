package io.github.quantranuk.protobuf.nio.internal;


import java.net.ProtocolException;
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
    /** Longest class name quoted in an exception message; longer ones are cut. */
    static final int MAX_QUOTED_NAME_LENGTH = 256;

    static final byte[] PREAMBLE = preamble(VERSION, 0);
    static final byte[] HEARTBEAT_FRAME = ByteBuffer.allocate(ProtobufSerializer.HEADER_LENGTH)
            .putInt(ProtobufSerializer.SIGNATURE).putInt(0).putInt(0).array();

    private Protocol() {
    }

    /**
     * A class name received from the peer, made safe to put in an exception message (which applications may log):
     * characters outside printable ASCII are escaped as {@code \xNN}, so a peer can't inject line breaks or terminal
     * control sequences, and names longer than {@link #MAX_QUOTED_NAME_LENGTH} are cut. Real Java class names come
     * through unchanged.
     *
     * @param bytes  buffer holding the ISO-8859-1 name
     * @param offset start of the name
     * @param length length of the name
     * @return the quotable name
     */
    static String quotableName(byte[] bytes, int offset, int length) {
        int shown = Math.min(length, MAX_QUOTED_NAME_LENGTH);
        StringBuilder out = new StringBuilder(shown + 16);
        for (int i = offset; i < offset + shown; i++) {
            int c = bytes[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F && c != '\\') {
                out.append((char) c);
            } else {
                out.append(String.format("\\x%02X", c));
            }
        }
        if (shown < length) {
            out.append("... (").append(length).append(" bytes)");
        }
        return out.toString();
    }

    /** @return a preamble with the given fields (tests use other values) */
    public static byte[] preamble(int version, int flags) {
        return ByteBuffer.allocate(PREAMBLE_LENGTH).putInt(PREAMBLE_MAGIC).putShort((short) version).putShort((short) flags).array();
    }

    /**
     * @param bytes  buffer holding the peer's preamble
     * @param offset start of the preamble
     * @return the protocol version to use with this peer
     * @throws ProtocolException for a bad magic or no common version
     */
    static int negotiate(byte[] bytes, int offset) throws ProtocolException {
        ByteBuffer preamble = ByteBuffer.wrap(bytes, offset, PREAMBLE_LENGTH);
        int magic = preamble.getInt();
        if (magic != PREAMBLE_MAGIC) {
            throw new ProtocolException(
                    String.format("Bad protocol preamble 0x%08X: the peer isn't a protobuf-nio 2.x endpoint", magic));
        }
        int peerVersion = Short.toUnsignedInt(preamble.getShort());
        int version = Math.min(VERSION, peerVersion);
        if (version < MIN_VERSION) {
            throw new ProtocolException(
                    "No common protocol version: the peer supports up to " + peerVersion + ", this side " + MIN_VERSION + " to " + VERSION);
        }
        return version;
    }
}
