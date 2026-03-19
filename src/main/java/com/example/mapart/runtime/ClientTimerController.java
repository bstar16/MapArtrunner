package com.example.mapart.runtime;

public final class ClientTimerController {
    public static final double OFF = 1.0;
    public static final double MIN_MULTIPLIER = 0.1;

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
}
