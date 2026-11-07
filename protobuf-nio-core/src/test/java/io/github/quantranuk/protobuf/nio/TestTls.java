package io.github.quantranuk.protobuf.nio;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Self-signed certificates for TLS tests, generated with the JDK's {@code keytool} into a temporary directory, so no
 * keystore that could expire is committed.
 */
final class TestTls {

    private static final char[] PASSWORD = "changeit".toCharArray();

    /** CN=localhost, for localhost and 127.0.0.1; trusted by {@link #clientContext}. */
    final KeyStore localhost;
    /** CN=other.example, for other.example only; trusted by {@link #clientContext}. */
    final KeyStore otherHost;
    /** CN=localhost, but trusted by nobody. */
    final KeyStore untrusted;
    /** CN=client; trusted by {@link #serverContext}. */
    final KeyStore client;

    TestTls(Path directory) throws Exception {
        localhost = generate(directory, "localhost", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        otherHost = generate(directory, "other", "CN=other.example", "SAN=dns:other.example");
        untrusted = generate(directory, "untrusted", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
        client = generate(directory, "client", "CN=client", null);
    }

    /** A server context presenting {@code keys}, trusting the client certificate. */
    SSLContext serverContext(KeyStore keys) throws GeneralSecurityException {
        return context("TLS", keys, client);
    }

    /** A client context trusting {@link #localhost} and {@link #otherHost}, presenting {@code keys} if not null. */
    SSLContext clientContext(String protocol, KeyStore keys) throws GeneralSecurityException {
        return context(protocol, keys, localhost, otherHost);
    }

    private static SSLContext context(String protocol, KeyStore keys, KeyStore... trusted) throws GeneralSecurityException {
        KeyManagerFactory keyManagers = null;
        if (keys != null) {
            keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, PASSWORD);
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try {
            trust.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        int n = 0;
        for (KeyStore store : trusted) {
            trust.setCertificateEntry("trusted-" + n++, certificate(store));
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance(protocol);
        context.init(keyManagers == null ? null : keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    static Certificate certificate(KeyStore store) throws GeneralSecurityException {
        return store.getCertificate(store.aliases().nextElement());
    }

    private static KeyStore generate(Path directory, String name, String dname, String extension) throws Exception {
        Path file = directory.resolve(name + ".p12");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", dname, "-validity", "2",
                "-keystore", file.toString(), "-storetype", "PKCS12",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)));
        if (extension != null) {
            command.add("-ext");
            command.add(extension);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(file)) {
            store.load(in, PASSWORD);
        }
        return store;
    }
}
