# Changelog

All notable changes to protobuf-nio are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in the README's
[Versioning](README.md#versioning) section.

## [Unreleased]

The first release on Maven Central, as `io.github.quantranuk:protobuf-nio-core`. Requires Java 25 or later.

### Added

- `ProtoServer` and `ProtoClient`: send any generated protobuf message over TCP and receive it on the other side
  already parsed. Messages are identified by their Java class name, so there are no type IDs to register.
- Virtual threads with blocking sockets: each connection has a reader and a writer virtual thread. A connection's
  callbacks run in order on its reader thread, and a handler may block without stalling other connections.
- Callbacks set directly on the server and client builders (`onMessage(Type.class, handler)`, `onConnected`,
  `onDisconnected` and so on), with each message dispatched to its handler by type.
- Backpressure: bounded send queues (`maxPendingMessages`, `maxPendingBytes`) and four overflow policies: `BLOCK`,
  `REJECT`, `DROP_OLDEST` and `DISCONNECT`. Every message passed to `send` is either written or reported to
  `onSendFailure`.
- `ProtoServer.broadcast`, which encodes a message once for every connection, optionally filtered.
- TLS through a standard `SSLContext`, with hostname verification on by default and optional client certificates
  (`TlsClientAuth`).
- Connect, handshake, read, write and close timeouts, and heartbeats to keep idle connections alive.
- Graceful close: queued messages are written before the connection closes, within `closeTimeout`.
- `DisconnectReason`, reported exactly once per connection to `onDisconnected` and returned by `awaitClosed()`.
- Protection against untrusted peers: accept only the types you handle (`allowOnlyHandledTypes()`) or an explicit
  allowlist (`allowedMessageTypes`), checked before any class is loaded; a maximum message size; memory that follows the bytes a peer actually sends; and
  `maxConnections`, under which peers still in their handshake make room for new connections.
- Low-garbage paths: raw handlers receive a message type's payload unparsed, and the writer encodes queued messages
  in batches without allocating per message.
- Wire protocol version 1: an 8-byte preamble with the protocol version, then one frame per message (a 12-byte
  header, the class name and the protobuf payload), plus heartbeat frames.
- The Java module `io.github.quantranuk.protobuf.nio`, exporting only the API package.
- Javadoc for the whole public API.

[Unreleased]: https://github.com/quantranuk/protobuf-nio/commits/master
