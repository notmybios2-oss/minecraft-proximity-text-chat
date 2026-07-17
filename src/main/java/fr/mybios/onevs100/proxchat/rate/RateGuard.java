package fr.mybios.onevs100.proxchat.rate;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-player minimum interval between accepted messages (pc-002 Q10: a bubble costs an entity
 * spawn plus a per-viewer showEntity fan-out — vanilla spam protection doesn't price that).
 * Excess messages are dropped silently.
 *
 * CAS-claimed so concurrent chat events for one player (async chat threads) can never
 * double-claim a slot. The clock is injectable for tests; production uses System.nanoTime
 * (monotonic — wall-clock jumps must not open or close the gate).
 */
public final class RateGuard {

    /** Sentinel for "never spoke" — kept out of the elapsed-time math to avoid underflow. */
    private static final long NEVER = Long.MIN_VALUE;

    private final LongSupplier minIntervalMillis;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<UUID, AtomicLong> lastAccepted = new ConcurrentHashMap<>();

    /** The interval is a SUPPLIER so a config reload retunes the guard live (slice 3). */
    public RateGuard(LongSupplier minIntervalMillis, LongSupplier nanoClock) {
        this.minIntervalMillis = minIntervalMillis;
        this.nanoClock = nanoClock;
    }

    /** True exactly once per interval per player; claiming and checking are one atomic step. */
    public boolean tryAcquire(UUID playerId) {
        long minIntervalNanos = minIntervalMillis.getAsLong() * 1_000_000L;
        if (minIntervalNanos <= 0) {
            return true;
        }
        long now = nanoClock.getAsLong();
        AtomicLong last = lastAccepted.computeIfAbsent(playerId, id -> new AtomicLong(NEVER));
        while (true) {
            long prev = last.get();
            if (prev != NEVER && now - prev < minIntervalNanos) {
                return false;
            }
            if (last.compareAndSet(prev, now)) {
                return true;
            }
        }
    }

    /** Quit cleanup — the map must not grow with player churn across a whole event. */
    public void forget(UUID playerId) {
        lastAccepted.remove(playerId);
    }
}
