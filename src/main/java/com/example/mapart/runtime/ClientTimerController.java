package com.example.mapart.runtime;

public final class ClientTimerController {
    public static final double OFF = 1.0;
    public static final double MIN_MULTIPLIER = 0.1;

    /** Identifies the implementation backing this controller. */
    public static final String MODE = "REAL_CLIENT_TIMER";

    private static double multiplier = OFF;

    private ClientTimerController() {
    }

    public static double getMultiplier() {
        return multiplier;
    }

    public static void setMultiplier(double value) {
        if (value < MIN_MULTIPLIER) {
            throw new IllegalArgumentException("clientTimerSpeed must be >= " + MIN_MULTIPLIER + ".");
        }

        multiplier = value;
    }

    /**
     * Syncs the timer multiplier from the persisted settings.
     * Returns true if the multiplier changed (use to gate logging).
     */
    public static boolean applySettings(boolean enabled, int speed) {
        double target = enabled ? (double) speed : OFF;
        if (Double.compare(target, multiplier) == 0) return false;
        multiplier = target;
        return true;
    }

    /** Resets to the default multiplier. Intended for tests. */
    public static void reset() {
        multiplier = OFF;
    }
}
