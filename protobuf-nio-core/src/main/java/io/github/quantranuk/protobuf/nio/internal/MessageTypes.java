package io.github.quantranuk.protobuf.nio.internal;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.net.ProtocolException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the class names received on the wire to protobuf parsers, through one class loader. One instance is shared by
 * every connection that uses the same class loader, and parsers are cached by the class name bytes.
 */
final class MessageTypes {

    // Values are weak so that the registry (which references the loader through its classes) doesn't keep the loader alive
    private static final Map<ClassLoader, WeakReference<MessageTypes>> BY_LOADER = new WeakHashMap<>();

    private final ClassLoader loader;
    // Keyed by a private copy of the name bytes, never by the caller's buffer, which is a view over a reusable read buffer
    private final Map<ByteBuffer, Parser<? extends MessageLite>> parsers = new ConcurrentHashMap<>();

    private MessageTypes(ClassLoader loader) {
        this.loader = loader;
    }

    static MessageTypes forClassLoader(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        synchronized (BY_LOADER) {
            WeakReference<MessageTypes> ref = BY_LOADER.get(loader);
            MessageTypes types = ref == null ? null : ref.get();
            if (types == null) {
                types = new MessageTypes(loader);
                BY_LOADER.put(loader, new WeakReference<>(types));
            }
            return types;
        }
    }

    /**
     * @param bytes  buffer holding the ISO-8859-1 class name; not modified and not retained
     * @param offset start of the name
     * @param length length of the name
     * @return the parser of that class
     * @throws ProtocolException if the name isn't a loadable protobuf message class
     */
    Parser<? extends MessageLite> parser(byte[] bytes, int offset, int length) throws ProtocolException {
        ByteBuffer name = ByteBuffer.wrap(bytes, offset, length);
        Parser<? extends MessageLite> parser = parsers.get(name);
        if (parser == null) {
            parser = resolve(new String(bytes, offset, length, ProtobufSerializer.CHARSET), Protocol.quotableName(bytes, offset, length));
            byte[] copy = new byte[length];
            System.arraycopy(bytes, offset, copy, 0, length);
            parsers.putIfAbsent(ByteBuffer.wrap(copy).asReadOnlyBuffer(), parser);
        }
        return parser;
    }

    /** {@link ProtocolException} has no constructor taking a cause. */
    static ProtocolException protocolException(String message, Throwable cause) {
        ProtocolException e = new ProtocolException(message);
        e.initCause(cause);
        return e;
    }

    int cachedParserCount() {
        return parsers.size();
    }

    /**
     * @param className the class name from the peer
     * @param quoted    the same, made safe for exception messages (see {@link Protocol#quotableName})
     */
    private Parser<? extends MessageLite> resolve(String className, String quoted) throws ProtocolException {
        final Class<?> type;
        try {
            // Don't initialise the class until it's known to be a protobuf message: the name comes from the network
            type = Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw protocolException("Unknown message class: " + quoted, e);
        }
        if (!MessageLite.class.isAssignableFrom(type)) {
            throw new ProtocolException("Not a protobuf message class: " + quoted);
        }
        try {
            return parserOf(type);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw protocolException("Unable to get the parser of " + quoted + ": " + e.getMessage(), e);
        }
    }

    /**
     * The parser of a generated protobuf message class, through its static {@code getDefaultInstance()}.
     * @throws IllegalAccessException if the class is in a named module that doesn't export its package to this one;
     *                                the message says what to add to that module's descriptor
     */
    static Parser<? extends MessageLite> parserOf(Class<?> type) throws ReflectiveOperationException {
        MessageLite defaultInstance;
        try {
            defaultInstance = (MessageLite) type.getMethod("getDefaultInstance").invoke(null);
        } catch (IllegalAccessException e) {
            Module module = type.getModule();
            Module self = MessageTypes.class.getModule();
            if (module.isNamed() && !module.isExported(type.getPackageName(), self)) {
                IllegalAccessException explained = new IllegalAccessException("module " + module.getName()
                        + " doesn't export package " + type.getPackageName() + " to " + self.getName() + ": add \"exports "
                        + type.getPackageName() + " to " + self.getName() + ";\" to its module-info.java");
                explained.initCause(e);
                throw explained;
            }
            throw e;
        }
        return defaultInstance.getParserForType();
    }
}
