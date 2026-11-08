[![Build](https://github.com/quantranuk/protobuf-nio/actions/workflows/build.yml/badge.svg)](https://github.com/quantranuk/protobuf-nio/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-blue.svg)](https://openjdk.org/projects/jdk/25/)

# protobuf-nio

Send [Protocol Buffers](https://protobuf.dev/) messages between Java applications over TCP. Define your messages in a `.proto` file, send any of them from either side, and receive them already parsed. Each connection is a plain socket served by two virtual threads: no Netty, no framework, and only `protobuf-java` and `slf4j-api` as dependencies.

```proto
syntax = "proto3";
option java_package = "com.example.chat";
option java_multiple_files = true;

message Ping { string text = 1; }
message Pong { string text = 1; }
```

```java
import com.example.chat.Ping;
import com.example.chat.Pong;
import io.github.quantranuk.protobuf.nio.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Example {
    public static void main(String[] args) throws Exception {
        // A server that answers every Ping with a Pong, on the connection it came from
        ProtoServer server = ProtoServer.builder(3456)
                .listener(ProtoServerListener.builder()
                        .onMessage(Ping.class, (connection, ping) ->
                                connection.send(Pong.newBuilder().setText("pong: " + ping.getText()).build()))
                        .build())
                .build();
        server.start();

        // A client that sends a Ping and waits for the Pong
        CompletableFuture<Pong> reply = new CompletableFuture<>();
        try (ProtoClient client = ProtoClient.builder("localhost", 3456)
                .listener(ProtoClientListener.builder()
                        .onMessage(Pong.class, (c, pong) -> reply.complete(pong))
                        .build())
                .build()) {
            client.connect();
            client.send(Ping.newBuilder().setText("hello").build());
            System.out.println(reply.get(5, TimeUnit.SECONDS).getText());   // pong: hello
        }
        server.close();
    }
}
```

- **Protobuf-native:** send any generated message and receive it on the other side already parsed. There are no type IDs or registrations to keep in sync: messages are identified by their class.
- **Simple threading:** each connection's callbacks run in order on its own virtual thread. A handler may block; that stalls only its own connection.
- **Backpressure:** bounded send queues with a choice of overflow policy (block, reject, drop oldest, disconnect), and every message you send is either written or reported back to you.
- **Production features:** TLS, timeouts, heartbeats, graceful close, connection limits, and an allowlist of accepted message types for untrusted peers.
- **Fast and lean:** about 7 million pipelined request/response round trips per second on one connection, and about 7 KB of heap per idle connection (see [Performance](#performance)). Sending allocates nothing per message beyond a queue node.

## When to use it

protobuf-nio is a good fit for **long-lived connections between Java applications that exchange protobuf messages in both directions**: streaming updates, internal publish/subscribe, game or trading servers, telemetry, and services that push events to each other. You get framing, threading, backpressure, TLS and timeouts without adopting a framework.

Use something else when:

- **The other side isn't Java,** or doesn't have the same generated classes: messages are identified by their Java class name. gRPC works across languages.
- **You want RPC semantics:** service definitions, deadlines, status codes, interceptors, or HTTP/2 infrastructure such as proxies and load balancers in between. That's what gRPC is for.
- **You need your own wire format,** or protocols other than protobuf over TCP: Netty gives you full control, at the cost of writing the codec and protocol yourself.

Compared with plain sockets, protobuf-nio is the part you'd otherwise write yourself: message framing, a reader and writer per connection, bounded queues, a protocol handshake, heartbeats, write timeouts, and closing without losing data.

> **Limitations:** both peers must be Java and have the same generated protobuf classes on their classpath (see [Wire format](#wire-format)).

## Requirements

- Java 25 or later. Virtual threads that block inside `synchronized` no longer pin their carrier thread from JDK 24 ([JEP 491](https://openjdk.org/jeps/491)), so your handlers can use `synchronized` freely.
- `protobuf-java` 4.x and `slf4j-api` 2.x come with the library as regular dependencies. Add an SLF4J backend (for example Logback or `log4j-slf4j2-impl`) to see its logs.

## Installation

Version 2.0 isn't published yet. Build and install it into your local Maven repository:

```bash
git clone https://github.com/quantranuk/protobuf-nio.git
cd protobuf-nio
./mvnw clean install
```

Then add the dependency:

```xml
<dependency>
    <groupId>io.github.quantranuk</groupId>
    <artifactId>protobuf-nio-core</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Your generated protobuf classes must be compatible with the `protobuf-java` version the library brings in (4.x). If your project declares its own `protobuf-java` version, Maven uses yours.

## Usage

### Server

```java
ProtoServerListener listener = ProtoServerListener.builder()
        .onConnected(connection -> log.info("{} connected", connection))
        .onMessage(HeartBeatRequest.class, (connection, request) ->
                connection.send(HeartBeatResponse.newBuilder()      // reply on the same connection
                        .setRequestTimeMillis(request.getRequestTimeMillis())
                        .build()))
        .onDisconnected((connection, reason, cause) -> log.info("{} disconnected: {}", connection, reason))
        .build();

ProtoServer server = ProtoServer.builder(3456)
        .listener(listener)
        .build();
server.start();                  // binds, then accepts connections on a virtual thread
// ...
server.broadcast(update);        // encode once, queue on every open connection
server.close();                  // close every connection gracefully, then stop
```

### Client

```java
ProtoClientListener listener = ProtoClientListener.builder()
        .onMessage(HeartBeatResponse.class, (client, response) ->
                log.info("Round trip: {} ms", System.currentTimeMillis() - response.getRequestTimeMillis()))
        .onSendFailure((client, message, cause) -> log.warn("Not sent: {}", cause.reason()))
        .build();

try (ProtoClient client = ProtoClient.builder("localhost", 3456).listener(listener).build()) {
    client.connect();            // blocks until connected; throws IOException on failure
    client.send(HeartBeatRequest.newBuilder()
            .setRequestTimeMillis(System.currentTimeMillis())
            .build());           // queues the message; a writer thread sends it
    // ...
}                                // close() writes what's still queued, then closes
```

`connectAsync()` does the same on a virtual thread and returns a `CompletableFuture`. Messages sent while connecting are queued and sent once connected.

### Reconnecting

A client is single-use: once closed, for whatever reason, it can't connect again. Build a new one from the same builder, which is reusable:

```java
ProtoClient.Builder builder = ProtoClient.builder("localhost", 3456).listener(listener);
long backoffMillis = 500;
while (running) {
    ProtoClient client = builder.build();
    try {
        client.connect();
        backoffMillis = 500;
        client.awaitClosed();    // returns after onDisconnected
    } catch (IOException e) {    // no listener method is called when connect() fails
        Thread.sleep(backoffMillis);
        backoffMillis = Math.min(backoffMillis * 2, 30_000);
    }
}
```

A `java.net.ProtocolException` from `connect()` means the peer isn't a protobuf-nio endpoint, or has no protocol version in common with this side, so retrying won't help.

### Samples

Working examples are in [`protobuf-nio-sample`](protobuf-nio-sample/src/main/java/io/github/quantranuk/protobuf/nio/sample). After `./mvnw install`, start the server:

```bash
./mvnw -q -pl protobuf-nio-sample exec:java -Dexec.mainClass=io.github.quantranuk.protobuf.nio.sample.SampleServer
```

Then, in another terminal, run `SampleClient` (one request), `SampleClientBenchmarkTest` (throughput) or `MultiClientsTest` (15,000 clients) the same way.

## Listeners and threads

`ProtoServerListener` and `ProtoClientListener` have the same four methods, all optional no-ops. You can implement the interface, or use its `builder()`, which takes lambdas and dispatches messages by type (exact class match, no allocation per message):

| Method | Thread | Called when |
|---|---|---|
| `onConnected(connection)` | reader | The connection is open: the TCP connect, TLS handshake and protocol preamble have all succeeded. |
| `onMessage(connection, message)` | reader | A message has been received and parsed. With the builder, `onMessage(Type.class, handler)` per type, and `onOtherMessage(handler)` for the rest (by default, logged at WARN once per type). |
| `onSendFailure(connection, message, cause)` | writer, or the sending thread | A message passed to `send` wasn't written. `cause.reason()` is `REJECTED`, `DROPPED`, `CLOSED` or `WRITE_FAILED` (see [Sending and backpressure](#sending-and-backpressure)). |
| `onDisconnected(connection, reason, cause)` | reader | The connection closed. Always the last callback for the connection, exactly once. `reason` is a `DisconnectReason`; `cause` is the exception, or `null` for a normal close. |

On the server, the first argument is a `ProtoConnection`; on the client, it's the `ProtoClient` itself.

The threading contract:

- **Each connection has two virtual threads**: a reader, which runs `onConnected`, `onMessage` and `onDisconnected` in that order and never concurrently, and a writer, which runs `onSendFailure`. `REJECTED` and `DROPPED` failures are reported on the thread that called `send` (or `broadcast`), before it returns.
- **Callbacks for different connections run concurrently.** A server listener, or a listener shared by several clients, must be thread-safe.
- **Handlers may block.** Blocking in `onMessage` stalls only that connection: its next message isn't read until the handler returns (and TCP flow control eventually slows the peer down). Other connections carry on.
- **A connection that fails before it opens is never reported.** If the TCP connect, TLS handshake or preamble fails, no listener method is called at all; the client's `connect()` throws instead.
- **Exceptions thrown by a callback are logged and ignored.** The connection carries on.
- `attach(object)` and `attachment()` keep your own per-connection state, such as a session after login.

## Sending and backpressure

`send(message)` is thread-safe, and messages sent from one thread arrive in order. It encodes nothing on the calling thread: it queues the message, and the connection's writer thread encodes and writes whatever has queued up, in batches, with one socket write per batch.

Each connection's queue is bounded by `maxPendingMessages` (default 1,000,000) and `maxPendingBytes` (default 64 MiB of encoded messages). When it's full, the `OverflowPolicy` decides what `send` does:

| Policy | When the queue is full |
|---|---|
| `BLOCK` (default) | Waits up to `sendTimeout` (default 10 s) for room, then gives up and returns `false`. |
| `REJECT` | Returns `false` at once. |
| `DROP_OLDEST` | Drops queued messages, oldest first, until the new one fits, reports each as `DROPPED`, and returns `true`. For data where only the latest value matters, such as prices. |
| `DISCONNECT` | Returns `false` and closes the connection at once with `SEND_QUEUE_OVERFLOW`. For servers that would rather drop a slow consumer than buffer for it. |

Whenever `send` returns `false` because the queue is full, `onSendFailure(REJECTED)` has already run on the calling thread. Until a connection starts closing, every message passed to `send` is either written or reported to `onSendFailure`. Once closing has started, `send` returns `false` without calling the listener, and anything still queued when the connection closes is reported as `CLOSED`, before `onDisconnected`.

`pendingMessages()` and `pendingBytes()` show how far behind a connection is.

`server.broadcast(message)` encodes the message once and queues the bytes on every open connection; `broadcast(message, filter)` does the same for the connections that match a predicate, for example `connection -> connection.<Session>attachment().isSubscribed(topic)`. Broadcast never blocks: under `BLOCK`, a full queue rejects the message as under `REJECT`.

## Closing

`close()` on a client, a server connection or a server is graceful: it stops accepting sends, lets the writer flush the queue, sends a TCP FIN and waits for the peer's, then closes the socket, all within `closeTimeout` (default 5 s; `Duration.ZERO` closes at once). The peer receives everything that was queued. `close()` blocks until done, except when called from the connection's own threads: from `onMessage` it waits only for the queue to be written, and from `onSendFailure` it returns at once. `ProtoServer.close()` closes all connections in parallel and waits for every `onDisconnected`. That includes connections still in their handshake: a client counts itself connected as soon as it has the server's preamble and may already be sending, so the server gives the handshake until `closeTimeout` to finish, then closes the connection gracefully like the others.

`awaitClosed()` blocks until the connection (or server) has closed and returns the `DisconnectReason`:

| Reason | Meaning |
|---|---|
| `CLOSED_LOCALLY` | `close()` was called on this side. |
| `CLOSED_BY_PEER` | The peer closed the connection. |
| `READ_TIMEOUT` | Nothing was received for `readTimeout`. |
| `WRITE_TIMEOUT` | A socket write blocked for longer than `writeTimeout`, usually because the peer stopped reading. |
| `SEND_QUEUE_OVERFLOW` | The queue was full under `OverflowPolicy.DISCONNECT`. |
| `PROTOCOL_ERROR` | The peer sent a bad frame, a disallowed or unknown class name, or a payload that didn't parse. |
| `PROTOCOL_MISMATCH` | The peer's protocol preamble was missing or wrong (the peer isn't protobuf-nio), or the two sides have no protocol version in common. |
| `TLS_ERROR` | A TLS failure after connecting. |
| `IO_ERROR` | Any other socket error. |

Failures close the socket at once, without flushing.

## Configuration

Both builders (`ProtoClient.builder(host, port)` and `ProtoServer.builder(port)`) take these settings. On the server they apply to each accepted connection. Invalid values throw `IllegalArgumentException` when the setting is called.

| Setting | Default | Description |
|---|---|---|
| `listener(listener)` | no-op | See [Listeners and threads](#listeners-and-threads). |
| `handshakeTimeout(duration)` | 10 s | Time allowed for the TLS handshake and the protocol preamble. |
| `readTimeout(duration)` | none | Close if nothing is received for this long. Any frame resets it, heartbeats included. |
| `heartbeatInterval(duration)` | none | Send a heartbeat frame when nothing has been sent for this long. Set it on the peer, below this side's `readTimeout`. |
| `writeTimeout(duration)` | 10 s | Close if a socket write blocks for longer than this, typically because the peer stopped reading. `Duration.ZERO` = none. |
| `closeTimeout(duration)` | 5 s | How long `close()` may spend writing queued messages. `Duration.ZERO` = close at once. |
| `readBufferSize(bytes)` | 64 KB | Largest socket read. Reads start small and grow only while they keep filling the buffer; idle connections drop back to 64 bytes after a second. |
| `writeBufferSize(bytes)` | 64 KB | Largest batch of queued messages written in one go. A single larger message is still sent. |
| `maxMessageSize(bytes)` | 64 MiB | Largest protobuf payload accepted from the peer, which is disconnected (`PROTOCOL_ERROR`) if it sends a larger one. |
| `maxPendingMessages(count)` | 1,000,000 | Most messages queued and not yet written. |
| `maxPendingBytes(bytes)` | 64 MiB | Most encoded bytes queued and not yet written. A single larger message is queued only when nothing else is pending. |
| `overflowPolicy(policy)` | `BLOCK` | What `send` does when the queue is full (see [Sending and backpressure](#sending-and-backpressure)). |
| `sendTimeout(duration)` | 10 s | How long `BLOCK` waits for room. `Duration.ZERO` = not at all. |
| `socketOption(option, value)` | `TCP_NODELAY` on | Any `java.net.SocketOption`, such as `SO_SNDBUF` or `SO_KEEPALIVE`. Unsupported options are ignored. |
| `allowedMessageTypes(types...)` | any | Accept only these message types (see [Untrusted peers](#untrusted-peers)). |
| `rawHandler(type, handler)` | none | Receive this type unparsed (see [Raw handlers](#raw-handlers)). |
| `classLoader(loader)` | context class loader | Loads received message classes. The default is the thread's context class loader when `build()` runs. |
| `threadFactory(factory)` | virtual threads | Creates the reader and writer threads (and the server's acceptor thread). |
| `tls(sslContext)` | none | Use TLS (see [TLS](#tls)). |

Client only:

| Setting | Default | Description |
|---|---|---|
| `connectTimeout(duration)` | 10 s | TCP connect. `Duration.ZERO` = the operating system's timeout. |
| `tlsHostnameVerification(boolean)` | true | With TLS, check that the server's certificate matches the host name. |

Server only:

| Setting | Default | Description |
|---|---|---|
| `backlog(count)` | 1024 | Pending connections the operating system may queue before `accept`. |
| `maxConnections(count)` | unlimited | At the limit, a new connection replaces the oldest one still in its handshake (peers that connect and stay silent can't hold every slot); if there's none, the new connection is closed at once. |
| `tlsClientAuth(mode)` | `NONE` | With TLS, whether clients must present a certificate: `NONE`, `WANT` or `NEED`. |

The server also turns on `SO_REUSEADDR` by default. `ProtoServer.builder(0)` binds to any free port; `server.localAddress()` tells you which.

## Raw handlers

To process a high-volume message type without parsing it into a new object each time, register a raw handler for it. Messages of that type then skip parsing and `onMessage`, and the handler gets the payload bytes:

```java
PriceUpdate.Builder update = PriceUpdate.newBuilder();   // reused: this client's reader thread is the only caller

ProtoClient client = ProtoClient.builder("prices.example.com", 3456)
        .rawHandler(PriceUpdate.class, (c, payload) -> {
            try {
                update.clear().mergeFrom(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining());
            } catch (InvalidProtocolBufferException e) {
                c.close();       // or log and skip the message
                return;
            }
            // ... use update
        })
        .build();
```

The `ByteBuffer` is a reused view over the connection's read buffer: it's valid only during the call, and you mustn't modify it. Copy what you need to keep. A raw handler runs on the reader thread, like `onMessage`, so a reused builder like the one above is safe for one connection. On a server, raw handlers for different connections run concurrently: give each connection its own builder (for example in its `attachment()`). Its type is always accepted, even when `allowedMessageTypes` doesn't list it.

## Untrusted peers

By default, a peer can make the receiver load any protobuf message class on its classpath, by name. When the peer isn't trusted, list the types you expect:

```java
ProtoServer.builder(3456)
        .allowedMessageTypes(LoginRequest.class, OrderRequest.class)
```

A message of any other type then disconnects the peer with `PROTOCOL_ERROR`, and its class name is checked against the list before any class is loaded, and before its payload is read. Even without a list, the receiver only uses classes that implement `com.google.protobuf.MessageLite`, and checks that before initializing the class.

### Hardening checklist

The defaults suit services on a trusted network. For a server that untrusted peers can reach:

- **`allowedMessageTypes(...)`**: accept only the types you expect.
- **`maxMessageSize(bytes)`**: each received message is held in memory whole, so set it to the largest message you really expect, rather than the 64 MiB default. Memory follows what a peer actually sends (a peer that announces a large message and stalls costs only what it sent), but a peer that does send a whole large message makes the receiver hold it.
- **`readTimeout(duration)`** on the server, with **`heartbeatInterval(duration)`** below it on the clients: peers that go quiet are disconnected instead of holding a connection forever.
- **`maxConnections(count)`**: caps file descriptors and memory. Peers still in their handshake are replaced first when the limit is reached, and a short `handshakeTimeout` limits how long they can hold a slot.
- **`maxPendingBytes(bytes)`** and **`maxPendingMessages(count)`**: bound what's queued for a peer that reads slowly (the defaults allow 64 MiB per connection), and choose an `overflowPolicy` such as `DISCONNECT` for consumers that fall behind.
- **TLS with `tlsClientAuth(TlsClientAuth.NEED)`** when only known clients may connect: a peer without a trusted certificate is rejected before it can send anything.

```java
ProtoServer server = ProtoServer.builder(8443)
        .tls(serverContext)
        .tlsClientAuth(TlsClientAuth.NEED)
        .allowedMessageTypes(LoginRequest.class, OrderRequest.class)
        .maxMessageSize(1024 * 1024)
        .readTimeout(Duration.ofSeconds(30))         // clients send heartbeats every 10 s
        .handshakeTimeout(Duration.ofSeconds(5))
        .maxConnections(10_000)
        .maxPendingBytes(4 * 1024 * 1024)
        .overflowPolicy(OverflowPolicy.DISCONNECT)
        .listener(listener)
        .build();
```

Class names that a peer sends are quoted in exception messages (the `cause` passed to `onDisconnected`) with control and non-ASCII characters escaped, so logging them is safe.

## TLS

TLS goes through the JDK's `SSLSocket`. Pass an `SSLContext` to both builders: its key managers provide the certificate, its trust managers decide which peer certificates to accept.

```java
ProtoServer server = ProtoServer.builder(8443)
        .tls(serverContext)                     // e.g. key material from a PKCS12 keystore
        .tlsClientAuth(TlsClientAuth.NEED)      // optional: require client certificates (default NONE)
        .listener(listener)
        .build();

ProtoClient client = ProtoClient.builder("server.example.com", 8443)
        .tls(clientContext)                     // trusts the server's certificate
        .build();                               // hostname verification is on by default
client.connect();                               // throws an SSLException if the TLS handshake fails
SSLSession session = client.tlsSession();
```

- The TLS handshake runs on each connection's own thread, within `handshakeTimeout`, before the protocol preamble. If it fails, `connect()` throws and no listener method is called on either side.
- The client checks that the server's certificate matches the host name it was given, as HTTPS clients do. `tlsHostnameVerification(false)` turns that off, for tests only.
- Both sides must use TLS: a TLS client and a plain server (or the reverse) fail to connect.
- A handshake rejected by the server (for example a missing client certificate with `TlsClientAuth.NEED`) may surface on the client as a connection reset rather than an `SSLException`: with TLS 1.3, the client's side of the handshake is over before the server checks its certificate.
- `tlsSession()` on a client or a server connection gives the negotiated protocol, cipher suite and peer certificates.
- Graceful close works the same over TLS (1.2 and 1.3): the queue is flushed and `close_notify` is sent.

## Module path

The library is the module `io.github.quantranuk.protobuf.nio`, and it calls `getDefaultInstance()` on your generated classes reflectively, so on the module path their package must be exported to it. Otherwise receiving a message of that type disconnects the peer, with an error naming the line to add:

```java
module com.example.app {
    requires io.github.quantranuk.protobuf.nio;
    exports com.example.app.proto to io.github.quantranuk.protobuf.nio;
}
```

On the classpath, nothing is needed.

## Connection limits

Threads aren't the limit: virtual threads are cheap, and an idle connection costs about 7 KB of heap, mostly its two parked thread stacks. Its read and write buffers are released after a second of idleness. What limits the number of connections is the operating system:

- **File descriptors.** Each connection is one file descriptor on each side. Raise `ulimit -n` (and `LimitNOFILE` for a systemd service) above the number of connections you expect, plus the JVM's own files.
- **Ephemeral ports.** Each client connection from one machine to one server address uses a local port, from `net.ipv4.ip_local_port_range` on Linux (32768–60999 by default, about 28,000). When the client closes first, its port stays in `TIME_WAIT` for a minute. Many clients on one machine may need a wider range or several server addresses; servers don't have this limit.
- **Accept backlog.** A burst of connections larger than `backlog` (default 1024) can overflow the operating system's queue, and those clients retry after a delay. `net.core.somaxconn` caps it on Linux.
- **Send queue memory.** Queues are bounded per connection: with many connections, lower `maxPendingBytes` so that the worst case (every connection's queue full) fits in the heap.
- `maxConnections` caps the server's connections. At the limit, a new connection replaces the oldest one still in its handshake, if any.

`MultiClientsTest` connects, uses and closes 15,000 clients against `SampleServer` in about a second.

## Wire format

### Preamble

Right after connecting (and after the TLS handshake), each side sends an 8-byte preamble once, before any frame:

| Field | Size | Content |
|---|---|---|
| Magic | 4 bytes | `0x504E494F` (`PNIO` in ASCII) |
| Version | 2 bytes | Highest protocol version the sender supports (currently 1) |
| Flags | 2 bytes | Reserved: sent as 0, ignored on receipt |

Each side may send frames straight after its own preamble, without waiting for the peer's. Both sides then use the lower of the two versions. The connection is closed with `PROTOCOL_MISMATCH` if the magic is wrong, if the two sides have no version in common, or if no preamble arrives within the handshake timeout.

### Frames

Each message is sent as one frame:

| Field | Size | Content |
|---|---|---|
| Signature | 4 bytes | `0x7A6B5C4D`, used to detect corrupt or foreign data |
| Class name length | 4 bytes | Length *N* of the class name, 1 to 4096 |
| Payload length | 4 bytes | Length *M* of the protobuf payload, 0 to the maximum message size |
| Class name | *N* bytes | Fully qualified Java class name of the message, in ISO-8859-1 |
| Payload | *M* bytes | The serialized protobuf message |

Integers are big-endian. A frame with both lengths 0 is a **heartbeat**: it's sent when nothing else has been sent for the heartbeat interval, it resets the peer's read timeout, and it's never passed to the application.

The receiver uses the class name to find the generated class and its parser, so:

- both sides must have the **same generated protobuf classes** on their classpath;
- **renaming or moving** a message's Java class or package breaks compatibility with peers that still use the old name;
- peers must be Java: other languages can implement the format, but they must send Java class names.

## Performance

`SampleClientBenchmarkTest` sends 1,000,000 heartbeat requests to `SampleServer` over one connection without waiting, and waits for all 1,000,000 responses; it does this 10 times after a warm-up. Each request and response is built and parsed in full, with a string field. Default settings, loopback, JDK 25, 16-core machine, server already warm:

```
Round 1: 1000000 round trips in 139 ms, 7168 round trips per millisecond
Round 2: 1000000 round trips in 172 ms, 5800 round trips per millisecond
Round 3: 1000000 round trips in 130 ms, 7638 round trips per millisecond
Round 4: 1000000 round trips in 137 ms, 7292 round trips per millisecond
Round 5: 1000000 round trips in 107 ms, 9339 round trips per millisecond
```

That's about 5–10 million round trips per second. One way on one connection, with small messages, a separate probe measured about 9–11 million messages per second, and 6–8 million with TLS 1.3.

Per message, the library itself allocates 24 bytes on the sending side (the queue node) and nothing on the writer; the receiving side allocates only what protobuf's parser does (about 40 bytes for a small numeric message, nothing with a [raw handler](#raw-handlers)). Results depend on hardware, message size and network: run the samples to measure your own setup.

## Versioning

protobuf-nio follows [Semantic Versioning](https://semver.org/), and [`CHANGELOG.md`](CHANGELOG.md) lists the changes in each release. The public API covered by it is:

- **The Java API:** the types in `io.github.quantranuk.protobuf.nio`, the only package the module exports. Anything in `io.github.quantranuk.protobuf.nio.internal` may change in any release.
- **The wire protocol:** peers with the same major version can always talk to each other. New protocol features are negotiated through the version in the [preamble](#preamble), so a newer peer still works with an older one of the same major version.
- **The minimum Java version** (currently 25).

So:

- **Patch releases** (2.0.x) fix bugs.
- **Minor releases** (2.x) add features without breaking code that compiles against the previous version. They may add builder settings, methods to `ProtoClient`, `ProtoServer` and `ProtoConnection` (which applications use but don't implement), default methods to the listener interfaces, and constants to the enums: give a `switch` over `DisconnectReason` or `SendException.Reason` a `default` branch.
- **Major releases** may change or remove API, change the wire protocol incompatibly, or raise the minimum Java version.

## Building

```bash
./mvnw clean install                          # build everything and run the tests
./mvnw test -pl protobuf-nio-core             # core tests only
```

Use the Maven Wrapper (`./mvnw`). Building requires JDK 25 or later.

## Contributing

Bug reports, fixes and improvements are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Please report security vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
