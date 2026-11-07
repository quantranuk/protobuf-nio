# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Java library for sending and receiving protobuf messages over TCP with no netty/mina dependency.

**Status: mid-rewrite to 2.0.** 1.x (async NIO, `java.nio.channels.Asynchronous*`, no TLS) has been removed from `master`; its last version is the git tag `1.x-final`, which is the reference for 1.x behaviour, its tests (`ChannelRobustnessTest`) and its benchmark baseline. 2.0 is a rewrite on virtual threads with blocking sockets, a new API (`ProtoClient` / `ProtoServer` / `ProtoConnection`), TLS, backpressure and a one-time protocol preamble. The frame format is unchanged. There's no backward compatibility with 1.x, which had no users.

## Build & test

Maven multi-module project compiled for Java 25 (`maven.compiler.release=25`). Java 25 is the baseline because 2.0 moves to virtual threads, which pin carrier threads inside `synchronized` before JDK 24 (JEP 491). Building requires JDK 25+ (enforcer) and Maven 3.9+. Always build with the Maven Wrapper (`./mvnw`, pinned to Apache Maven 3.9.12): Ubuntu's packaged `/usr/bin/mvn` can't load the ascopes protobuf plugin (it fails with a Guice `NullInjectedIntoNonNullable` error). On this machine JDK 25 is at `/usr/lib/jvm/java-25-openjdk-amd64`, but the default `java` is 21, so set `JAVA_HOME` if needed.

```bash
./mvnw clean install                       # build all modules
./mvnw test -pl protobuf-nio-core          # core unit tests only
./mvnw test -pl protobuf-nio-core -Dtest=ProtobufSerializerTest            # single test class
./mvnw test -pl protobuf-nio-core -Dtest=ProtobufSerializerTest#testName   # single method
```

All dependency and plugin versions live as properties in the root `pom.xml` (`dependencyManagement` with the JUnit and Log4j BOMs, plus `pluginManagement`). Modules declare dependencies without versions. Tests use JUnit Jupiter (JUnit 6), run by surefire 3.x. CI is GitHub Actions (`.github/workflows/build.yml`): it builds and tests with Temurin 25 by running `./mvnw -B verify`.

Protobuf code is generated at build time by `io.github.ascopes:protobuf-maven-plugin`, with the `protoc` version tied to `${protobuf.version}`:
- core: `src/test/proto/TestHeartBeat.proto` → test classes (`generate-test`)
- sample: `src/main/proto/HeartBeat.proto` → main classes (`generate`)

`protobuf-nio-sample` is **not in the root `<modules>` list** during the rewrite: it still uses the 1.x API and goes back into the build once it's ported to 2.0. Its "tests" (`SampleServer`, `SampleClient`, `SampleClientBenchmarkTest`, `MultiClientsTest`) are `main()` programs in `src/main`, not JUnit tests.

## Architecture (protobuf-nio-core)

Everything currently lives in the non-exported package `io.github.quantranuk.protobuf.nio.internal`, carried over from 1.x:

- **`ProtobufSerializer`** (wire format): each frame is a 12-byte header of three ints, `SIGNATURE (0x7A6B5C4D)`, class-name length and payload length. The ISO-8859-1 fully-qualified Java class name comes next, then the protobuf bytes. `serializeInto` encodes straight into a batch buffer, with class names encoded once per class (`ClassValue`). On receive, the class name is resolved by reflection (`Class.forName(name, false, …)`, so the class isn't initialised before it's known to be a `Message`, then `getDefaultInstance().getParserForType()`). The `Parser` is cached in a global map keyed by a private copy of the name bytes, never the caller's buffer, which is a view over a reusable read buffer. (1.x's reader also kept an 8-entry per-reader cache to skip hashing; see `SocketChannelReader` at `1.x-final`.) Both peers need the same generated protobuf classes on their classpath, and renaming or moving a proto class's Java package breaks wire compatibility.
- **`ByteArrayDequeue`**: growable byte deque. `prepareWrite`/`commitWrite` let socket reads land directly in its storage.
- **`ByteUtils`**: big-endian int/long/string helpers.

Planned 2.0 threading model: each connection has a reader virtual thread and a writer virtual thread. Message listeners run on the connection's reader thread, so listeners for one connection run in order and different connections run concurrently. The writer drains the send queue in batches (one socket write per batch). Avoid `synchronized` on blocking paths (use `ReentrantLock`), and block once per batch, not once per message.

`protobuf-java` (4.x) and `slf4j-api` are compile-scope dependencies of core, so consumers get them transitively. Log4j is test scope only in core, so don't add a logging backend to core's compile or runtime scope. Use `MessageLite` rather than `Message` in new API code.
