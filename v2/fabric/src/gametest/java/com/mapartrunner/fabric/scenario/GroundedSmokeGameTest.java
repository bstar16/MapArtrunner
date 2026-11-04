package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

/** One owned world for the failed-first/reset/repeat scenario. */
public final class GroundedSmokeGameTest implements FabricClientGameTest {
    private static final List<String> PROVIDERS=List.of("environment-probe","single-lane","schematic-import","grounded-two-lane");
    private static final Selection REQUESTED=selection(System.getProperty("mapart.test.scenario"),System.getProperty("mapart.test.repeat"));

    static int requestedRepeat() { return REQUESTED.repeat(); }

    static boolean selected(String provider) {
        verifySelectionGuards();
        boolean selected=REQUESTED.includes(provider);
        System.out.println("MapArtRunner test selection: scenario="+REQUESTED.scenario()+"; repeat="+REQUESTED.repeat()
                +"; planned="+PROVIDERS.stream().filter(REQUESTED::includes).toList()
                +"; provider="+provider+"; execution="+(selected?"STARTED":"NOT_EXECUTED"));
        return selected;
    }

    private record Selection(String scenario,int repeat) {
        boolean includes(String provider) {
            if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Unknown test provider: "+provider);
            return scenario.equals("all") || provider.equals("grounded-two-lane");
        }
    }

    private static Selection selection(String scenario,String repeat) {
        if (scenario==null) scenario="all";
        if (!scenario.equals("all") && !scenario.equals("grounded-two-lane"))
            throw new IllegalArgumentException("Invalid mapart.test.scenario: "+scenario+"; expected all|grounded-two-lane");
        if (repeat==null) repeat="2";
        int count=switch (repeat) {
            case "1" -> 1;
            case "2" -> 2;
            default -> throw new IllegalArgumentException("Invalid mapart.test.repeat: "+repeat+"; expected 1|2");
        };
        return new Selection(scenario,count);
    }

    private static void verifySelectionGuards() {
        var defaults=selection(null,null);
        require(defaults.scenario().equals("all") && defaults.repeat()==2,"Selection defaults changed");
        for (var provider:PROVIDERS) require(defaults.includes(provider),"Default omitted "+provider);
        for (var scenario:List.of("all","grounded-two-lane")) for (var repeat:List.of("1","2")) {
            var request=selection(scenario,repeat);
            require(request.repeat()==(repeat.equals("1")?1:2),"Wrong requested repeat");
            for (var provider:PROVIDERS)
                require(request.includes(provider)==(scenario.equals("all") || provider.equals("grounded-two-lane")),
                        "Wrong provider selection: "+scenario+"/"+repeat+"/"+provider);
        }
        require(selection("grounded-two-lane",null).repeat()==2 && selection(null,"1").includes("single-lane"),
                "Independent selection defaults changed");
        for (var scenario:List.of("","unknown","single-lane")) rejectsSelection(scenario,"2");
        for (var repeat:List.of("","abc","0","-1","3","2147483648","01","+1","1.0")) rejectsSelection("all",repeat);
        try { defaults.includes("unknown"); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unknown provider accepted");
    }

    private static void rejectsSelection(String scenario,String repeat) {
        try { selection(scenario,repeat); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid selection accepted: "+scenario+"/"+repeat);
    }

    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }

    @Override public void runTest(ClientGameTestContext context) {
        if (!selected("grounded-two-lane")) return;
        ScenarioDefinition.verifyDefinition();
        var definition = ScenarioDefinition.load();
        if (!definition.definitionHash().equals("81fad1bb9c4e356c941455f0cd50e7fd6a82dcc4f56e22d0740a1ec670337127"))
            throw new AssertionError("Unexpected two-lane canonical definition: " + definition.definitionHash());
        var wrong = System.getenv("MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK");
        if (wrong != null && !wrong.isEmpty() && !wrong.equals("1"))
            throw new AssertionError("Invalid MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK");
        var watch = context.computeOnClient(ScenarioObserver::install);
        var runner = new ScenarioRunner(context, watch,REQUESTED.repeat());
        Throwable failure=null;
        try {
            runner.reset(definition);
            runner.failedFirst(definition);
            for (int run=0;run<REQUESTED.repeat();run++) {
                runner.reset(definition);
                runner.assertResult(runner.run(definition));
            }
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
