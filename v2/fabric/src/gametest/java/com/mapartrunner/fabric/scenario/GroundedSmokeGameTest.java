package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** One owned world for the failed-first/reset/repeat scenario. */
public final class GroundedSmokeGameTest implements FabricClientGameTest {
    private static final List<String> PROVIDERS=List.of("environment-probe","single-lane","schematic-import","grounded-two-lane","grounded-traversal");
    private static final Selection REQUESTED=selection(System.getProperty("mapart.test.scenario"),System.getProperty("mapart.test.repeat"));
    private static boolean startPublished;

    static int requestedRepeat() { return REQUESTED.repeat(); }

    static boolean selected(String provider) {
        verifySelectionGuards();
        boolean selected=REQUESTED.includes(provider);
        if (selected) publishTestStart(provider);
        System.out.println("MapArtRunner test selection: scenario="+REQUESTED.scenario()+"; repeat="+REQUESTED.repeat()
                +"; planned="+PROVIDERS.stream().filter(REQUESTED::includes).toList()
                +"; provider="+provider+"; execution="+(selected?"STARTED":"NOT_EXECUTED"));
        return selected;
    }

    private static synchronized void publishTestStart(String provider) {
        String id=System.getenv("MAPART_TEST_LAUNCH_ID"), directory=System.getenv("MAPART_TEST_LAUNCH_DIR");
        if (id==null && directory==null) return;
        require(id!=null && directory!=null,"Incomplete owned test launch environment");
        require(UUID.fromString(id).toString().equals(id),"Noncanonical test launch UUID");
        if (startPublished) return;
        try {
            var source=Path.of(GroundedSmokeGameTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(Files.isDirectory(source) && source.endsWith(Path.of("build","classes","java","gametest"))
                    && source.equals(source.toRealPath()),"Unsupported/redirected loaded test module");
            var module=source.getParent().getParent().getParent().getParent();
            require(module.endsWith(Path.of("v2","fabric")) && Files.isRegularFile(module.resolve("build.gradle")),"Wrong test module root");
            var root=module.resolve("build/scenario-results");
            require(root.equals(root.toRealPath()),"Redirected scenario-results root");
            var launch=Path.of(directory);
            require(launch.isAbsolute() && launch.equals(launch.normalize()) && launch.getParent().equals(root)
                    && launch.getFileName().toString().equals("launch-"+id) && Files.isDirectory(launch,LinkOption.NOFOLLOW_LINKS)
                    && launch.equals(launch.toRealPath()),"Wrong/redirected owned launch directory");
            require(System.getProperty("os.name").startsWith("Windows") && Files.getFileStore(launch).type().equals("NTFS"),
                    "Owned test receipts require the configured Windows/NTFS backend");
            var process=ProcessHandle.current();
            var receipt=new JsonObject();
            receipt.addProperty("schema","mapartrunner-test-start-v1");
            receipt.addProperty("launchId",id);
            receipt.addProperty("pid",process.pid());
            receipt.addProperty("startedUtc",process.info().startInstant().orElseThrow(() -> new IllegalStateException("Native game creation time unavailable")).toString());
            receipt.addProperty("scenario",REQUESTED.scenario());
            receipt.addProperty("repeat",REQUESTED.repeat());
            receipt.addProperty("provider",provider);
            receipt.addProperty("event","SELECTED_PROVIDER_RUNTEST_ENTERED");
            byte[] bytes=receipt.toString().getBytes(StandardCharsets.UTF_8);
            require(bytes.length<=4096,"Test-start receipt exceeds4096bytes");
            var temporary=launch.resolve("test-start-"+UUID.randomUUID()+".tmp");
            try (var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            require(launch.equals(launch.toRealPath()),"Launch directory redirected before publication");
            Files.createLink(launch.resolve("test-start.json"),temporary);
            Files.delete(temporary);
            // Only a completed first publication sets this guard; subsequent selected providers never replace it.
            startPublished=true;
        } catch (IOException error) { throw new UncheckedIOException("Cannot publish selected-provider startup",error); }
        catch (java.net.URISyntaxException error) { throw new IllegalStateException("Cannot identify loaded test module",error); }
    }

    private record Selection(String scenario,int repeat) {
        boolean includes(String provider) {
            if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Unknown test provider: "+provider);
            return scenario.equals("all") || provider.equals(scenario);
        }
    }

    private static Selection selection(String scenario,String repeat) {
        if (scenario==null) scenario="all";
        if (!scenario.equals("all") && !scenario.equals("grounded-two-lane") && !scenario.equals("grounded-traversal"))
            throw new IllegalArgumentException("Invalid mapart.test.scenario: "+scenario+"; expected all|grounded-two-lane|grounded-traversal");
        if (repeat==null) repeat="2";
        int count=switch (repeat) {
            case "1" -> 1;
            case "2" -> 2;
            default -> throw new IllegalArgumentException("Invalid mapart.test.repeat: "+repeat+"; expected 1|2");
        };
        return new Selection(scenario,count);
    }

    private static void verifySelectionGuards() {
        require(PROVIDERS.equals(List.of("environment-probe","single-lane","schematic-import","grounded-two-lane","grounded-traversal")),
                "Registered provider set changed");
        var defaults=selection(null,null);
        require(defaults.scenario().equals("all") && defaults.repeat()==2,"Selection defaults changed");
        for (var provider:PROVIDERS) require(defaults.includes(provider),"Default omitted "+provider);
        for (var scenario:List.of("all","grounded-two-lane","grounded-traversal")) for (var repeat:List.of("1","2")) {
            var request=selection(scenario,repeat);
            require(request.repeat()==(repeat.equals("1")?1:2),"Wrong requested repeat");
            for (var provider:PROVIDERS)
                require(request.includes(provider)==(scenario.equals("all") || provider.equals(scenario)),
                        "Wrong provider selection: "+scenario+"/"+repeat+"/"+provider);
        }
        require(selection("grounded-two-lane",null).repeat()==2 && selection("grounded-traversal",null).repeat()==2
                        && selection(null,"1").includes("single-lane"),
                "Independent selection defaults changed");
        for (var scenario:List.of("","unknown","single-lane","GROUNDED-TRAVERSAL","grounded-traversal ")) rejectsSelection(scenario,"2");
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
