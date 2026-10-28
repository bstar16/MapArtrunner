package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;


/** Fresh managed worlds; observers never drive production or call gametest contexts. */
public final class GroundedSingleLaneGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try { SingleLaneFixture.verifyCoordinateLookup(); }
        catch(AssertionError error) {
            try { context.takeScreenshot(TestScreenshotOptions.of("coordinate-lookup-failure").disableCounterPrefix()); }
            catch(RuntimeException | Error capture) { error.addSuppressed(capture); }
            throw error;
        }
        var watch = context.computeOnClient(ScenarioObserver::install);
        boolean autoJump = context.computeOnClient(client -> client.options.getAutoJump().getValue());
        context.runOnClient(client -> client.options.getAutoJump().setValue(false));
        try {
            SingleLaneScenario disconnected = null;
            for (var kind : SingleLaneScenario.Kind.values()) {
                var scenario = new SingleLaneScenario(context, watch, kind);
                scenario.run(disconnected);
                disconnected = kind == SingleLaneScenario.Kind.DISCONNECT ? scenario : null;
            }
        } finally {
            context.runOnClient(client -> {
                watch.clear();
                client.options.getAutoJump().setValue(autoJump);
            });
        }
    }

}
