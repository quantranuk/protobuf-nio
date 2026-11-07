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

Public types so far: `DisconnectReason`, `SendException` and `OverflowPolicy` (shared by the 2.0 client and server API, which isn't written yet). Everything else is in the non-exported `io.github.quantranuk.protobuf.nio.internal` package:

- **`Connection`**: one connection over a connected blocking `java.net.Socket` (blocking streams rather than `SocketChannel`, so TLS can later be a drop-in `SSLSocket`). It has a **reader virtual thread** and a **writer virtual thread**, and reports to an internal `ConnectionListener`: `onConnected`, `onMessage` and `onDisconnected` on the reader thread, `onSendFailure` on the writer thread. Listener exceptions are logged and ignored. Reader-only and writer-only fields aren't synchronized; only the send queue (`ConcurrentLinkedQueue`), the counters, the state and the termination are shared.
  - Handshake: the writer sends the 8-byte preamble (`Protocol`) first. The reader checks the peer's preamble within the handshake timeout (a wrong magic or no common version gives `PROTOCOL_MISMATCH`). `handshake()` completes before `onConnected`. **If the handshake fails, no listener method is called at all.**
  - Reader: one byte array with start and end indices. It starts at 64 bytes, jumps to 8 KB and doubles only while reads fill it (up to `readBufferSize`), and goes back to 64 bytes after `idleShrinkDelay` (1 s) of idleness. `SO_TIMEOUT` is set to the next deadline (handshake, read timeout or idle shrink). Frames are validated (signature, name length 1–4096, payload 0–`maxMessageSize`), and heartbeats (both lengths 0) are skipped. An 8-entry per-reader parser cache sits in front of `MessageTypes`.
  - Writer: drains the queue in batches, **one socket write per batch**. Senders `unpark` it only when it's parked (`writerParked` flag; Dekker-style ordering with the queue). It sends a heartbeat when idle for `heartbeatInterval` and releases its batch buffer after `idleShrinkDelay`.
  - Closing: failures (EOF, bad frame, timeouts, I/O errors) close the socket at once through `terminate()`; the first reason wins. `close()` is graceful: stop accepting sends → writer flushes → `shutdownOutput()` (FIN) → the reader waits for the peer's FIN → close the socket. A plain close with unread input would send a TCP reset and could make the peer lose data. All of this happens within `closeTimeout`, after which the socket is closed regardless. The reader's `finish()` then waits for the writer, fails leftovers with the shared `CLOSED` `SendException`, and calls `onDisconnected` last, exactly once.
- **`Watchdog`**: one shared platform daemon thread that enforces the write timeout (blocking socket writes have no timeout in the JDK) and the close deadline, by scanning registered connections at a tenth of the shortest timeout (10 ms to 1 s).
- **`ConnectionConfig`**: immutable settings plus a validating builder (V2's public builders delegate to it). The defaults match the 2.0 API appendix; `TCP_NODELAY` is on by default.
- **`ProtobufSerializer`** (frame encoding): each frame is a 12-byte header of three ints, `SIGNATURE (0x7A6B5C4D)`, class-name length and payload length, followed by the ISO-8859-1 fully-qualified Java class name and the protobuf bytes. `serializeInto` encodes straight into the batch buffer, with class names encoded once per class (`ClassValue`).
- **`MessageTypes`**: resolves received class names to parsers through one class loader (`Class.forName(name, false, loader)`, so a class isn't initialised until it's known to be a `MessageLite`), shared per loader through a weak map. Parsers are cached by a private copy of the name bytes, never the caller's buffer.
- Both peers need the same generated protobuf classes on their classpath, and renaming or moving a proto class's Java package breaks wire compatibility.

Measured on loopback (JDK 25): about 7.3 KB of heap per idle connection, of which about 5.4 KB is the two parked virtual-thread stacks.

`protobuf-java` (4.x) and `slf4j-api` are compile-scope dependencies of core, so consumers get them transitively. Log4j is test scope only in core, so don't add a logging backend to core's compile or runtime scope. Use `MessageLite` rather than `Message` in new API code. On hot paths, block once per batch rather than once per message, and avoid per-message allocation in library code.
