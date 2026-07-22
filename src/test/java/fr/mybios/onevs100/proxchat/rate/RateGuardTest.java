package fr.mybios.onevs100.proxchat.rate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Q10 rate guard: 750 ms min interval per player, silent drop, monotonic clock. */
class RateGuardTest {

    private static final long INTERVAL_MS = 750;

    private final AtomicLong clock = new AtomicLong(); // fake monotonic nanos
    private final AtomicLong intervalMs = new AtomicLong(INTERVAL_MS); // supplier-fed (live reload)
    private final RateGuard guard = new RateGuard(intervalMs::get, clock::get);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private void advanceMillis(long ms) {
        clock.addAndGet(ms * 1_000_000L);
    }

    @Test
    void firstMessageAlwaysPasses() {
        assertTrue(guard.tryAcquire(alice));
    }

    @Test
    void withinIntervalIsDropped() {
        assertTrue(guard.tryAcquire(alice));
        advanceMillis(749);
        assertFalse(guard.tryAcquire(alice));
    }

    @Test
    void atIntervalPassesAgain() {
        assertTrue(guard.tryAcquire(alice));
        advanceMillis(750);
        assertTrue(guard.tryAcquire(alice));
    }

    @Test
    void deniedAttemptsDoNotExtendTheWindow() {
        assertTrue(guard.tryAcquire(alice));
        advanceMillis(700);
        assertFalse(guard.tryAcquire(alice)); // spam mid-window...
        advanceMillis(50);
        assertTrue(guard.tryAcquire(alice)); // ...must not push the reopen time back
    }

    @Test
    void playersAreIndependent() {
        assertTrue(guard.tryAcquire(alice));
        assertTrue(guard.tryAcquire(bob));
        advanceMillis(10);
        assertFalse(guard.tryAcquire(alice));
        assertFalse(guard.tryAcquire(bob));
    }

    @Test
    void forgetResetsThePlayer() {
        assertTrue(guard.tryAcquire(alice));
        guard.forget(alice); // quit cleanup: a rejoin starts fresh
        assertTrue(guard.tryAcquire(alice));
    }

    @Test
    void zeroIntervalDisablesTheGuard() {
        RateGuard off = new RateGuard(() -> 0, clock::get);
        assertTrue(off.tryAcquire(alice));
        assertTrue(off.tryAcquire(alice));
    }

    @Test
    void intervalChangeAppliesLive() {
        // /proxchat reload retunes the guard without rebuilding it: the interval is
        // re-read per attempt, so an already-claimed window shrinks or grows with the config.
        assertTrue(guard.tryAcquire(alice));
        advanceMillis(500);
        assertFalse(guard.tryAcquire(alice)); // still inside 750
        intervalMs.set(400); // reload lowers the interval; 500 ms already elapsed
        assertTrue(guard.tryAcquire(alice));
        intervalMs.set(750); // reload raises it back; the new claim is 750 ms wide again
        advanceMillis(500);
        assertFalse(guard.tryAcquire(alice));
    }
}
