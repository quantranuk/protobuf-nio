# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Java library for sending and receiving protobuf messages over Java NIO sockets (`java.nio.channels.Asynchronous*`), with no netty/mina dependency. SSL/TLS is not supported; per the README, adding it would need a major redesign because of how `SSLEngine` works.

## Build & test

Maven multi-module project on Java 25 (`maven.compiler.release=25`). The enforcer requires JDK 25+ and Maven 3.9+. Always build with the Maven Wrapper (`./mvnw`, pinned to Apache Maven 3.9.12): Ubuntu's packaged `/usr/bin/mvn` can't load the ascopes protobuf plugin (it fails with a Guice `NullInjectedIntoNonNullable` error). On this machine JDK 25 is at `/usr/lib/jvm/java-25-openjdk-amd64`, but the default `java` is 21, so set `JAVA_HOME` if needed.

```bash
./mvnw clean install                       # build all modules
./mvnw test -pl protobuf-nio-core          # core unit tests only
./mvnw test -pl protobuf-nio-core -Dtest=ProtobufSerializerTest            # single test class
./mvnw test -pl protobuf-nio-core -Dtest=ProtobufSerializerTest#testName   # single method
```

All dependency and plugin versions live as properties in the root `pom.xml` (`dependencyManagement` with the JUnit and Log4j BOMs, plus `pluginManagement`). Modules declare dependencies without versions. Tests use JUnit Jupiter (JUnit 6), run by surefire 3.x. CI is GitHub Actions (`.github/workflows/build.yml`, running `./mvnw -B verify` on Temurin 25).

Protobuf code is generated at build time by `io.github.ascopes:protobuf-maven-plugin`, with the `protoc` version tied to `${protobuf.version}`:
- core: `src/test/proto/TestHeartBeat.proto` → test classes (`generate-test`)
- sample: `src/main/proto/HeartBeat.proto` → main classes (`generate`)

The sample module's "tests" (`SampleServer`, `SampleClient`, `SampleClientBenchmarkTest`, `MultiClientsTest`) are `main()` programs in `src/main`, not JUnit tests. Start `SampleServer` first, then a client.

## Architecture (protobuf-nio-core)

**Public API:** `ProtoChannelFactory.newClient(host, port)` / `newServer(port)` return builders that produce `ProtoSocketChannel` / `ProtoServerSocketChannel`. Callers register functional handler interfaces from `handlers/` (connection, disconnection, message received/sent/send-failure). Every handler callback receives the remote `SocketAddress`.

**Wire format** (`serializer/ProtobufSerializer`): each frame is a 12-byte header of three ints, `SIGNATURE (0x7A6B5C4D)`, class-name length and payload length. The ISO-8859-1 fully-qualified Java class name comes next, then the protobuf bytes. On receive, the class name is resolved by reflection (`Class.forName` → static `parseFrom(ByteBuffer)`), and the `Method` is cached by the class-name `ByteBuffer`. Both peers therefore need the same generated protobuf classes on their classpath. Renaming or moving a proto class's Java package breaks wire compatibility.

**Client channel** (`impl/AsyncProtoSocketChannel`): the base unit. It owns an `AsynchronousSocketChannel` and delegates to two helpers:
- `SocketChannelReader`: a state machine (`READING_MESSAGE_HEADER` → `READING_MESSAGE_BODY`) that buffers incoming bytes in a `ByteArrayDequeue` until a full frame is present. A bad signature or EOF goes through the failure path, which disconnects the channel.
- `SocketChannelWriter`: `sendMessage` is non-blocking. It enqueues onto the write executor, which batches queued messages up to the write buffer size, serializes them into a `ByteArrayDequeue`, and writes in chunks. A partial write pushes the unwritten bytes back with `pushLast`. The outbound queue is capped at `DefaultSetting.MAX_WRITE_MESSAGE_QUEUE_SIZE`.

**Threading model (important):** reader and writer state is not synchronized. Correctness relies on each channel's reads all running on one **single-threaded** read executor and its writes all running on one single-threaded write executor. Completion handlers re-dispatch onto those executors before touching state. `setReadExecutor`/`setWriteExecutor` reject a `ThreadPoolExecutor` with max pool size ≠ 1. The read executor also backs the `AsynchronousChannelGroup`. Executors injected from outside are not shut down on `disconnect()`, and internally created ones are. Sharing one executor pair across many channels is how a single thread handles thousands of connections, as in `MultiClientsTest`.

**Server channel** (`impl/AsyncProtoServerSocketChannel`): accepts on its own acceptor executor. For each accepted socket it builds an `AsyncProtoSocketChannel`, injecting the server's shared single-threaded read and write executors and calling `setSocketChannel` with the accepted socket. It keeps the channels in a `Map<SocketAddress, ProtoSocketChannel>` and forwards each channel's events to the server-level handlers. A per-client disconnect removes that client from the map.

`init()` / `disconnect()` carry `jakarta.annotation` `@PostConstruct` / `@PreDestroy` so the channels can be managed as DI beans. The factory builders call `init()` themselves.

`protobuf-java` (4.x) and `slf4j-api` are `provided` scope in core. Consumers supply them. Log4j is test scope only in core, so don't add a logging backend to core's compile or runtime scope.
