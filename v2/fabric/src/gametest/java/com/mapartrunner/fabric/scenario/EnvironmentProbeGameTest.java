package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Only a test-adapter feasibility probe; this does not exercise a builder. */
public final class EnvironmentProbeGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        new ScenarioEnvironment(context).run();
    }
}
