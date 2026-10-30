package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** One owned world for the failed-first/reset/repeat scenario. */
public final class GroundedSmokeGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        ScenarioDefinition.verifyDefinition();
        var definition = ScenarioDefinition.load();
        if (!definition.definitionHash().equals("81fad1bb9c4e356c941455f0cd50e7fd6a82dcc4f56e22d0740a1ec670337127"))
            throw new AssertionError("Unexpected two-lane canonical definition: " + definition.definitionHash());
        var wrong = System.getenv("MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK");
        if (wrong != null && !wrong.isEmpty() && !wrong.equals("1"))
            throw new AssertionError("Invalid MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK");
        var watch = context.computeOnClient(ScenarioObserver::install);
        var runner = new ScenarioRunner(context, watch);
        Throwable failure=null;
        try {
            runner.reset(definition);
            runner.failedFirst(definition);
            runner.reset(definition);
            runner.assertResult(runner.run(definition));
            runner.reset(definition);
            runner.assertResult(runner.run(definition));
        } catch (RuntimeException | Error error) {
            failure=error;
            runner.captureFailure(error);
        }
        try { runner.close(); }
        catch (RuntimeException | Error error) { if (failure==null) failure=error; else failure.addSuppressed(error); }
        try { runner.invocationOutcome(failure); }
        catch (RuntimeException | Error error) { if (failure==null) failure=error; else failure.addSuppressed(error); }
        if (failure instanceof Error error) throw error;
        if (failure!=null) throw (RuntimeException)failure;
    }
}
