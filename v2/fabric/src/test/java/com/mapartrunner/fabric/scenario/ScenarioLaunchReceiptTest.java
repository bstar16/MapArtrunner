package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioLaunchReceiptTest {
    private final List<Path> launches = new ArrayList<>();
    private final List<Path> operations = new ArrayList<>();

    @AfterEach void removeOnlyFreshFixtureFiles() throws Exception {
        for (var root : operations) {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        for (var launch : launches) {
            try (var files = Files.list(launch)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(launch);
        }
    }

    @Test void realHostPublishesExactPhasesAndTypedFinalOutcomeBeforeNextWork() throws Exception {
        var launch=launch(); var publisher=publisher(launch); var now=new AtomicLong(1);
        var utc=Instant.now(); var op=operation(1, Command.RUN);
        var host=new InspectScenarioHost(now::get, () -> false, () -> false, Runnable::run, Runnable::run,
                outcome -> { }, publisher, () -> utc.plusNanos(now.get()-1));
        var work=host.beginWork(op);
        var begin=phase(launch,1,"begin");
        assertEquals(Set.of("schema","launchId","pid","startedUtc","mode","sessionId","ordinal","operationId",
                "command","phase","phaseStartedUtc","phaseDeadlineUtc","evidenceDir","verdict","cleanupStatus"),begin.keySet());
        assertEquals("WORK",begin.get("phase").getAsString());
        assertTrue(begin.get("verdict").isJsonNull()); assertTrue(begin.get("cleanupStatus").isJsonNull());
        assertEquals(utc.plusMillis(600000),Instant.parse(begin.get("phaseDeadlineUtc").getAsString()));
        now.set(work.deadlineNanos());
        var cleanup=host.beginCleanup(work,new ScopeTimeoutException());
        assertThrows(IllegalStateException.class,() -> host.beginWork(operation(2,Command.RUN)));
        var middle=phase(launch,1,"cleanup");
        assertEquals("TIMEOUT",middle.get("verdict").getAsString());
        assertEquals(utc.plusMillis(610000),Instant.parse(middle.get("phaseDeadlineUtc").getAsString()));
        host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.TIMEOUT,CleanupStatus.SETTLED));
        var end=phase(launch,1,"end");
        assertEquals("SETTLED",end.get("phase").getAsString()); assertTrue(end.get("phaseDeadlineUtc").isJsonNull());
        assertEquals("TIMEOUT",end.get("verdict").getAsString()); assertEquals("SETTLED",end.get("cleanupStatus").getAsString());
        assertThrows(IllegalStateException.class,() -> host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.SUCCESS,CleanupStatus.SETTLED)));
    }

    @Test void closePhasesShareOneTenSecondCeiling() throws Exception {
        var launch=launch(); var now=new AtomicLong(1); var utc=Instant.now(); var op=operation(1,Command.CLOSE);
        var host=new InspectScenarioHost(now::get,() -> false,() -> false,Runnable::run,Runnable::run,
                outcome -> { },publisher(launch),() -> utc.plusNanos(now.get()-1));
        var work=host.beginWork(op); now.addAndGet(3_000_000_000L);
        var cleanup=host.beginCleanup(work,null);
        assertEquals(phase(launch,1,"begin").get("phaseDeadlineUtc"),phase(launch,1,"cleanup").get("phaseDeadlineUtc"));
        assertEquals(utc.plusMillis(10000),Instant.parse(phase(launch,1,"cleanup").get("phaseDeadlineUtc").getAsString()));
        host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.FAILURE,CleanupStatus.UNRESOLVED));
        assertEquals("UNRESOLVED",phase(launch,1,"end").get("cleanupStatus").getAsString());
    }

    @Test void beginFailureBeforeOrAfterFinalLinkCancelsWithoutGrantOrSuccessorOrRepair() throws Exception {
        for(boolean afterLink:List.of(false,true)) {
            var launch=launch(); var op=operation(1,Command.RUN); var error=new IllegalStateException("after final link");
            var publisher=publisher(launch,afterLink?path -> { throw error; }:null);
            var destination=launch.resolve("inspect-op-1.begin.json");
            if(!afterLink) Files.writeString(destination,"old evidence");
            var host=new InspectScenarioHost(System::nanoTime,() -> false,() -> false,Runnable::run,Runnable::run,
                    outcome -> fail("No END"),publisher,Instant::now);
            var failure=assertThrows(RuntimeException.class,() -> host.beginWork(op));
            if(afterLink) assertSame(error,failure);
            var bytes=Files.readAllBytes(destination);
            assertThrows(IllegalStateException.class,() -> host.beginWork(operation(2,Command.RUN)));
            assertFalse(Files.exists(launch.resolve("inspect-op-2.begin.json")));
            var shutdown=host.beginShutdownCleanup(op,failure);
            host.settleCleanup(shutdown,new FinalOutcome(op,Verdict.FAILURE,CleanupStatus.SETTLED));
            assertArrayEquals(bytes,Files.readAllBytes(destination));
            assertFalse(Files.exists(launch.resolve("inspect-op-1.cleanup.json")));
            assertFalse(Files.exists(launch.resolve("inspect-op-1.end.json")));
        }
    }

    @Test void operationPublisherRejectsWrongAncestryAndDoesNotReplaceExistingPhase() throws Exception {
        var launch=launch(); var op=operation(1,Command.RUN); var publisher=publisher(launch); var start=Instant.now();
        var bad=new Operation(op.sessionId(),1,op.operationId(),Command.RUN,launch);
        assertThrows(AssertionError.class,() -> publisher.operation(bad,"WORK",start,start.plusMillis(600000),null,null));
        assertThrows(AssertionError.class,() -> publisher.operation(op,"WORK",start,start.plusMillis(600001),null,null));
        assertThrows(AssertionError.class,() -> publisher.operation(op,"CLEANUP",start,start.plusMillis(10001),Verdict.FAILURE,null));
        assertThrows(AssertionError.class,() -> publisher.operation(op,"SETTLED",start,start,Verdict.SUCCESS,CleanupStatus.SETTLED));
        publisher.operation(op,"WORK",start,start.plusMillis(600000),null,null);
        var bytes=Files.readAllBytes(launch.resolve("inspect-op-1.begin.json"));
        assertThrows(UncheckedIOException.class,() -> publisher.operation(op,"WORK",start,start.plusMillis(600000),null,null));
        assertArrayEquals(bytes,Files.readAllBytes(launch.resolve("inspect-op-1.begin.json")));
    }

    @Test void slowBeginPublicationDoesNotRenewTheJavaAllowance() throws Exception {
        var launch=launch(); var now=new AtomicLong(1); var utc=Instant.now(); var op=operation(1,Command.RUN);
        var publisher=publisher(launch,path -> now.addAndGet(5_000_000_000L));
        var host=new InspectScenarioHost(now::get,() -> false,() -> false,Runnable::run,Runnable::run,
                outcome -> { },publisher,() -> utc.plusNanos(now.get()-1));
        var work=host.beginWork(op);
        assertEquals(600_000_000_001L,work.deadlineNanos());
        assertEquals(utc.plusMillis(600000),Instant.parse(phase(launch,1,"begin").get("phaseDeadlineUtc").getAsString()));
        now.set(work.deadlineNanos());
        assertThrows(ScopeTimeoutException.class,() -> host.inScope(work,() -> fail("Expired WORK effect")));
    }

    @Test void actualStartupLineageNeverEmitsOrdinaryPhaseFiles() throws Exception {
        var launch=launch(); var now=new AtomicLong(1);
        var host=new InspectScenarioHost(now::get,() -> false,() -> false,Runnable::run,Runnable::run,
                outcome -> fail("Startup is not an ordinary END"),publisher(launch),Instant::now);
        var startup=operation(0,Command.STARTUP); var scope=host.beginStartup(startup);
        var cleanup=host.beginCleanup(scope,new IllegalStateException("startup failed"));
        host.settleCleanup(cleanup,new FinalOutcome(startup,Verdict.FAILURE,CleanupStatus.SETTLED));
        try(var files=Files.list(launch)) { assertEquals(0,files.count()); }
    }

    @Test void productionConstructorEnforcesTheActualBackendWithoutSimulatingWindows() throws Exception {
        var launch=launch(); var actual=new ScenarioLaunchReceipt(id(launch),launch.toString());
        if (System.getProperty("os.name").startsWith("Windows") && Files.getFileStore(launch).type().equals("NTFS")) {
            actual.automatic("all",2,"single-lane"); assertOnlyFinal(launch);
        } else {
            assertThrows(AssertionError.class,() -> actual.automatic("all",2,"single-lane"));
            assertFalse(Files.exists(launch.resolve("test-start.json")));
        }
    }

    private Operation operation(int ordinal,Command command) throws Exception {
        var root=launches.getLast().getParent().resolve("two-lane-"+UUID.randomUUID());
        Files.createDirectory(root); operations.add(root);
        var session=UUID.fromString(root.getFileName().toString().substring("two-lane-".length())); var id=UUID.randomUUID();
        var directory=Files.createDirectories(root.resolve("inspect-operations/op-"+ordinal+"-"+id));
        return new Operation(session,ordinal,id,command,directory);
    }
    private static JsonObject phase(Path launch,int ordinal,String phase) throws Exception {
        return JsonParser.parseString(Files.readString(launch.resolve("inspect-op-"+ordinal+"."+phase+".json"))).getAsJsonObject();
    }

    @Test void automaticPublishesOriginalEightFieldsAndNativeIdentity() throws Exception {
        var launch = launch();
        publisher(launch).automatic("all", 2, "schematic-import");
        var receipt = read(launch);
        assertEquals(Set.of("schema", "launchId", "pid", "startedUtc", "scenario", "repeat", "provider", "event"), receipt.keySet());
        assertEquals("mapartrunner-test-start-v1", receipt.get("schema").getAsString());
        assertEquals("SELECTED_PROVIDER_RUNTEST_ENTERED", receipt.get("event").getAsString());
        assertEquals("all", receipt.get("scenario").getAsString());
        assertEquals(2, receipt.get("repeat").getAsInt());
        assertEquals("schematic-import", receipt.get("provider").getAsString());
        assertIdentity(launch, receipt);
        assertOnlyFinal(launch);
    }

    @Test void firstCompletedAutomaticPublicationKeepsFirstProviderBytes() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        publisher.automatic("all", 2, "environment-probe");
        var first = Files.readAllBytes(launch.resolve("test-start.json"));
        publisher.automatic("all", 2, "grounded-traversal");
        assertArrayEquals(first, Files.readAllBytes(launch.resolve("test-start.json")));
        assertOnlyFinal(launch);
    }

    @Test void inspectPublishesOnlyTypedReadyFieldsWithoutAutomaticSelection() throws Exception {
        var launch = launch();
        var session = UUID.randomUUID().toString();
        publisher(launch).inspect(session);
        var receipt = read(launch);
        assertEquals(Set.of("schema", "launchId", "pid", "startedUtc", "scenario", "mode", "sessionId", "event"), receipt.keySet());
        assertEquals("mapartrunner-inspect-ready-v1", receipt.get("schema").getAsString());
        assertEquals("INSPECT_WORLD_BOUND_COMMANDS_READY", receipt.get("event").getAsString());
        assertEquals("grounded-two-lane", receipt.get("scenario").getAsString());
        assertEquals("inspect", receipt.get("mode").getAsString());
        assertEquals(session, receipt.get("sessionId").getAsString());
        assertIdentity(launch, receipt);
        assertOnlyFinal(launch);
    }

    @Test void malformedInspectSessionCannotPublish() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        for (var session : List.of("", "wrong", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "1-1-1-1-1"))
            assertThrows(IllegalArgumentException.class, () -> publisher.inspect(session));
        assertFalse(Files.exists(launch.resolve("test-start.json")));
        publisher.inspect(UUID.randomUUID().toString());
        assertOnlyFinal(launch);
    }

    @Test void existingFinalIsNeverReplacedAndFailedPublisherCanRetry() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        Files.writeString(launch.resolve("test-start.json"), "preexisting");
        var foreignTemp = launch.resolve("test-start-foreign.tmp");
        Files.writeString(foreignTemp, "foreign");
        assertThrows(UncheckedIOException.class, () -> publisher.automatic("all", 2, "single-lane"));
        assertEquals("preexisting", Files.readString(launch.resolve("test-start.json")));
        try (var files = Files.list(launch)) { assertEquals(2, files.count()); }
        assertEquals("foreign", Files.readString(foreignTemp));
        Files.delete(launch.resolve("test-start.json"));
        publisher.automatic("grounded-two-lane", 1, "grounded-two-lane");
        assertEquals(1, read(launch).get("repeat").getAsInt());
    }

    @Test void absentEnvironmentKeepsAutomaticUnownedUseButPartialEnvironmentFails() {
        assertDoesNotThrow(() -> new ScenarioLaunchReceipt(null, null).automatic("all", 2, "single-lane"));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(null, null).inspect(UUID.randomUUID().toString()));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(UUID.randomUUID().toString(), null).automatic("all", 2, "single-lane"));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(null, "directory").automatic("all", 2, "single-lane"));
    }

    @Test void invalidLaunchIdentityOrPathCannotPublish() throws Exception {
        var launch = launch();
        var id = id(launch);
        assertThrows(IllegalArgumentException.class, () -> new ScenarioLaunchReceipt("wrong", launch.toString()).automatic("all", 2, "single-lane"));
        for (var bad : List.of("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "1-1-1-1-1"))
            assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(bad, launch.toString()).automatic("all", 2, "single-lane"));
        for (var path : List.of(launch.resolve(".").toString(), launch.getFileName().toString(), launch.getParent().toString()))
            assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(id, path).automatic("all", 2, "single-lane"));
        assertFalse(Files.exists(launch.resolve("test-start.json")));
    }

    private Path launch() throws Exception {
        var source = Path.of(ScenarioLaunchReceipt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var root = source.getParent().getParent().getParent().getParent().resolve("build/scenario-results");
        Files.createDirectories(root);
        var launch = Files.createDirectory(root.resolve("launch-" + UUID.randomUUID()));
        launches.add(launch);
        return launch;
    }

    private static String id(Path launch) { return launch.getFileName().toString().substring("launch-".length()); }
    private static ScenarioLaunchReceipt publisher(Path launch) { return publisher(launch,null); }
    private static ScenarioLaunchReceipt publisher(Path launch,java.util.function.Consumer<Path> afterLink) {
        // Unsupported hosts prove portable bytes/atomic no-replace policy, never Windows native support.
        return new ScenarioLaunchReceipt(id(launch),launch.toString(),afterLink,System.getProperty("os.name").startsWith("Windows"));
    }
    private static JsonObject read(Path launch) throws Exception { return JsonParser.parseString(Files.readString(launch.resolve("test-start.json"))).getAsJsonObject(); }
    private static void assertOnlyFinal(Path launch) throws Exception {
        try (var files = Files.list(launch)) { assertEquals(List.of("test-start.json"), files.map(path -> path.getFileName().toString()).toList()); }
    }
    private static void assertIdentity(Path launch, JsonObject receipt) throws Exception {
        assertEquals(id(launch), receipt.get("launchId").getAsString());
        assertEquals(ProcessHandle.current().pid(), receipt.get("pid").getAsLong());
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toString(), receipt.get("startedUtc").getAsString());
        assertTrue(Files.size(launch.resolve("test-start.json")) <= 4096);
    }
}
