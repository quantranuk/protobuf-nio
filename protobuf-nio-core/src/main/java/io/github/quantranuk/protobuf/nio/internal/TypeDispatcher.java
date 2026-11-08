package io.github.quantranuk.protobuf.nio.internal;

import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-type handler lookup for the callbacks set on the builders ({@link Callbacks}): exact class match through a {@link ClassValue}, so dispatching a
 * message neither hashes nor allocates. Types without a handler go to the fallback, or are logged at WARN once per type
 * and dropped when there's no fallback.
 *
 * @param <H> the handler type
 */
public final class TypeDispatcher<H> {

    private static final Logger LOG = LoggerFactory.getLogger(TypeDispatcher.class);

    private final Map<Class<?>, H> handlers;
    private final H fallback;
    private final Set<Class<?>> warned = ConcurrentHashMap.newKeySet();
    private final ClassValue<H> lookup = new ClassValue<>() {
        @Override
        protected H computeValue(Class<?> type) {
            return handlers.getOrDefault(type, fallback);
        }
    };

    /**
     * @param handlers handlers by exact message class (copied)
     * @param fallback handler for other types, or {@code null} to log and drop them
     */
    public TypeDispatcher(Map<Class<?>, H> handlers, H fallback) {
        this.handlers = new HashMap<>(handlers);
        this.fallback = fallback;
    }

    /** @return the handler for this message, or {@code null} if it should be dropped (already logged) */
    public H handlerFor(MessageLite message) {
        H handler = lookup.get(message.getClass());
        if (handler == null && warned.add(message.getClass())) {
            LOG.warn("No handler for messages of type {}; dropping them (this is logged once per type)", message.getClass().getName());
        }
        return handler;
    }
}
