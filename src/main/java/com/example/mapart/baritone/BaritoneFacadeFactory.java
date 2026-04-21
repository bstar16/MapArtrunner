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
            return new NoOpBaritoneFacade("Baritone was not found. Install baritone-api-fabric to enable assisted movement.");
        } catch (LinkageError error) {
            return new NoOpBaritoneFacade("Baritone API mismatch detected. Ensure your baritone-api-fabric jar matches Minecraft 1.21.11.");
        }
    }
}
