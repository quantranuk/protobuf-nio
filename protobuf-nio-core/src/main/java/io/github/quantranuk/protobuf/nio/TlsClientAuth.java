package io.github.quantranuk.protobuf.nio;

/** Whether a TLS server asks its clients for a certificate ({@code ProtoServer.Builder.tlsClientAuth}). */
public enum TlsClientAuth {
    /** Don't ask for a client certificate. */
    NONE,
    /** Ask for one, but accept clients without one; a certificate that's presented must be trusted. */
    WANT,
    /** Require a trusted client certificate: the TLS handshake fails without one. */
    NEED
}
