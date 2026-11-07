package com.github.quantranuk.protobuf.nio.utils;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple non-daemon thread factory that print out uncaught exceptions
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
    private static final Logger LOGGER = LoggerFactory.getLogger("UncaughtExceptionHandler");
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = (t, e) -> LOGGER.error("Exception thrown from {}", t.getName(), e);

    private final ThreadFactory delegate;

    public NamedThreadFactory(String prefix) {
        delegate = Thread.ofPlatform()
                .group(Thread.currentThread().getThreadGroup())
                .name(prefix + "-pool-" + POOL_NUMBER.getAndIncrement() + "-thread-", 1)
                .daemon(false)
                .priority(Thread.NORM_PRIORITY)
                .uncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER)
                .factory();
    }

    @Override
    public Thread newThread(@Nonnull Runnable r) {
        return delegate.newThread(r);
    }

}
