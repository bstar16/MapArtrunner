package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Selected traversal variants share one managed runner across each variant's requested repeats. */
public final class TraversalGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (!GroundedSmokeGameTest.selected("grounded-traversal")) return;
        var watch=context.computeOnClient(ScenarioObserver::install);
        int repeats=GroundedSmokeGameTest.requestedRepeat();
        for(var fixture:ScenarioDefinition.Fixture.values()) {
            if(fixture==ScenarioDefinition.Fixture.TWO_LANE) continue;
            var definition=ScenarioDefinition.load(fixture);
            var runner=new ScenarioRunner(context,watch,repeats,fixture);
            Throwable failure=null;
            try {
                for(int repeat=0;repeat<repeats;repeat++) {
                    runner.reset(definition);
                    runner.assertResult(runner.run(definition));
                }
            } catch(RuntimeException | Error error) {
                failure=error; runner.captureFailure(error);
            }
            try { runner.close(); }
            catch(RuntimeException | Error error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
            try { runner.invocationOutcome(failure); }
            catch(RuntimeException | Error error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
            if(failure instanceof Error error) throw error;
            if(failure!=null) throw (RuntimeException)failure;
        }
    }
}
