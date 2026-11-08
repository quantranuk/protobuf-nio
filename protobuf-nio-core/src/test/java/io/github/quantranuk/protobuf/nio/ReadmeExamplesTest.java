package io.github.quantranuk.protobuf.nio;

import io.github.quantranuk.protobuf.nio.readme.Ping;
import com.google.protobuf.MessageLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles every Java example in the README (and in {@code llms.txt}, when it exists) against the real library, so an
 * API change that breaks an example fails the build. People and coding agents copy examples literally.
 * <p>
 * Each code block becomes the body of a method in its own class, which declares what the examples take for granted:
 * the messages of {@code ReadmeExamples.proto} and the test protos, a logger {@code log}, a message {@code update}, the
 * TLS contexts {@code serverContext} and {@code clientContext}, a flag {@code running}, and an
 * {@code onPriceUpdate} method. A block that starts with {@code module} (a module declaration) isn't compiled.
 */
public class ReadmeExamplesTest {

    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)```", Pattern.DOTALL);

    private static final String HEADER = """
            package readme;

            import io.github.quantranuk.protobuf.nio.*;
            import io.github.quantranuk.protobuf.nio.proto.TestHeartBeat.*;
            import io.github.quantranuk.protobuf.nio.readme.*;
            import com.google.protobuf.*;
            import javax.net.ssl.*;
            import java.io.IOException;
            import java.net.*;
            import java.nio.ByteBuffer;
            import java.time.Duration;
            import java.util.concurrent.*;

            abstract class %s {
                org.slf4j.Logger log;
                MessageLite update;
                SSLContext serverContext;
                SSLContext clientContext;
                volatile boolean running;

                abstract void onPriceUpdate(ProtoClient client, PriceUpdate update);

                void example() throws Exception {
            // %s line %d
            """;

    private static final String FOOTER = """

                }
            }
            """;

    @TempDir
    Path directory;

    @Test
    public void examplesCompile() throws Exception {
        Path root = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
        List<Example> examples = new ArrayList<>();
        for (String document : List.of("README.md", "llms.txt")) {
            Path file = root.resolve(document);
            if (Files.exists(file)) {
                examples.addAll(extract(document, Files.readString(file)));
            }
        }
        assertFalse(examples.isEmpty(), "No Java examples found under " + root);

        Path sources = directory.resolve("src/readme");
        Files.createDirectories(sources);
        List<String> command = new ArrayList<>(List.of(tool("javac"), "-nowarn", "-proc:none",
                "-d", directory.resolve("out").toString(), "-cp", classpath()));
        for (Example example : examples) {
            Path source = sources.resolve(example.className() + ".java");
            Files.writeString(source, HEADER.formatted(example.className(), example.document(), example.line())
                    + example.code() + FOOTER);
            command.add(source.toString());
        }
        Process javac = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(javac.waitFor(120, TimeUnit.SECONDS), "javac timed out");
        if (javac.exitValue() != 0) {
            StringBuilder report = new StringBuilder("Examples that don't compile (the generated class names its document and line):\n");
            for (Example example : examples) {
                if (output.contains(example.className() + ".java")) {
                    report.append("  ").append(example.className()).append(": ").append(example.document())
                            .append(" line ").append(example.line()).append('\n');
                }
            }
            fail(report + "\n" + output);
        }
    }

    /** The Java code blocks of a document, except module declarations, with the line each block starts on. */
    private static List<Example> extract(String document, String text) {
        List<Example> examples = new ArrayList<>();
        Matcher matcher = JAVA_BLOCK.matcher(text);
        while (matcher.find()) {
            String code = matcher.group(1).strip();
            if (code.startsWith("module ")) {
                continue;
            }
            if (!code.endsWith(";") && !code.endsWith("}")) {
                code += ";";   // a builder fragment, such as "ProtoServer.builder(3456).allowedMessageTypes(...)"
            }
            int line = text.substring(0, matcher.start()).split("\n", -1).length + 1;
            String prefix = document.replaceAll("[^A-Za-z]", "_");
            examples.add(new Example(prefix + "_line" + line, document, line, code));
        }
        return examples;
    }

    /** The library's real classes, the test classes (generated messages included) and the library's dependencies. */
    private static String classpath() throws URISyntaxException {
        return String.join(File.pathSeparator, location(ProtoClient.class), location(Ping.class),
                location(MessageLite.class), location(LoggerFactory.class));
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String tool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private record Example(String className, String document, int line, String code) {
    }
}
