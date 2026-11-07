[![Build](https://github.com/quantranuk/protobuf-nio/actions/workflows/build.yml/badge.svg)](https://github.com/quantranuk/protobuf-nio/actions/workflows/build.yml)

# protobuf-nio

A small Java library for sending and receiving [Protocol Buffers](https://protobuf.dev/) messages over TCP, built directly on Java NIO asynchronous sockets (`java.nio.channels.Asynchronous*`). It has no Netty or Mina dependency.

- **No framework dependencies:** only `protobuf-java` and `slf4j-api`, both supplied by your application.
- **Protobuf-native API:** send any generated `Message`, and receive it on the other side already parsed. There's no per-type registration.
- **Non-blocking sends:** `sendMessage` returns immediately. Messages are batched and written by a dedicated writer thread.
- **Fast:** about 2 million request/response round-trips per second on one connection (see [Benchmark](#benchmark)).
- **Scalable:** one read thread and one write thread can serve thousands of connections.

> **Limitations:** no SSL/TLS (see [below](#why-no-ssltls)). Both peers must be Java and have the same generated protobuf classes on their classpath (see [Wire format](#wire-format)).

## Requirements

- Java 21 or later
- `protobuf-java` 4.x and `slf4j-api` 2.x on the classpath. They are `provided` dependencies, so your project must declare them, plus an SLF4J backend if you want logs.

## Installation

Version 2.x isn't published yet. Build and install it into your local Maven repository:

```bash
git clone https://github.com/quantranuk/protobuf-nio.git
cd protobuf-nio
./mvnw clean install
```

Then add the dependencies:

```xml
<dependency>
    <groupId>com.github.quantranuk</groupId>
    <artifactId>protobuf-nio-core</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>4.36.2</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.20</version>
</dependency>
```

## Quick start

### Server

```java
ProtoServerSocketChannel server = ProtoChannelFactory.newServer(3456).build();

server.addMessageReceivedHandler((clientAddress, message) -> {
    if (message instanceof HeartBeatRequest request) {
        // Reply to the client that sent the request. Never blocks.
        server.sendMessage(clientAddress, HeartBeatResponse.newBuilder()
                .setRequestTimeMillis(request.getRequestTimeMillis())
                .build());
    }
});
server.addConnectionHandler(clientAddress -> System.out.println("Connected: " + clientAddress));
server.addDisconnectionHandler(clientAddress -> System.out.println("Disconnected: " + clientAddress));

server.start();                     // bind and start accepting connections
// ...
server.sendMessageToAll(message);   // broadcast to every connected client
server.stop();                      // disconnect all clients and close the server
```

### Client

```java
ProtoSocketChannel client = ProtoChannelFactory.newClient("localhost", 3456).build();

client.addMessageReceivedHandler((serverAddress, message) -> {
    if (message instanceof HeartBeatResponse response) {
        System.out.println("Round trip: " + (System.currentTimeMillis() - response.getRequestTimeMillis()) + " ms");
    }
});
client.addMessageSendFailureHandler((serverAddress, message, error) ->
        System.err.println("Could not send " + message.getClass().getSimpleName() + ": " + error));

client.connect();                   // blocks until connected
client.sendMessage(HeartBeatRequest.newBuilder()
        .setRequestTimeMillis(System.currentTimeMillis())
        .build());                  // never blocks
// ...
client.disconnect();
```

A client channel is single-use: after it disconnects, for whatever reason, it can't connect again. To reconnect, build a new channel and register its handlers again:

```java
ProtoSocketChannel connectWithRetry(String host, int port) throws InterruptedException {
    long backoffMillis = 500;
    while (true) {
        ProtoSocketChannel client = ProtoChannelFactory.newClient(host, port).build();
        CountDownLatch connected = new CountDownLatch(1);
        client.addConnectionHandler(address -> connected.countDown());
        client.addMessageReceivedHandler(this::onMessageReceived);
        client.connect();
        if (connected.getCount() == 0) {
            // Added only after connecting: a failed connect() also calls disconnection handlers
            client.addDisconnectionHandler(address -> scheduleReconnect());   // your own reconnect policy
            return client;
        }
        Thread.sleep(backoffMillis);
        backoffMillis = Math.min(backoffMillis * 2, 30_000);
    }
}
```

Working examples are in [`protobuf-nio-sample`](protobuf-nio-sample/src/main/java/com/github/quantranuk/protobuf/nio/sample). Start `SampleServer`, then run `SampleClient`, `SampleClientBenchmarkTest` or `MultiClientsTest`.

## Handlers

Register handlers on a client or a server. Each callback receives the address of the remote peer.

| Handler | Called when |
|---|---|
| `ConnectionHandler.onConnected(address)` | A connection is established. On the server, this means a client was accepted. |
| `DisconnectionHandler.onDisconnected(address)` | A connection is closed, by either side or because of an error. Called once per connection. |
| `MessageReceivedHandler.onMessageReceived(address, message)` | A complete message has been received and parsed. |
| `MessageSentHandler.onMessageSent(address, message)` | A message has been fully written to the socket. |
| `MessageSendFailureHandler.onMessageSendFailure(address, message, error)` | A message couldn't be sent, for example because the connection failed or closed while it was queued. |

An exception thrown by a handler is logged and doesn't affect the connection or the other handlers.

## Configuration

Both builders (`newClient(...)` and `newServer(...)`) accept:

| Setting | Default | Description |
|---|---|---|
| `setReadBufferSize(bytes)` | 64 KB | Largest single socket read. Reads start at 8 KB and grow only while they keep filling the buffer, so idle connections stay small. |
| `setWriteBufferSize(bytes)` | 64 KB | Largest batch of queued messages written in one go. A single larger message is still sent. |
| `setReadTimeoutMillis(ms)` | 0 (none) | Disconnect if nothing is received for this long. |
| `setWriteTimeoutMillis(ms)` | 10 000 | Disconnect if a write can't complete within this time, for example when the peer stops reading. |
| `setMaxMessageSize(bytes)` | 64 MiB | Largest accepted protobuf payload. A peer sending a larger message is disconnected. |

The client builder also accepts `setReadExecutor` and `setWriteExecutor` (see [Many connections](#many-connections)).

Up to 10 million messages can be queued per connection. Beyond that, `sendMessage` throws `IllegalStateException`. It also throws that exception if the connection is already closed.

## Threading model

- **`sendMessage` is thread-safe and non-blocking.** It adds the message to a lock-free queue. A single writer thread drains the queue in batches. Messages sent from the same thread arrive in the order they were sent.
- **Handlers run on the library's I/O threads.** Received messages are delivered on the reader thread. Sent and send-failure notifications come from the writer thread. On a server, all connections share one reader thread and one writer thread, and new connections are reported from the acceptor thread. So:
  - **Don't block in handlers.** A slow handler delays every connection that shares the thread. Hand long-running work to your own executor.
  - **One thread per kind of event.** A server's `MessageReceivedHandler` is never called concurrently, even with many clients connected. Handlers for different kinds of event, such as received and sent, can run at the same time on different threads.

### Many connections

Each client creates its own reader and writer threads by default. To run many clients cheaply, share one pair of executors. Each executor must be single-threaded:

```java
ExecutorService readExecutor = Executors.newSingleThreadExecutor();
ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

for (int i = 0; i < 15_000; i++) {
    ProtoSocketChannel client = ProtoChannelFactory.newClient("localhost", 3456)
            .setReadExecutor(readExecutor)
            .setWriteExecutor(writeExecutor)
            .build();
    client.connect();
}
```

Executors you provide aren't shut down by `disconnect()`. Shut them down yourself when every client is done.

## Error handling

A connection is closed, and its `DisconnectionHandler`s are called, when:

- the peer closes the connection;
- the read or write timeout expires;
- a write fails;
- the peer sends invalid data: a bad frame signature, a class-name or payload length out of range (see `setMaxMessageSize`), an unknown or non-protobuf class name, or a payload that fails to parse.

When a connection closes, every message still queued for it is reported to the `MessageSendFailureHandler`s. Unless `sendMessage` throws, every message it accepts is eventually reported as either sent or failed.

If `connect()` fails, the error is logged and the `DisconnectionHandler`s are called.

## Wire format

Each message is sent as one frame:

| Field | Size | Content |
|---|---|---|
| Signature | 4 bytes | `0x7A6B5C4D`, used to detect corrupt or foreign data |
| Class name length | 4 bytes | Length *N* of the class name |
| Payload length | 4 bytes | Length *M* of the protobuf payload |
| Class name | *N* bytes | Fully qualified Java class name of the message, in ISO-8859-1 |
| Payload | *M* bytes | The serialized protobuf message |

Integers are big-endian. The receiver uses the class name to find the generated class and its parser, so:

- both sides must have the **same generated protobuf classes** on their classpath;
- **renaming or moving** a message's Java class or package breaks compatibility with peers that still use the old name;
- peers must be Java: other languages can implement the format, but they must send Java class names.

The receiver only instantiates classes that implement `com.google.protobuf.Message`. Any other class name is rejected without initializing the class.

## Dependency injection

`init()` and `disconnect()` / `stop()` are annotated with `jakarta.annotation` `@PostConstruct` and `@PreDestroy`. You can therefore create `AsyncProtoSocketChannel` or `AsyncProtoServerSocketChannel` as beans in a DI container and configure them with setters. The factory builders call `init()` for you.

## Benchmark

`SampleClientBenchmarkTest` sends 1,000,000 heartbeat requests to `SampleServer` over one connection and waits for all 1,000,000 responses. Default settings, loopback, JDK 25, 16-core machine:

```
SampleClientBenchmarkTest - Sending and receiving 1000000 message took 478 milliseconds
SampleClientBenchmarkTest - Average throughput: 2092.05 messages per millisecond (round-trip)
```

`MultiClientsTest` connects 15,000 clients that share a single read thread and a single write thread. Each client sends a request and receives a response:

```
MultiClientsTest - All 15000 clients are connected
MultiClientsTest - Sending heartbeat requests from all clients...
MultiClientsTest - All HeartBeat response messages have been received. Closing all clients..
```

Results depend on hardware, message size and network. Run the samples to measure your own setup.

## Building

```bash
./mvnw clean install                          # build everything and run the tests
./mvnw test -pl protobuf-nio-core             # core tests only
```

Use the Maven Wrapper (`./mvnw`). Building requires JDK 25 or later. The library is compiled for Java 21, and CI runs the tests on both Java 21 and Java 25.

## Why no SSL/TLS?

Java's [`SSLEngine`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLEngine.html) has to be driven by hand alongside non-blocking I/O, through handshakes, wrapping, unwrapping and renegotiation. Adding it would mean a major redesign of the reader and writer. If you need encryption, run the connection through a TLS tunnel or proxy, such as stunnel, a service mesh sidecar, or an SSH tunnel.

## License

[MIT](LICENSE)
