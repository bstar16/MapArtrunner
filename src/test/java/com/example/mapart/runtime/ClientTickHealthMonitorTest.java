package com.example.mapart.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientTickHealthMonitorTest {

    @Test
    void firstTickReturnZeroAndNoStall() {
        long[] time = {1_000_000_000L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        long delta = monitor.tick();

        assertEquals(0, delta);
        assertFalse(monitor.isStallActive());
        assertEquals(0, monitor.lastDeltaMs());
    }

    @Test
    void normalTickDeltaBelowThresholdNoStall() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick(); // first tick
        time[0] = 50_000_000L; // 50 ms later
        long delta = monitor.tick();

        assertEquals(50, delta);
        assertFalse(monitor.isStallActive());
        assertEquals(50, monitor.lastDeltaMs());
    }

    @Test
    void deltaAtExactThresholdTriggersStall() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick();
        time[0] = 2_000_000_000L; // exactly 2000 ms
        monitor.tick();

        assertTrue(monitor.isStallActive());
        assertEquals(2_000, monitor.lastDeltaMs());
    }

    @Test
    void deltaAboveThresholdTriggersStall() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick();
        time[0] = 5_000_000_000L; // 5 seconds
        monitor.tick();

        assertTrue(monitor.isStallActive());
        assertEquals(5_000, monitor.lastDeltaMs());
    }

    @Test
    void stallClearsAfterNormalTick() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick();
        time[0] = 5_000_000_000L; // stall
        monitor.tick();
        assertTrue(monitor.isStallActive());

        time[0] = 5_050_000_000L; // 50 ms later — normal tick
        monitor.tick();

        assertFalse(monitor.isStallActive());
        assertEquals(50, monitor.lastDeltaMs());
    }

    @Test
    void resetClearsAllState() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick();
        time[0] = 5_000_000_000L;
        monitor.tick();
        assertTrue(monitor.isStallActive());

        monitor.reset();

        assertFalse(monitor.isStallActive());
        assertEquals(0, monitor.lastDeltaMs());
    }

    @Test
    void resetMakesNextTickFirstTick() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);

        monitor.tick();
        time[0] = 5_000_000_000L;
        monitor.tick();

        monitor.reset();
        time[0] = 10_000_000_000L; // 5 s after reset
        long delta = monitor.tick();

        // First tick after reset should return 0 regardless of elapsed time
        assertEquals(0, delta);
        assertFalse(monitor.isStallActive());
    }

    @Test
    void customThresholdRespected() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 500);

        monitor.tick();
        time[0] = 600_000_000L; // 600 ms — above 500 ms threshold
        monitor.tick();

        assertTrue(monitor.isStallActive());
    }

    @Test
    void customThresholdNotTriggeredBelowIt() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 500);

        monitor.tick();
        time[0] = 400_000_000L; // 400 ms — below 500 ms threshold
        monitor.tick();

        assertFalse(monitor.isStallActive());
    }

    @Test
    void defaultConstructorUsesSystemNanosAndDoesNotStall() {
        // Smoke test: two consecutive ticks on the real clock should not stall
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor();
        monitor.tick();
        monitor.tick();
        assertFalse(monitor.isStallActive());
    }
}
