package com.example.mapart.baritone;

public final class BaritoneFacadeFactory {
    private static final String BARITONE_API_CLASS = "baritone.api.BaritoneAPI";

    private BaritoneFacadeFactory() {
    }

    public static BaritoneFacade create() {
        try {
            Class.forName(BARITONE_API_CLASS);
            return new RealBaritoneFacade();
        } catch (ClassNotFoundException exception) {
            return new NoOpBaritoneFacade();
        }
    }
}
