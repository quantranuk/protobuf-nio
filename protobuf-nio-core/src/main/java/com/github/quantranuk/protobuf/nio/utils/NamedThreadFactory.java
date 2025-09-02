package com.github.quantranuk.protobuf.nio.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple non-deamon thread factory that print out uncaught exceptions
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final int STACK_SIZE = 0;
    private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
    private static final Logger LOGGER = LoggerFactory.getLogger("UncaughtExceptionHandler");
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = (t, e) -> LOGGER.error("Exception thrown from " + t.getName(), e);

    private final ThreadGroup group;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;

    public NamedThreadFactory(String prefix) {
        group = Thread.currentThread().getThreadGroup();
        namePrefix = prefix + "-pool-" + POOL_NUMBER.getAndIncrement() + "-thread-";
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(group, r,namePrefix + threadNumber.getAndIncrement(), STACK_SIZE);
        if (t.isDaemon())
            t.setDaemon(false);
        if (t.getPriority() != Thread.NORM_PRIORITY)
            t.setPriority(Thread.NORM_PRIORITY);
        t.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);
        return t;
    }

}
