package io.github.quantranuk.protobuf.nio.internal;

import io.github.quantranuk.protobuf.nio.DisconnectReason;
import io.github.quantranuk.protobuf.nio.SendException;
import com.google.protobuf.MessageLite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The callbacks registered on a {@code ProtoServer} or {@code ProtoClient} builder, for endpoints of type {@code E}
 * ({@code ProtoConnection} on a server, {@code ProtoClient} on a client). Immutable: the public builders adapt their
 * handler types to these and build a new instance for each server or client, so the builders stay reusable.
 *
 * @param <E> the endpoint passed to every callback
 */
public final class Callbacks<E> {

    /** @param <E> the endpoint */
    @FunctionalInterface
    public interface OnMessage<E> {
        void accept(E endpoint, MessageLite message);
    }

    /** @param <E> the endpoint */
    @FunctionalInterface
    public interface OnSendFailure<E> {
        void accept(E endpoint, MessageLite message, SendException cause);
    }

    /** @param <E> the endpoint */
    @FunctionalInterface
    public interface OnDisconnected<E> {
        void accept(E endpoint, DisconnectReason reason, Throwable cause);
    }

    private final Consumer<E> onConnected;
    private final TypeDispatcher<OnMessage<E>> dispatcher;
    private final OnSendFailure<E> onSendFailure;
    private final OnDisconnected<E> onDisconnected;

    private Callbacks(Builder<E> builder) {
        onConnected = builder.onConnected;
        dispatcher = new TypeDispatcher<>(builder.handlers, builder.onOtherMessage);
        onSendFailure = builder.onSendFailure;
        onDisconnected = builder.onDisconnected;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    void connected(E endpoint) {
        onConnected.accept(endpoint);
    }

    /** Dispatches by the message's exact class; types without a handler go to the fallback, or are logged once and dropped. */
    void message(E endpoint, MessageLite message) {
        OnMessage<E> handler = dispatcher.handlerFor(message);
        if (handler != null) {
            handler.accept(endpoint, message);
        }
    }

    void sendFailure(E endpoint, MessageLite message, SendException cause) {
        onSendFailure.accept(endpoint, message, cause);
    }

    void disconnected(E endpoint, DisconnectReason reason, Throwable cause) {
        onDisconnected.accept(endpoint, reason, cause);
    }

    /**
     * Collects callbacks; unset ones do nothing. Not thread-safe, like the public builders it serves.
     *
     * @param <E> the endpoint
     */
    public static final class Builder<E> {
        private Consumer<E> onConnected = endpoint -> { };
        private final Map<Class<?>, OnMessage<E>> handlers = new HashMap<>();
        private OnMessage<E> onOtherMessage;
        private OnSendFailure<E> onSendFailure = (endpoint, message, cause) -> { };
        private OnDisconnected<E> onDisconnected = (endpoint, reason, cause) -> { };

        private Builder() {
        }

        public void onConnected(Consumer<E> handler) {
            onConnected = Objects.requireNonNull(handler, "handler");
        }

        /** @throws IllegalArgumentException if a handler for this type is already registered */
        public void onMessage(Class<?> type, OnMessage<E> handler) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(handler, "handler");
            if (handlers.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException("A handler for " + type.getName() + " is already registered");
            }
        }

        public void onOtherMessage(OnMessage<E> handler) {
            onOtherMessage = Objects.requireNonNull(handler, "handler");
        }

        public void onSendFailure(OnSendFailure<E> handler) {
            onSendFailure = Objects.requireNonNull(handler, "handler");
        }

        public void onDisconnected(OnDisconnected<E> handler) {
            onDisconnected = Objects.requireNonNull(handler, "handler");
        }

        /** @return the types that have an {@code onMessage} handler */
        @SuppressWarnings("unchecked")
        public Set<Class<? extends MessageLite>> messageTypes() {
            Set<Class<? extends MessageLite>> types = new HashSet<>();
            for (Class<?> type : handlers.keySet()) {
                types.add((Class<? extends MessageLite>) type);
            }
            return types;
        }

        /** @return whether {@code onOtherMessage} is set */
        public boolean hasOtherMessageHandler() {
            return onOtherMessage != null;
        }

        /** @return callbacks with the current handlers; later changes to this builder don't affect them */
        public Callbacks<E> build() {
            return new Callbacks<>(this);
        }
    }
}
