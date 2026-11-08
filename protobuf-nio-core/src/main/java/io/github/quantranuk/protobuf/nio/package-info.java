/**
 * Send and receive Protocol Buffers messages over TCP.
 *
 * <h2>Overview</h2>
 * A {@link io.github.quantranuk.protobuf.nio.ProtoServer} accepts connections, each a
 * {@link io.github.quantranuk.protobuf.nio.ProtoConnection}; a {@link io.github.quantranuk.protobuf.nio.ProtoClient}
 * connects to it. Either side sends any generated protobuf message with {@code send}, and the other side receives it
 * already parsed, in the callbacks set on its builder. Messages are identified on the wire by their Java class name, so
 * both sides need the same generated classes, and there's nothing to register.
 * <pre>{@code
 * ProtoServer server = ProtoServer.builder(3456)
 *         .onMessage(Ping.class, (connection, ping) -> connection.send(Pong.newBuilder().setText("pong").build()))
 *         .build();
 * server.start();
 *
 * ProtoClient client = ProtoClient.builder("localhost", 3456)
 *         .onMessage(Pong.class, (c, pong) -> System.out.println(pong.getText()))
 *         .build();
 * client.connect();
 * client.send(Ping.newBuilder().setText("ping").build());
 * }</pre>
 *
 * <h2>Threads</h2>
 * Each connection has two virtual threads. Its <b>reader</b> runs {@code onConnected}, {@code onMessage} (and raw
 * handlers) and {@code onDisconnected}, in that order and never concurrently. Its <b>writer</b> encodes and writes the
 * queued messages in batches, and runs {@code onSendFailure}, except for messages rejected or dropped by
 * {@code send} itself, which are reported on the sending thread. Callbacks for different connections run
 * concurrently, so callbacks shared by several connections must be thread-safe. A handler may block: that stalls
 * only its own connection.
 *
 * <h2>Sending</h2>
 * {@code send} is thread-safe and queues the message; messages sent from one thread arrive in order. Queues are
 * bounded, and the {@link io.github.quantranuk.protobuf.nio.OverflowPolicy} decides what happens when one is full.
 * Until a connection starts closing, every message passed to {@code send} is either written or reported to
 * {@code onSendFailure} with a {@link io.github.quantranuk.protobuf.nio.SendException}.
 *
 * <h2>Failures</h2>
 * <ul>
 *     <li>A connection that fails before it opens (TCP connect, TLS handshake or protocol preamble) is never reported
 *     to the callbacks: {@code ProtoClient.connect()} throws an {@link java.io.IOException} instead
 *     ({@link java.net.ProtocolException} if the peer isn't a protobuf-nio endpoint).</li>
 *     <li>Once open, every connection ends with exactly one {@code onDisconnected}, whose
 *     {@link io.github.quantranuk.protobuf.nio.DisconnectReason} says why it closed.</li>
 *     <li>{@code close()} is graceful: it writes what's queued, within {@code closeTimeout}. Errors and timeouts close
 *     the connection at once.</li>
 *     <li>Exceptions thrown by callbacks are logged (through SLF4J) and ignored.</li>
 *     <li>Builder settings are checked when they're called: invalid values throw
 *     {@link java.lang.IllegalArgumentException}, and {@code null} arguments
 *     {@link java.lang.NullPointerException}.</li>
 * </ul>
 */
package io.github.quantranuk.protobuf.nio;
