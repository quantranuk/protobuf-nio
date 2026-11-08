package io.github.quantranuk.protobuf.nio;

import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a consumer module on the module path, in a child JVM, against the library's real module (not the test-patched
 * one): its generated protobuf class is received only if its package is exported to the library, and otherwise the
 * error names the line to add. The consumer reuses the test protos' generated source.
 */
public class ModulePathTest {

    private static final String LIBRARY = "io.github.quantranuk.protobuf.nio";

    @TempDir
    Path directory;

    @ParameterizedTest(name = "exported: {0}")
    @ValueSource(booleans = {true, false})
    public void consumerModule(boolean exported) throws Exception {
        Path source = directory.resolve("src/com.example.consumer");
        Files.createDirectories(source.resolve("com/example/consumer"));
        Files.writeString(source.resolve("module-info.java"), """
                module com.example.consumer {
                    requires %s;
                    %s
                }
                """.formatted(LIBRARY, exported ? "exports io.github.quantranuk.protobuf.nio.proto to " + LIBRARY + ";" : ""));
        Files.writeString(source.resolve("com/example/consumer/Main.java"), """
                package com.example.consumer;

                import io.github.quantranuk.protobuf.nio.*;
                import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.HeartBeatRequest;
                import java.net.*;
                import java.util.concurrent.*;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        BlockingQueue<Object> results = new LinkedBlockingQueue<>();
                        ProtoServer server = ProtoServer.builder(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                                .listener(ProtoServerListener.builder()
                                        .onMessage(HeartBeatRequest.class, (c, m) -> results.add("received " + m.getRequestTimeMillis()))
                                        .onDisconnected((c, reason, cause) -> results.add(reason + ": " + cause))
                                        .build())
                                .build();
                        server.start();
                        try (ProtoClient client = ProtoClient.builder("localhost", server.localAddress().getPort()).build()) {
                            client.connect();
                            client.send(HeartBeatRequest.newBuilder().setRequestTimeMillis(42).build());
                            System.out.println("result: " + results.poll(10, TimeUnit.SECONDS));
                        } finally {
                            server.close();
                        }
                    }
                }
                """);
        Path generated = Path.of(System.getProperty("basedir", "."),
                "target/generated-test-sources/protobuf/io/github/quantranuk/protobuf/nio/proto/TestHeartBeat.java");
        Path target = source.resolve("io/github/quantranuk/protobuf/nio/proto/TestHeartBeat.java");
        Files.createDirectories(target.getParent());
        Files.copy(generated, target);

        String modulePath = String.join(File.pathSeparator, location(ProtoClient.class), location(MessageLite.class),
                location(LoggerFactory.class));
        Path classes = directory.resolve("out");
        run(tool("javac"), "-nowarn", "-d", classes.toString(), "--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.consumer", "--module-path", modulePath);
        String output = run(tool("java"), "--module-path", classes + File.pathSeparator + modulePath,
                "--module", "com.example.consumer/com.example.consumer.Main");

        if (exported) {
            assertTrue(output.contains("result: received 42"), output);
        } else {
            assertTrue(output.contains("result: PROTOCOL_ERROR"), output);
            assertTrue(output.contains("add \"exports io.github.quantranuk.protobuf.nio.proto to " + LIBRARY + ";\""), output);
        }
    }

    /** The jar or directory a class was loaded from: for the library, its real classes, not the test-patched module. */
    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String tool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Timed out: " + List.of(command));
        assertTrue(process.exitValue() == 0, "Failed (" + process.exitValue() + "): " + new ArrayList<>(List.of(command)) + "\n" + output);
        return output;
    }
}
