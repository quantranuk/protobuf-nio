package io.github.quantranuk.protobuf.nio.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * One shared daemon thread that enforces the deadlines a blocked thread can't enforce itself: blocking socket writes have
 * no timeout in the JDK, and a graceful close must not wait for the peer forever. It scans every open connection
 * periodically, at a tenth of the shortest timeout registered (between 10 ms and 1 s).
 * <p>
 * It's a platform thread so that it keeps running even when every carrier thread is busy with user code.
 */
final class Watchdog {

    private static final Logger LOG = LoggerFactory.getLogger(Watchdog.class);
    private static final long MIN_PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long MAX_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

    static final Watchdog INSTANCE = new Watchdog();

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile long periodNanos = MAX_PERIOD_NANOS;
    private volatile Thread thread;

    private Watchdog() {
    }

    /** @param shortestTimeoutNanos the shortest deadline the connection needs enforced; 0 if none */
    void register(Connection connection, long shortestTimeoutNanos) {
        connections.add(connection);
        if (shortestTimeoutNanos > 0) {
            long period = Math.clamp(shortestTimeoutNanos / 10, MIN_PERIOD_NANOS, MAX_PERIOD_NANOS);
            if (period < periodNanos) {
                periodNanos = period;   // racy minimum: at worst a slightly longer period until the next registration
                Thread t = thread;
                if (t != null) {
                    LockSupport.unpark(t);
                }
            }
        }
        if (thread == null) {
            start();
        }
    }

    void unregister(Connection connection) {
        connections.remove(connection);
    }

    private synchronized void start() {
        if (thread == null) {
            thread = Thread.ofPlatform().daemon().name("protobuf-nio-watchdog").start(this::run);
        }
    }

    private void run() {
        while (true) {
            LockSupport.parkNanos(this, periodNanos);
            long now = System.nanoTime();
            for (Connection connection : connections) {
                try {
                    connection.checkDeadlines(now);
                } catch (Throwable t) {
                    LOG.warn("Watchdog check failed for {}", connection, t);
                }
            }
        }
    }
}
