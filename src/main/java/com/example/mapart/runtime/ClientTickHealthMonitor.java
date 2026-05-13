package com.example.mapart.runtime;

/**
 * Tracks wall-clock time between client ticks to detect stalls.
 *
 * A "stall" is a tick-to-tick wall-clock gap that exceeds the threshold (default 2 s).
 * This can happen when the Minecraft client is minimized, the JVM is paused for GC,
 * or the host OS suspends the process briefly. During a stall tick, sensitive timeout
 * and retry counters should not advance to avoid false failures.
 *
 * Supported modes:
 *   - Unfocused but still ticking (multiplayer/dedicated server): works normally; no stall.
 *   - Minimized / suspended: ticks stop entirely — no counters advance.
 *   - Throttled / very slow ticking: large inter-tick delta triggers stall suppression.
 *
 * Use {@link #tick()} on every client tick, then check {@link #isStallActive()} to decide
 * whether to skip sensitive counter increments. Call {@link #reset()} when the runner starts
 * or stops so stale state from a previous run does not bleed into a new one.
 */
public final class ClientTickHealthMonitor {
    public static final long DEFAULT_STALL_THRESHOLD_MS = 2_000L;
    private static final long NS_PER_MS = 1_000_000L;

    @FunctionalInterface
    public interface Clock {
        long nanoTime();
    }

    private final Clock clock;
    private final long stallThresholdMs;
    private long lastTickNanos = Long.MIN_VALUE; // sentinel: no tick yet
    private long lastDeltaMs = 0;
    private boolean stallActive = false;

    public ClientTickHealthMonitor() {
        this(System::nanoTime, DEFAULT_STALL_THRESHOLD_MS);
    }

    public ClientTickHealthMonitor(Clock clock, long stallThresholdMs) {
        this.clock = clock;
        this.stallThresholdMs = stallThresholdMs;
    }

    /**
     * Call once per client tick. Records the wall-clock time and computes the delta
     * from the previous tick. The first call always returns 0 and does not trigger a stall.
     *
     * @return wall-clock milliseconds elapsed since the last tick (0 on first call)
     */
    public long tick() {
        long now = clock.nanoTime();
        if (lastTickNanos == Long.MIN_VALUE) {
            lastTickNanos = now;
            lastDeltaMs = 0;
            stallActive = false;
            return 0;
        }
        long deltaNs = now - lastTickNanos;
        lastTickNanos = now;
        lastDeltaMs = deltaNs / NS_PER_MS;
        stallActive = lastDeltaMs >= stallThresholdMs;
        return lastDeltaMs;
    }

    /** Wall-clock ms between the last two tick() calls. 0 before the second tick. */
    public long lastDeltaMs() {
        return lastDeltaMs;
    }

    /**
     * True when the most recent tick-to-tick delta exceeded the stall threshold.
     * Sensitive timeout/retry counters should not advance when this returns true.
     */
    public boolean isStallActive() {
        return stallActive;
    }

    /** Reset to initial state. Call when the runner starts or stops. */
    public void reset() {
        lastTickNanos = Long.MIN_VALUE;
        lastDeltaMs = 0;
        stallActive = false;
    }
}
