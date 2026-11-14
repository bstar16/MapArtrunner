package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.*;
import java.util.function.*;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioInspectLifecycleTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }
    @TempDir Path directory;

    @Test void resetFaultSelectionRequiresOrdinaryGatesExactCounterAndRealPredecessorPolicy() {
        for(String inspect:java.util.Arrays.asList(null,"false","true"))
            for(String acceptance:java.util.Arrays.asList(null,"false","true"))
                for(String automatic:java.util.Arrays.asList(null,"false","true")) {
                    var point=ScenarioRunner.selectResetFault(true,inspect,acceptance,automatic,"reset-archive",Command.RESET,1,0,true);
                    assertEquals("true".equals(inspect) && "true".equals(acceptance) && automatic==null,
                            point==ScenarioRunner.ResetFault.RESET_AFTER_RESTORE_BEFORE_READINESS);
                }
        assertNull(ScenarioRunner.selectResetFault(false,"true","true",null,"reset-archive",Command.RESET,1,0,true));
        for(String selection:List.of("baseline","displaced")) assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,selection,Command.RESET,1,0,false));
        assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"stuck-cleanup",Command.RESET,1,0,true));
        assertThrows(AssertionError.class,() -> ScenarioRunner.selectResetFault(true,"true","true",null,"unknown",Command.RESET,1,0,true));
        for(Command command:List.of(Command.RUN,Command.RERUN,Command.STARTUP,Command.CLOSE))
            assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",command,1,0,true));
        for(int ordinal:List.of(1,5)) {
            int resets=ordinal==1?0:2;
            assertNotNull(ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,ordinal,resets,true));
            assertThrows(AssertionError.class,() -> ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,ordinal,resets,false));
            assertThrows(AssertionError.class,() -> ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,ordinal,resets+1,true));
        }
        assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,3,1,false));
        assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,7,3,false));
        assertThrows(AssertionError.class,() -> ScenarioRunner.selectResetFault(true,"true","true",null,"reset-archive",Command.RESET,2,1,true));
    }

    @Test void resetTokenConsumesBeforeThrowRetiresAndHasDetachedDiagnostics() throws Exception {
        resetFaultProperties(() -> {
            for(var point:ScenarioRunner.ResetFault.values()) {
                var identity=resetIdentity(point==ScenarioRunner.ResetFault.ARCHIVE_BEFORE_COMMITTED?5:1);
                var token=new ResetTokenProbe(point,identity);
                var failure=assertThrows(ScenarioRunner.SimulatedInspectResetFailure.class,() -> token.fire(identity,point));
                assertEquals(point==ScenarioRunner.ResetFault.ARCHIVE_BEFORE_COMMITTED?"SIMULATED_INSPECT_ARCHIVE_BEFORE_COMMITTED"
                        :"SIMULATED_INSPECT_RESET_AFTER_RESTORE_BEFORE_READINESS",failure.getMessage());
                assertEquals("CONSUMED",token.state());
                assertThrows(AssertionError.class,() -> token.fire(identity,point)); assertEquals("RETIRED",token.state());
                token.retire(); assertThrows(AssertionError.class,() -> token.check(identity,point));
                assertNull(failure.getCause()); assertEquals(0,failure.getSuppressed().length);
                for(var field:failure.getClass().getDeclaredFields()) {
                    field.setAccessible(true); var facts=field.get(failure);
                    assertTrue(facts.getClass().isRecord());
                    for(var component:facts.getClass().getRecordComponents())
                        assertTrue(Set.of(String.class,UUID.class,int.class,long.class).contains(component.getType()),component::toString);
                }
            }
        });
    }

    @Test void resetTokenRefusesEveryChangedIdentityPointAndGateAndRetiresOnEarlierUnwind() throws Exception {
        resetFaultProperties(() -> {
            var original=resetIdentity(1); var point=ScenarioRunner.ResetFault.RESET_AFTER_RESTORE_BEFORE_READINESS;
            for(var component:original.getClass().getRecordComponents()) {
                Object changed=switch(component.getName()) {
                    case "operation" -> new Operation(original.invocation(),1,original.operation().operationId(),Command.RESET,original.operation().evidenceDirectory());
                    case "work" -> new Scope(original.operation(),Kind.WORK,original.work().deadlineNanos());
                    case "invocation" -> UUID.randomUUID();
                    case "profile","definition" -> ScenarioDefinition.load();
                    case "save","stage" -> directory.resolve("foreign");
                    case "resetOrdinal" -> 2;
                    default -> new Object();
                };
                var candidate=changedResetIdentity(original,component.getName(),changed);
                var token=new ResetTokenProbe(point,original);
                assertThrows(AssertionError.class,() -> token.check(candidate,point),component.getName());
                assertThrows(AssertionError.class,() -> token.fire(original,point));
            }
            var wrongPoint=new ResetTokenProbe(point,original);
            assertThrows(AssertionError.class,() -> wrongPoint.fire(original,ScenarioRunner.ResetFault.ARCHIVE_BEFORE_COMMITTED));
            for(String property:List.of("mapart.test.inspect","mapart.test.inspect.acceptance","fabric.client.gametest","mapart.test.inspect.acceptance.case")) {
                var token=new ResetTokenProbe(point,original); var old=System.getProperty(property);
                System.setProperty(property,"false");
                assertThrows(AssertionError.class,() -> token.fire(original,point));
                if(old==null) System.clearProperty(property); else System.setProperty(property,old);
                assertThrows(AssertionError.class,() -> token.fire(original,point));
            }
            for(boolean earlyFailure:List.of(false,true)) {
                var token=new ResetTokenProbe(point,original);
                try { if(earlyFailure) throw new IllegalStateException("earlier archive/cancel/expiry"); }
                catch(IllegalStateException expected) { assertEquals("earlier archive/cancel/expiry",expected.getMessage()); }
                finally { token.retire(); }
                assertThrows(AssertionError.class,() -> token.fire(original,point));
            }
            assertThrows(AssertionError.class,() -> new ResetTokenProbe(point,changedResetIdentity(original,"work",new Scope(original.operation(),Kind.CLEANUP,99))));
        });
    }

    @Test void resetStageProofRejectsMissingForeignCommittedImportAndReadinessStages() throws Exception {
        var roots=roots(); var operation=new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RESET,roots.evidence().resolve("operation"));
        var stage=Files.createDirectories(operation.evidenceDirectory().resolve("reset-1"));
        var archive=ScenarioRunner.ResetFault.ARCHIVE_BEFORE_COMMITTED; var restored=ScenarioRunner.ResetFault.RESET_AFTER_RESTORE_BEFORE_READINESS;
        ScenarioRunner.requireResetFaultStage(roots,operation,stage,1,archive);
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireResetFaultStage(roots,operation,stage,1,restored));
        for(String name:List.of("COMMITTED","retained-source.schem","imported-plan.txt")) Files.writeString(stage.resolve(name),"actual temporary stage fixture");
        ScenarioRunner.requireResetFaultStage(roots,operation,stage,1,restored);
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireResetFaultStage(roots,operation,stage,1,archive));
        for(String name:List.of("readiness-1.json","stable-start.json")) {
            Files.writeString(stage.resolve(name),"must reject");
            assertThrows(AssertionError.class,() -> ScenarioRunner.requireResetFaultStage(roots,operation,stage,1,restored));
            Files.delete(stage.resolve(name));
        }
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireResetFaultStage(roots,operation,stage,2,restored));
        var foreign=new Operation(operation.sessionId(),1,UUID.randomUUID(),Command.RESET,operation.evidenceDirectory().resolve("other"));
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireResetFaultStage(roots,foreign,stage,1,restored));
    }

    @Test void archiveOwnedComparisonIgnoresPhysicsTimeButRejectsChangedOrMissingBlocksAndInventory() {
        String before="{\"blocks\":[\"stone\"],\"inventory\":[9],\"containers\":[],\"age\":1,\"hunger\":20,\"x\":1}";
        String after="{\"blocks\":[\"stone\"],\"inventory\":[9],\"containers\":[],\"age\":99,\"hunger\":19,\"x\":2}";
        ScenarioRunner.requirePreservedOwnedState(before,after);
        for(String changed:List.of(after.replace("stone","air"),after.replace("[9]","[8]"),"{\"blocks\":[],\"inventory\":[9]}"))
            assertThrows(AssertionError.class,() -> ScenarioRunner.requirePreservedOwnedState(before,changed));
    }

    @Test void simulatedFailureWithoutNativeProofStaysOriginalIncompleteAndClearsEnteredFinalization() throws Exception {
        for(String boundary:List.of("missing","proof","guard","settlement","abandon","admission")) {
            var host=new InspectTransport(); var runner=runner(host); var operation=runner.beginInspectOperation(Command.RESET);
            var work=host.beginWork(operation);
            var failure=new ScenarioRunner.SimulatedInspectResetFailure(new ScenarioRunner.ResetFailureFacts("RESET_AFTER_RESTORE_BEFORE_READINESS",
                    operation.sessionId(),operation.operationId(),1,1,ScenarioDefinition.load().definitionHash(),operation.evidenceDirectory().resolve("reset-1").toString(),work.deadlineNanos()));
            if(!boundary.equals("missing")) setResetDiagnostic(runner,operation,failure);
            var cleanup=host.beginCleanup(work,failure);
            if(boundary.equals("guard")) {
                var foreign=new Operation(operation.sessionId(),1,operation.operationId(),Command.RESET,operation.evidenceDirectory());
                assertThrows(AssertionError.class,() -> host.inScope(cleanup,() -> runner.finishInspectOperation(foreign,cleanup,failure)));
            } else if(boundary.equals("admission")) {
                runner.failInspectAdmission(operation,failure);
            } else if(boundary.equals("abandon")) {
                assertThrows(AssertionError.class,() -> host.inScope(cleanup,runner::close));
            } else if(boundary.equals("settlement")) {
                host.settlementFailure=new IllegalStateException("settlement unavailable");
                assertSame(failure,assertThrows(ScenarioRunner.SimulatedInspectResetFailure.class,
                        () -> host.inScope(cleanup,() -> runner.finishInspectOperation(operation,cleanup,failure))));
                assertTrue(failure.getSuppressed().length>0);
            } else {
                var completion=host.inScope(cleanup,() -> runner.finishInspectOperation(operation,cleanup,failure));
                assertSame(failure,completion.failure()); assertEquals("FAIL",completion.verdict());
                assertFalse(completion.evidenceComplete()); assertTrue(completion.runId().isEmpty());
                assertFalse(Files.exists(operation.evidenceDirectory().resolve("simulated-reset-fault-verification.json")));
            }
            assertNull(runnerField("inspectResetDiagnostic").get(runner));
        }
    }

    /** Reflection reaches the actual private one-shot implementation; no token behavior is doubled. */
    private static final class ResetTokenProbe {
        final Object token;
        ResetTokenProbe(ScenarioRunner.ResetFault point,ScenarioRunner.ResetIdentity identity) {
            try {
                var type=Class.forName(ScenarioRunner.class.getName()+"$ResetToken");
                var constructor=type.getDeclaredConstructor(ScenarioRunner.ResetFault.class,ScenarioRunner.ResetIdentity.class); constructor.setAccessible(true);
                token=constructor.newInstance(point,identity);
            } catch(java.lang.reflect.InvocationTargetException error) { throw tokenError(error); }
            catch(ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        void check(ScenarioRunner.ResetIdentity identity,ScenarioRunner.ResetFault point) { invoke("check",identity,point); }
        void fire(ScenarioRunner.ResetIdentity identity,ScenarioRunner.ResetFault point) { invoke("fire",identity,point); }
        void retire() { invoke("retire"); }
        String state() throws Exception { var field=token.getClass().getDeclaredField("state"); field.setAccessible(true); return field.get(token).toString(); }
        private void invoke(String name,Object... args) {
            try {
                var types=java.util.Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new);
                var method=token.getClass().getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(token,args);
            } catch(java.lang.reflect.InvocationTargetException error) { throw tokenError(error); }
            catch(ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        private static RuntimeException tokenError(java.lang.reflect.InvocationTargetException error) {
            if(error.getCause() instanceof Error fatal) throw fatal;
            return (RuntimeException)error.getCause();
        }
    }

    @Test void resetFaultPredecessorUsesActualRetainedCompletionSuccessAndNonemptyBoundHistory() throws Exception {
        var host=new InspectTransport(); var runner=runner(host);
        var first=runner.beginInspectOperation(Command.RESET);
        assertTrue(resetPredecessor(runner,first));
        runnerField("resetOrdinal").setInt(runner,1); assertFalse(resetPredecessor(runner,first));
        runnerField("resetOrdinal").setInt(runner,0);
        var work=host.beginWork(first); var cleanup=host.beginCleanup(work,new IllegalStateException("synthetic owner prelude"));
        host.inScope(cleanup,() -> runner.finishInspectOperation(first,cleanup,new IllegalStateException("synthetic owner prelude")));
        for(int i=2;i<=3;i++) {
            var op=runner.beginInspectOperation(Command.RESET); var w=host.beginWork(op); var c=host.beginCleanup(w,null);
            host.inScope(c,() -> runner.finishInspectOperation(op,c,new IllegalStateException("synthetic owner prelude")));
        }
        var run=runner.beginInspectOperation(Command.RUN); var runWork=host.beginWork(run);
        var result=successfulOwnerState(runner,run,checkpoint(),"synthetic-start");
        host.inScope(runWork,() -> runner.assertResult(result));
        var runCleanup=host.beginCleanup(runWork,null);
        var settled=host.inScope(runCleanup,() -> runner.finishInspectOperation(run,runCleanup,null));
        assertEquals("NO_OWNED_WORLD",settled.cleanupStatus());
        // Synthetic immutable predecessor facts test the policy; this is deliberately not a native RELEASED result.
        runnerField("inspectLast").set(runner,new ScenarioRunner.InspectCompletion(settled.sessionId(),settled.operationId(),4,Command.RUN,
                settled.runId(),"PASS",null,settled.evidenceDirectory(),true,"RELEASED",true,settled.summaryPath()));
        var fifth=runner.beginInspectOperation(Command.RESET);
        assertFalse(resetPredecessor(runner,fifth),"A successful completion alone cannot replace bound retained history");
        var context=result.terminal().lifecycle().context().orElseThrow();
        var watch=pairedWatch(context,witnessSamples(context),null,null); runnerField("watch").set(runner,watch);
        assertTrue(resetPredecessor(runner,fifth));
        var prior=runnerField("priorCountedSuccess").get(runner); runnerField("priorCountedSuccess").set(runner,null);
        assertFalse(resetPredecessor(runner,fifth)); runnerField("priorCountedSuccess").set(runner,prior);
        var completion=runner.lastInspectCompletion().orElseThrow();
        runnerField("inspectLast").set(runner,new ScenarioRunner.InspectCompletion(UUID.randomUUID(),completion.operationId(),4,Command.RUN,
                completion.runId(),"PASS",null,completion.evidenceDirectory(),true,"RELEASED",true,completion.summaryPath()));
        assertFalse(resetPredecessor(runner,fifth));
        assertEquals(0,host.effects,"Owner-state tests grant no native completion credit");
    }

    private static boolean resetPredecessor(ScenarioRunner runner,Operation operation) throws Exception {
        var method=ScenarioRunner.class.getDeclaredMethod("resetFaultPredecessor",Operation.class); method.setAccessible(true);
        return (boolean)method.invoke(runner,operation);
    }

    private static void setResetDiagnostic(ScenarioRunner runner,Operation operation,ScenarioRunner.SimulatedInspectResetFailure failure) throws Exception {
        var holder=runnerField("inspectResetDiagnostic"); var constructor=holder.getType().getDeclaredConstructors()[0]; constructor.setAccessible(true);
        holder.set(runner,constructor.newInstance(operation,failure,operation.evidenceDirectory().resolve("reset-1"),1,null,
                new ScenarioRunner.InspectIdleObservation(new ScenarioObserver.Watch().retained()),null,null,null,null,null,null,null,null,null));
    }

    private ScenarioRunner.ResetIdentity resetIdentity(int ordinal) {
        var operation=new Operation(UUID.randomUUID(),ordinal,UUID.randomUUID(),Command.RESET,directory.resolve("operation-"+ordinal));
        var profile=ScenarioDefinition.load();
        return new ScenarioRunner.ResetIdentity(operation,new Scope(operation,Kind.WORK,600_000_000_001L),operation.sessionId(),profile,
                ScenarioDefinition.load(),directory.resolve("save"),new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),
                ordinal==1?1:3,operation.evidenceDirectory().resolve("reset-"+(ordinal==1?1:3)));
    }

    private static ScenarioRunner.ResetIdentity changedResetIdentity(ScenarioRunner.ResetIdentity original,String name,Object replacement) throws Exception {
        var fields=original.getClass().getRecordComponents(); var args=new Object[fields.length]; var types=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++) { types[i]=fields[i].getType(); args[i]=fields[i].getName().equals(name)?replacement:fields[i].getAccessor().invoke(original); }
        return original.getClass().getDeclaredConstructor(types).newInstance(args);
    }

    private interface ResetTest { void run() throws Exception; }
    private static void resetFaultProperties(ResetTest test) throws Exception {
        var names=List.of("mapart.test.inspect","mapart.test.inspect.acceptance","fabric.client.gametest","mapart.test.inspect.acceptance.case");
        var old=new java.util.HashMap<String,String>(); names.forEach(name -> old.put(name,System.getProperty(name)));
        try { System.setProperty(names.get(0),"true"); System.setProperty(names.get(1),"true"); System.clearProperty(names.get(2)); System.setProperty(names.get(3),"reset-archive"); test.run(); }
        finally { names.forEach(name -> { if(old.get(name)==null) System.clearProperty(name); else System.setProperty(name,old.get(name)); }); }
    }

    @Test void opaqueIdleValueComparesWholeHistoryBindingAndFirstFailureDespiteEqualCounts() {
        var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),null,null);
        var retained=watch.retained(); var before=new ScenarioRunner.InspectIdleObservation(retained);
        assertEquals(before,new ScenarioRunner.InspectIdleObservation(watch.retained()));
        var samples=new java.util.ArrayList<>(retained.samples());
        samples.set(0,laterEnd(context,99));
        var witnesses=new java.util.ArrayList<>(retained.witnesses()); var first=witnesses.getFirst();
        witnesses.set(0,new ScenarioObserver.ServerWitness(first.publication(),first.callback(),
                reading(retained.binding().server(),first.publication().sample(),4.5,999)));
        for(var changed:List.of(
                new ScenarioObserver.Watch.Retained(samples,null,retained.binding(),retained.witnesses(),null,null),
                new ScenarioObserver.Watch.Retained(retained.samples(),null,retained.binding(),witnesses,null,null),
                new ScenarioObserver.Watch.Retained(retained.samples(),null,witnessBinding(context,null,null),retained.witnesses(),null,null),
                new ScenarioObserver.Watch.Retained(retained.samples(),new AssertionError("new first failure"),retained.binding(),retained.witnesses(),null,null))) {
            var after=new ScenarioRunner.InspectIdleObservation(changed);
            assertEquals(before.samples(),after.samples()); assertEquals(before.witnesses(),after.witnesses());
            assertEquals(before.bound(),after.bound()); assertNotEquals(before,after);
        }
        var a=new MutableFailure(); var b=new MutableFailure(); b.setStackTrace(a.getStackTrace());
        assertNotEquals(new ScenarioRunner.InspectIdleObservation(new ScenarioObserver.Watch.Retained(List.of(),a,null,List.of(),null,null)),
                new ScenarioRunner.InspectIdleObservation(new ScenarioObserver.Watch.Retained(List.of(),b,null,List.of(),null,null)),"First-failure identity matters");
    }

    @Test void opaqueIdleValueFreezesThrowableRenderingIncludingLaterMessageCauseStackAndSuppressedChanges() throws Exception {
        for(String changed:List.of("message","cause","stack","suppressed")) {
            var watch=new ScenarioObserver.Watch(); var failure=new MutableFailure(); field("failure").set(watch,failure);
            var before=new ScenarioRunner.InspectIdleObservation(watch.retained());
            assertEquals(before,new ScenarioRunner.InspectIdleObservation(watch.retained()));
            switch(changed) {
                case "message" -> failure.message="mutated diagnostic";
                case "cause" -> failure.initCause(new AssertionError("late cause"));
                case "stack" -> failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("changed.Owner","later","Owner.java",42)});
                case "suppressed" -> failure.addSuppressed(new AssertionError("late suppressed diagnostic"));
            }
            assertSame(failure,watch.retained().failure());
            assertNotEquals(before,new ScenarioRunner.InspectIdleObservation(watch.retained()),changed);
        }
    }

    private static final class MutableFailure extends RuntimeException {
        String message="original diagnostic";
        @Override public String getMessage() { return message; }
        @Override public boolean equals(Object other) { return other instanceof MutableFailure; }
        @Override public int hashCode() { return 1; }
    }

    @Test void realFrozenWatchRetainsOpaqueValueAndClosedEvidenceAcrossBothIdleHistoriesAndLateClaims() throws Exception {
        for(boolean failed:List.of(false,true)) {
            var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),null,null);
            var retained=watch.retained(); watch.append(retained.binding(),laterEnd(context,12),retained.binding().client(),4.5);
            var inFlight=watch.beginServerRead(); assertNotNull(inFlight);
            if(failed) field("failure").set(watch,new AssertionError("original retained observer failure"));
            watch.freeze(); var before=new ScenarioRunner.InspectIdleObservation(watch.retained());
            var host=new InspectTransport(); var runner=new ScenarioRunner(host,watch,roots()); var completion=settledPrior(runner,host);
            var hashes=closedFileHashes(completion.evidenceDirectory()); int effects=host.effects;
            for(int callback=0;callback<6002;callback++) {
                watch.record(null,"START"); watch.record(null,"END"); watch.recordServer(null);
                watch.append(retained.binding(),laterEnd(context,13),retained.binding().client(),4.5);
                watch.commitServer(inFlight,reading(retained.binding().server(),inFlight.publications().getFirst().sample(),4.5,999));
                watch.failRead(inFlight,new AssertionError("late read must not replace retained failure"));
            }
            assertEquals(before,new ScenarioRunner.InspectIdleObservation(watch.retained()));
            assertTrue(before.samples()>0 && before.witnesses()>0 && before.bound());
            assertEquals(hashes,closedFileHashes(completion.evidenceDirectory())); assertEquals(effects,host.effects);
        }
    }

    @Test void retainedIdleValueCannotAuthorizeANewNativeReadOrReopenExpiredScope() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicLong(1);
        var dispatches=new java.util.concurrent.atomic.AtomicInteger();
        var host=new InspectScenarioHost(time::get,() -> false,() -> false,a -> dispatches.incrementAndGet(),a -> dispatches.incrementAndGet(),null);
        var op=new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RUN,directory);
        var scope=host.beginWork(op); var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),host,scope);
        watch.freeze(); var oldValue=new ScenarioRunner.InspectIdleObservation(watch.retained());
        time.set(scope.deadlineNanos()+1);
        assertFalse(host.observationScopeValid(scope));
        assertThrows(ScopeTimeoutException.class,() -> host.inScope(scope,() -> host.waitTick()));
        assertEquals(0,dispatches.get()); assertEquals(oldValue,new ScenarioRunner.InspectIdleObservation(watch.retained()));
        var runner=new ScenarioRunner(new InspectTransport(),watch,roots());
        assertThrows(AssertionError.class,() -> runner.observeInspectIdle(null,null));
        assertEquals(oldValue,new ScenarioRunner.InspectIdleObservation(watch.retained()));
        assertTrue(runner.lastInspectCompletion().isEmpty());
    }

    @Test void commandsBoundaryObservationRechecksPublicationAndNativeAuthorityWithoutAllocatingWork() throws Exception {
        // Real owners, no created world: even a fresh equal publication must reach and fail the native guard.
        var host=new InspectTransport(); var runner=runner(host); var completion=settledPrior(runner,host);
        var commands=new ScenarioCommands(action -> fail("Boundary read must not schedule work"),host);
        for(String name:List.of("runner","ready","last")) {
            var field=ScenarioCommands.class.getDeclaredField(name); field.setAccessible(true);
            field.set(commands,name.equals("runner")?runner:name.equals("ready")?true:completion);
        }
        var properties=List.of("mapart.test.inspect","mapart.test.inspect.acceptance","mapart.test.inspect.acceptance.case","fabric.client.gametest");
        var previous=new java.util.HashMap<String,String>(); properties.forEach(key -> previous.put(key,System.getProperty(key)));
        int ordinal=runnerField("inspectOrdinal").getInt(runner), effects=host.effects;
        var hashes=closedFileHashes(completion.evidenceDirectory());
        try {
            System.setProperty(properties.get(0),"true"); System.setProperty(properties.get(1),"true");
            System.setProperty(properties.get(2),"displaced"); System.clearProperty(properties.get(3));
            var expected=commands.observeInspect().last().orElseThrow();
            assertEquals(expected,commands.observeInspect().last().orElseThrow());
            assertNotSame(expected,commands.observeInspect().last().orElseThrow());
            for(int read=0;read<2;read++) assertEquals("Inspect command session/world unavailable",
                    assertThrows(AssertionError.class,() -> commands.observeAcceptanceIdle(null,expected)).getMessage());
            var queued=ScenarioCommands.class.getDeclaredField("queued"); queued.setAccessible(true); queued.setBoolean(commands,true);
            assertThrows(IllegalStateException.class,() -> commands.observeAcceptanceIdle(null,expected)); queued.setBoolean(commands,false);
            assertThrows(IllegalStateException.class,() -> commands.observeAcceptanceIdle(null,null));
            System.setProperty(properties.get(3),"false");
            assertThrows(IllegalStateException.class,() -> commands.observeAcceptanceIdle(null,expected));
        } finally {
            previous.forEach((key,value) -> { if(value==null) System.clearProperty(key); else System.setProperty(key,value); });
        }
        assertEquals(ordinal,runnerField("inspectOrdinal").getInt(runner)); assertEquals(effects,host.effects);
        assertTrue(runnerField("inspectOperation").get(runner)==null); assertEquals(hashes,closedFileHashes(completion.evidenceDirectory()));
    }

    @Test void simulatedCadenceRetainsBothEntryEndsBeforeLaneInOnePhysicalRead() {
        var context=witnessContext(); var watch=new ScenarioObserver.Watch();
        var binding=witnessBinding(context,null,null); watch.bind(binding);
        var samples=witnessSamples(context);
        var ends=List.of(samples.get(13),samples.get(15),samples.get(17));
        for(var end:ends) watch.append(binding,end,binding.client(),4.5);
        var claim=watch.beginServerRead(); assertNotNull(claim);
        var scalar=reading(binding.server(),ends.getLast(),4.5,110);
        watch.commitServer(claim,scalar);
        var retained=watch.retained();
        assertNull(retained.failure());
        assertEquals(ends,retained.witnesses().stream().map(w -> w.publication().sample()).toList());
        for(int n=0;n<ends.size();n++) {
            assertSame(ends.get(n),retained.witnesses().get(n).publication().sample());
            assertSame(scalar,retained.witnesses().get(n).reading());
            assertEquals(claim.callback(),retained.witnesses().get(n).callback());
        }
        assertNull(watch.beginServerRead());
        assertEquals(3,watch.retained().witnesses().size());
    }

    @Test void denseNativeEvidenceMustNotDependOnSparseHeldWorkerSelection() throws Exception {
        var context=witnessContext();
        var dense=witnessSamples(context);
        var retained=pairedWatch(context,dense,null,null).retained();
        var proof=ScenarioRunner.evaluateTraversal(context.runId(),retained,null);
        assertEquals(List.of(2L,3L,4L,5L,6L,7L,10L,11L),proof.ordered().stream().map(w -> w.publication().sample().tick()).toList());
        // Same sparse held worker selection as the behavioral RED against the old production verifier.
        var sparse=retained.witnesses().stream().filter(w -> Set.of(1L,5L).contains(w.publication().sample().tick())).toList();
        assertRejected(retained,sparse,retained.samples());
        assertRejected(retained,List.of(),retained.samples());
    }

    @Test void sharedEntryReadSkipsSameBatchWestAndPreservesEarlierSouthThenUsesLaterIndependentReads() {
        var context=witnessContext(); var samples=witnessSamples(context,true);
        var earlyWalk=samples.stream().map(s -> s.phase().equals("END") && s.tick()==10
                ? witnessSample(context,10,com.mapartrunner.core.run.ForwardTraversal.Substage.LANE,1,"WEST",11.5,7.5,true,0,0):s).toList();
        // Simulated cadence: late SHIFT, two entry ENDs and first WEST WALK alias one real-seam scalar fixture.
        var r=batchedWatch(context,earlyWalk,null,null,Set.of(1L,2L,3L,4L,5L,6L,10L,11L,12L)).retained();
        var proof=ScenarioRunner.evaluateTraversal(context.runId(),r,null);
        assertEquals(List.of(2L,3L,4L,5L,6L,8L,11L,12L),proof.ordered().stream().map(w -> w.publication().sample().tick()).toList());
        assertEquals(8,proof.ordered().stream().map(ScenarioObserver.ServerWitness::callback).distinct().count());
        assertEquals(8,proof.ordered().stream().map(w -> w.reading().serverTick()).distinct().count());
        var batch=r.witnesses().stream().filter(w -> w.publication().sample().tick()>=7 && w.publication().sample().tick()<=10).toList();
        assertEquals(4,batch.size()); for(var row:batch) assertSame(batch.getFirst().reading(),row.reading());
        assertRejectedBecause(r,r.witnesses().stream().filter(w -> w.publication().sample().tick()<=10).toList(),
                "Missing/unordered native traversal witnesses");
        // Retention still cannot manufacture the next physical read's full server entry rectangle.
        var wrong=reading(r.binding().server(),laterEnd(context,10),4.5,110);
        var noServerEntry=r.witnesses().stream().map(w -> batch.contains(w)?new ScenarioObserver.ServerWitness(w.publication(),w.callback(),wrong):w).toList();
        assertRejectedBecause(r,noServerEntry,"Missing/unordered native traversal witnesses");
    }

    @Test void endpointAndShiftBatchRetainsBothButNeedsAnIndependentShiftAnchor() {
        var context=witnessContext(); var r=batchedWatch(context,witnessSamples(context,true),null,null,
                Set.of(1L,2L,3L,5L,6L,7L,8L,9L,10L,11L,12L)).retained();
        var endpoint=r.witnesses().get(3); var shift=r.witnesses().get(4);
        assertSame(endpoint.reading(),shift.reading()); assertEquals(endpoint.callback(),shift.callback());
        var proof=ScenarioRunner.evaluateTraversal(context.runId(),r,null);
        assertEquals(List.of(2L,3L,4L,6L,7L,8L,11L,12L),proof.ordered().stream().map(w -> w.publication().sample().tick()).toList());
        var outside=new ScenarioObserver.ServerReading(endpoint.reading().binding(),11.249,66,2.5,4.5,25,205,105);
        assertRejectedBecause(r,r.witnesses().stream().map(w -> w.callback()==endpoint.callback()
                ?new ScenarioObserver.ServerWitness(w.publication(),w.callback(),outside):w).toList(),"Missing/unordered native traversal witnesses");
    }

    @Test void claimBoundaryExcludesNewEndsBeforeAndAfterScalarConstructionAndRejectsOverlapForgeryReplay() {
        for(boolean scalarFirst:List.of(false,true)) {
            var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
            var ends=witnessSamples(context); var endpoint=ends.get(7); var shift=ends.get(9); var later=ends.get(11);
            watch.append(binding,endpoint,binding.client(),4.5); watch.append(binding,shift,binding.client(),4.5);
            var claim=watch.beginServerRead(); assertNotNull(claim); assertEquals(2,claim.publications().size());
            assertThrows(UnsupportedOperationException.class,() -> claim.publications().clear());
            assertNull(watch.beginServerRead()); // Cannot replace the outstanding exact claim.
            ScenarioObserver.ServerReading scalar=null;
            if(scalarFirst) scalar=reading(binding.server(),shift,4.5,105);
            watch.append(binding,later,binding.client(),4.5);
            if(!scalarFirst) scalar=reading(binding.server(),shift,4.5,105);
            var forged=new ScenarioObserver.ServerRead(claim.binding(),claim.publications(),claim.callback(),claim.resume());
            assertEquals(claim,forged); assertNotSame(claim,forged);
            var before=watch.retained(); watch.commitServer(forged,scalar); watch.failRead(forged,new AssertionError("forged"));
            assertEquals(before,watch.retained());
            watch.commitServer(claim,scalar);
            var committed=watch.retained(); assertEquals(2,committed.witnesses().size());
            for(var row:committed.witnesses()) assertSame(scalar,row.reading());
            watch.commitServer(claim,scalar); watch.failRead(claim,new AssertionError("replayed")); assertEquals(committed,watch.retained());
            var next=watch.beginServerRead(); assertNotNull(next); assertEquals(3,next.callback());
            assertEquals(1,next.publications().size()); assertSame(later,next.publications().getFirst().sample());
            watch.commitServer(next,reading(binding.server(),later,4.5,106));
            assertEquals(3,watch.retained().witnesses().size()); assertNull(watch.beginServerRead()); assertNull(watch.retained().failure());
        }
    }

    @Test void serverFailureSurvivesLaterValidPublicationAndBindingLossRejectsWholeBatch() {
        for(String cause:List.of("read-error","server-binding","client-binding")) {
            var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
            var ends=witnessSamples(context); var first=ends.get(7); var second=ends.get(9); var third=ends.get(11);
            watch.append(binding,first,binding.client(),4.5); watch.append(binding,second,binding.client(),4.5);
            var claim=watch.beginServerRead(); assertNotNull(claim);
            watch.append(binding,third,binding.client(),4.5);
            Throwable expected;
            switch(cause) {
                case "read-error" -> { expected=new IllegalStateException("actual earlier read failure"); watch.failRead(claim,expected); }
                case "server-binding" -> {
                    var invalid=witnessBinding(context,null,null).server();
                    watch.commitServer(claim,reading(invalid,first,4.5,105)); expected=watch.retained().failure();
                    assertEquals("Native server END lost exact binding/reach",expected.getMessage());
                }
                default -> {
                    watch.append(binding,laterEnd(context,12),witnessBinding(context,null,null).client(),4.5);
                    expected=watch.retained().failure(); assertEquals("Native client END lost exact observation binding/reach",expected.getMessage());
                    watch.commitServer(claim,reading(binding.server(),first,4.5,105));
                }
            }
            assertNotNull(expected); assertSame(expected,watch.retained().failure()); assertTrue(watch.retained().witnesses().isEmpty());
            watch.failRead(claim,new AssertionError("second cause")); watch.commitServer(claim,reading(binding.server(),first,4.5,106));
            assertSame(expected,watch.retained().failure()); assertNull(watch.beginServerRead());
        }
    }

    @Test void idleCallbacksCountPhysicalOrdinalsWithoutRereadingOneConsumedEnd() throws Exception {
        var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
        var end=laterEnd(context,1); watch.append(binding,end,binding.client(),4.5);
        var first=watch.beginServerRead(); watch.commitServer(first,reading(binding.server(),end,4.5,101));
        for(int n=0;n<6001;n++) { assertNull(watch.beginServerRead()); watch.recordServer(null); }
        assertEquals(12003,field("serverCallbacks").getLong(watch));
        assertEquals(1,watch.retained().witnesses().size()); assertNull(watch.retained().failure());
        watch.append(binding,laterEnd(context,2),binding.client(),4.5);
        var next=watch.beginServerRead(); assertNotNull(next); assertEquals(12004,next.callback());
    }

    @Test void duplicateOrReorderedEndPublicationLatchesAtTheActualWatchSeam() {
        for(long nextFrame:List.of(1L,2L)) {
            var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
            watch.append(binding,laterEnd(context,2),binding.client(),4.5);
            var claim=watch.beginServerRead(); assertNotNull(claim);
            watch.append(binding,laterEnd(context,nextFrame),binding.client(),4.5);
            var first=watch.retained().failure(); assertEquals("Duplicate/reordered client END publication",first.getMessage());
            watch.commitServer(claim,reading(binding.server(),laterEnd(context,2),4.5,102));
            assertTrue(watch.retained().witnesses().isEmpty()); assertSame(first,watch.retained().failure());
        }
    }

    @Test void rawBatchStreamRejectsFalseAliasesDuplicateFramesAndNoncontiguousCallbackGroups() {
        var context=witnessContext(); var r=batchedWatch(context,witnessSamples(context),null,null,
                Set.of(1L,2L,3L,4L,5L,6L,9L,10L,11L)).retained();
        assertDoesNotThrow(() -> ScenarioRunner.evaluateTraversal(context.runId(),r,null));
        for(String forgery:List.of("equal-new-reading","different-reading","same-callback-new-tick","new-callback-same-tick",
                "reuse-reading","nonadjacent-reuse","duplicate-frame","reordered-frame","reordered-group")) {
            var bad=changeWitness(r,forgery.equals("nonadjacent-reuse")?11:forgery.equals("new-callback-same-tick")
                    || forgery.equals("reuse-reading") || forgery.equals("reordered-group")?10:8,w -> {
                var prior=r.witnesses().get(6); var value=prior.reading();
                return switch(forgery) {
                    case "equal-new-reading","different-reading","same-callback-new-tick" -> new ScenarioObserver.ServerWitness(w.publication(),w.callback(),
                            new ScenarioObserver.ServerReading(value.binding(),value.x()+(forgery.equals("different-reading")?1:0),value.y(),value.z(),value.reach(),
                                    value.age(),value.worldTime(),value.serverTick()+(forgery.equals("same-callback-new-tick")?1:0)));
                    case "new-callback-same-tick" -> new ScenarioObserver.ServerWitness(w.publication(),w.callback(),reading(value.binding(),w.publication().sample(),4.5,value.serverTick()));
                    case "reuse-reading","nonadjacent-reuse" -> new ScenarioObserver.ServerWitness(w.publication(),w.callback(),value);
                    case "duplicate-frame" -> new ScenarioObserver.ServerWitness(prior.publication(),w.callback(),value);
                    case "reordered-frame" -> new ScenarioObserver.ServerWitness(r.witnesses().get(5).publication(),w.callback(),value);
                    default -> new ScenarioObserver.ServerWitness(w.publication(),prior.callback(),w.reading());
                };
            });
            assertRejectedBecause(r,bad,"Duplicate/reordered native frame/server callback/tick");
        }
        var duplicate=new java.util.ArrayList<>(r.witnesses()); duplicate.add(7,r.witnesses().get(6));
        assertRejectedBecause(r,duplicate,"Duplicate/reordered native frame/server callback/tick");
    }

    private static void assertRejectedBecause(ScenarioObserver.Watch.Retained original,List<ScenarioObserver.ServerWitness> witnesses,String reason) {
        var retained=new ScenarioObserver.Watch.Retained(original.samples(),original.failure(),original.binding(),witnesses,null,null);
        var failure=assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(original.binding().context().runId(),retained,null));
        assertTrue(failure.getMessage().startsWith(reason),failure.getMessage());
    }

    private static RunLifecycle.Context witnessContext() {
        return new RunLifecycle.Context(UUID.randomUUID(),"paired-connection","paired-world","minecraft:overworld",
                "paired-plan",new BuildPlan.GridPos(0,65,0),"paired-settings");
    }

    @Test void postResumeServerCallbackPrecedesDelayedWorkerDiagnostics() {
        var result=scheduledResume(true,6.165848892158152,6.415356906947316);
        assertEquals(716,result.resumed().server().serverTick());
        assertDoesNotThrow(() -> ScenarioRunner.evaluateTraversal(result.context().runId(),result.watch().retained(),result.resumed()));
        var lower=result.watch().retained().lower();
        assertEquals(710,lower.callback()); assertEquals(710,lower.reading().serverTick()); assertEquals(6.165848892158152,lower.reading().z());
        var uppers=result.watch().retained().witnesses().stream().filter(w -> w.publication().sample().tick()>=299 && w.publication().sample().tick()<=301).toList();
        assertEquals(3,uppers.size()); assertEquals(713,uppers.getFirst().callback());
        for(var upper:uppers) assertSame(uppers.getFirst().reading(),upper.reading());
        assertNotSame(lower.reading(),uppers.getFirst().reading());
    }

    @Test void oldFailedWindowCannotInventAnEarlyLowerOrUseNextEntryAsUpper() {
        var schedule=scheduledResume(false,6.165848892158152,6.415356906947316);
        var r=schedule.watch().retained();
        assertEquals(713,r.lower().callback()); assertEquals(713,r.lower().reading().serverTick());
        assertEquals(6.415356906947316,r.lower().reading().z());
        var next=r.witnesses().stream().filter(w -> w.callback()==727).findFirst().orElseThrow();
        assertEquals(com.mapartrunner.core.run.ForwardTraversal.Substage.NEXT_ENTRY,next.publication().sample().status().engine().traversal().orElseThrow().substage());
        assertPostResumeFailure(schedule,r,"No actual independent SOUTH progress after manual resume");
    }

    @Test void genuinePreResumeServerCatchUpIsNotPostResumeServerDisplacement() {
        var schedule=scheduledResume(true,6.1,6.1); var r=schedule.watch().retained();
        var before=r.witnesses().stream().filter(w -> w.publication().sample().tick()==297).findFirst().orElseThrow();
        var caughtUp=r.witnesses().stream().filter(w -> w.publication().sample().tick()==298).findFirst().orElseThrow();
        assertEquals(6.0,before.reading().z()); assertEquals(6.1,caughtUp.reading().z());
        assertTrue(caughtUp.callback()<r.lower().callback()); assertTrue(caughtUp.reading().serverTick()<r.lower().reading().serverTick());
        assertEquals(6.1,r.lower().reading().z());
        var upper=r.witnesses().stream().filter(w -> w.publication().sample().tick()==299).findFirst().orElseThrow();
        assertEquals(6.1,upper.reading().z()); assertTrue(upper.publication().sample().pose().z()>schedule.resumed().sample().pose().z());
        assertPostResumeFailure(schedule,r,"No actual independent SOUTH progress after manual resume");
    }

    @Test void postResumeProofRequiresBothEndpointsStrictClocksIndependentReadAndFinitePositiveDisplacement() {
        var schedule=scheduledResume(true,6.165848892158152,6.415356906947316); var r=schedule.watch().retained();
        var missing=new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),r.witnesses(),r.resume(),null);
        assertPostResumeFailure(schedule,missing,"Missing exact post-resume server lower reading");
        assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),r.witnesses(),null,r.lower()),
                "Missing exact post-resume server lower reading");
        var noUpper=r.witnesses().stream().filter(w -> w.publication().sample().tick()<299 || w.publication().sample().tick()>301).toList();
        assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),noUpper,r.resume(),r.lower()),
                "No actual independent SOUTH progress after manual resume");
        var lower=r.lower(); var scalar=lower.reading();
        for(double z:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,6.415356906947316,6.5}) {
            var reading=new ScenarioObserver.ServerReading(scalar.binding(),scalar.x(),scalar.y(),z,scalar.reach(),scalar.age(),scalar.worldTime(),scalar.serverTick());
            var changed=new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),r.witnesses(),r.resume(),new ScenarioObserver.ResumeLower(r.resume(),lower.callback(),reading));
            assertPostResumeFailure(schedule,changed,Double.isFinite(z)?"No actual independent SOUTH progress after manual resume":"Missing exact post-resume server lower reading");
        }
        for(String change:List.of("equal-callback","older-callback","equal-tick","older-tick","alias","equal-z","decreased-z","nan","infinite")) {
            var upper=r.witnesses().stream().filter(w -> w.publication().sample().tick()==299).findFirst().orElseThrow();
            var old=upper.reading();
            double z=switch(change) { case "equal-z" -> scalar.z(); case "decreased-z" -> 6.0; case "nan" -> Double.NaN; case "infinite" -> Double.POSITIVE_INFINITY; default -> old.z(); };
            var value=change.equals("alias")?scalar:new ScenarioObserver.ServerReading(old.binding(),old.x(),old.y(),z,old.reach(),old.age(),old.worldTime(),
                    change.equals("equal-tick")?scalar.serverTick():change.equals("older-tick")?scalar.serverTick()-1:old.serverTick());
            long callback=change.equals("equal-callback")?lower.callback():change.equals("older-callback")?lower.callback()-1:upper.callback();
            var points=r.witnesses().stream().map(w -> w.publication().sample().tick()>=299 && w.publication().sample().tick()<=301?
                    new ScenarioObserver.ServerWitness(w.publication(),callback,value):w).toList();
            assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),points,r.resume(),lower),
                    "No actual independent SOUTH progress after manual resume");
        }
        for(long frame:List.of(301L,302L)) {
            var marker=r.resume(); var transition=marker.transition(); var after=transition.after(); var s=after.sample();
            var moved=new ScenarioRunner.Live(new ScenarioObserver.Sample(frame,s.phase(),s.worldIdentity(),s.originalWorld(),s.pose(),s.keys(),s.paused(),s.status(),s.observations()),
                    after.client(),after.server(),after.fault(),after.pending(),after.nativeSequence(),after.nativeIdentities());
            var changedMarker=new ScenarioObserver.ResumeMarker(r.binding(),new ScenarioRunner.ClientTransition(transition.before(),moved));
            var changed=new ScenarioObserver.Watch.Retained(r.samples(),null,r.binding(),r.witnesses(),changedMarker,new ScenarioObserver.ResumeLower(changedMarker,lower.callback(),scalar));
            var changedSchedule=new ResumeSchedule(schedule.context(),schedule.watch(),moved);
            assertPostResumeFailure(changedSchedule,changed,"No actual independent SOUTH progress after manual resume");
        }
    }

    @Test void upperMustBeNativeRunningForwardSouthShiftWithClientDisplacement() {
        var schedule=scheduledResume(true,6.165848892158152,6.415356906947316); var r=schedule.watch().retained();
        for(String change:List.of("client-equal","client-decreased","client-nan","client-infinite","paused","owner","forward","activity","NEXT_ENTRY","WEST")) {
            var replacements=new java.util.IdentityHashMap<ScenarioObserver.Sample,ScenarioObserver.Sample>();
            for(var w:r.witnesses()) {
                var s=w.publication().sample(); if(s.tick()<299 || s.tick()>301) continue;
                var e=s.status().engine(); var lifecycle=e.lifecycle(); var t=e.traversal().orElseThrow(); var entry=t.entry(); var lane=entry.lane(); var p=s.pose();
                if(change.startsWith("client-")) {
                    double z=switch(change) { case "client-equal" -> schedule.resumed().sample().pose().z(); case "client-decreased" -> 6.0; case "client-nan" -> Double.NaN; default -> Double.POSITIVE_INFINITY; };
                    p=new ScenarioObserver.Pose(p.x(),p.y(),z,p.velocity(),p.eyes(),p.age(),p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable());
                }
                if(change.equals("paused") || change.equals("activity")) lifecycle=new RunLifecycle.Snapshot(change.equals("paused")?RunLifecycle.Phase.PAUSED:lifecycle.phase(),
                        lifecycle.context(),Optional.of(change.equals("activity")?RunLifecycle.Activity.BUILD:RunLifecycle.Activity.SHIFT),lifecycle.outcome());
                if(change.equals("WEST")) {
                    lane=new com.mapartrunner.core.traversal.LanePlanner.Lane(lane.id(),com.mapartrunner.core.traversal.LanePlanner.Direction.WEST,lane.start(),new BuildPlan.GridPos(0,lane.start().y(),lane.start().z()),lane.corridor(),lane.expectedFloor());
                    entry=new com.mapartrunner.core.movement.MovementPolicy.LaneEntry(lane,entry.stage(),entry.stageElapsedTicks(),entry.entryElapsedTicks(),entry.consecutiveYawAlignedTicks(),entry.supportHoldTicks(),entry.yawHoldTicks(),entry.pendingTargetIds(),entry.constantSprint());
                }
                t=new com.mapartrunner.core.run.ForwardTraversal.Snapshot(t.laneIndex(),change.equals("NEXT_ENTRY")?com.mapartrunner.core.run.ForwardTraversal.Substage.NEXT_ENTRY:t.substage(),entry,t.transitionTicks(),t.transitionRegion(),t.pass(),t.walkNoProgressTicks());
                e=new BuildEngine.Snapshot(lifecycle,e.eligibleTicks(),e.verifyTicks(),Optional.of(t),e.unfinishedTargetIds(),e.pendingTargetIds(),e.diagnostic(),e.effectResults());
                replacements.put(s,new ScenarioObserver.Sample(s.tick(),s.phase(),s.worldIdentity(),s.originalWorld(),p,change.equals("forward")?java.util.Collections.nCopies(9,false):s.keys(),s.paused(),
                        new com.mapartrunner.fabric.runtime.FabricBuildAdapter.Status(e,change.equals("owner")?BuildEngine.ControlOwner.NONE:s.status().controlOwner(),Optional.empty()),s.observations()));
            }
            var samples=r.samples().stream().map(s -> replacements.getOrDefault(s,s)).toList();
            var points=r.witnesses().stream().map(w -> new ScenarioObserver.ServerWitness(new ScenarioObserver.EndPublication(r.binding(),replacements.getOrDefault(w.publication().sample(),w.publication().sample()),w.publication().reach()),w.callback(),w.reading())).toList();
            assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(samples,null,r.binding(),points,r.resume(),r.lower()),"No actual independent SOUTH progress after manual resume");
        }
    }

    @Test void resumeProofRefusesCopiedGenerationForeignServerAndReconstructedResumeIdentity() {
        var schedule=scheduledResume(true,6.165848892158152,6.415356906947316); var r=schedule.watch().retained();
        var b=r.binding(); var marker=r.resume(); var lower=r.lower(); var scalar=lower.reading();
        var copied=new ScenarioObserver.Binding(b.generation(),b.context(),b.client(),b.server(),b.inspect(),b.scope());
        var foreignMarker=new ScenarioObserver.ResumeMarker(copied,marker.transition());
        assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,b,r.witnesses(),foreignMarker,
                new ScenarioObserver.ResumeLower(foreignMarker,lower.callback(),scalar)),"Missing exact post-resume server lower reading");
        var equalMarker=new ScenarioObserver.ResumeMarker(b,marker.transition());
        assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,b,r.witnesses(),marker,
                new ScenarioObserver.ResumeLower(equalMarker,lower.callback(),scalar)),"Missing exact post-resume server lower reading");
        for(boolean world:List.of(false,true)) {
            var server=b.server(); var changed=new ScenarioObserver.ServerBinding(server.server(),world?new Object():server.world(),
                    world?server.player():new Object(),server.playerId(),server.dimension(),true,true);
            var reading=new ScenarioObserver.ServerReading(changed,scalar.x(),scalar.y(),scalar.z(),scalar.reach(),scalar.age(),scalar.worldTime(),scalar.serverTick());
            assertPostResumeFailure(schedule,new ScenarioObserver.Watch.Retained(r.samples(),null,b,r.witnesses(),marker,
                    new ScenarioObserver.ResumeLower(marker,lower.callback(),reading)),"Missing exact post-resume server lower reading");
        }
        var s=schedule.resumed().sample();
        var copy=new ScenarioObserver.Sample(s.tick(),s.phase(),s.worldIdentity(),s.originalWorld(),s.pose(),s.keys(),s.paused(),s.status(),s.observations());
        var resumed=schedule.resumed(); var reconstructed=new ScenarioRunner.Live(copy,resumed.client(),resumed.server(),resumed.fault(),resumed.pending(),resumed.nativeSequence(),resumed.nativeIdentities());
        assertPostResumeFailure(new ResumeSchedule(schedule.context(),schedule.watch(),reconstructed),r,"Missing exact post-resume server lower reading");
    }

    private static void assertPostResumeFailure(ResumeSchedule schedule,ScenarioObserver.Watch.Retained retained,String message) {
        var failure=assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(schedule.context().runId(),retained,schedule.resumed()));
        assertEquals(message,failure.getMessage());
    }

    private record ResumeSchedule(RunLifecycle.Context context,ScenarioObserver.Watch watch,ScenarioRunner.Live resumed) { }

    private static ResumeSchedule scheduledResume(boolean earlyCallback,double lowerZ,double upperZ) {
        var engine=new ScenarioClientTransitionTest.QueueFixture();
        var context=engine.context; var watch=new ScenarioObserver.Watch();
        var samples=resumeSamples(context,6.415356906947316);
        boolean[] client={false},server={false};
        ScenarioObserver.Binding[] binding={null};
        Runnable afterResume=() -> {
            assertFalse(client[0]); // Physical server callbacks can run before the worker dispatches diagnostics.
            server[0]=true;
            try {
                if(earlyCallback) {
                    var lower=watch.beginServerRead();
                    if(lower!=null) watch.commitServer(lower,new ScenarioObserver.ServerReading(binding[0].server(),11.5,66,lowerZ,4.5,710,710,710));
                    while(fieldCallbacks(watch)<712) assertNull(watch.beginServerRead());
                } // With no early callback, the first actual post-R read is 713: no historical 710 is invented.
                for(var sample:samples) if(sample.tick()>=299 && sample.tick()<=301) watch.append(binding[0],sample,binding[0].client(),4.5);
                var upper=watch.beginServerRead(); assertNotNull(upper); assertEquals(713,upper.callback());
                watch.commitServer(upper,new ScenarioObserver.ServerReading(binding[0].server(),11.5,66,upperZ,4.5,713,713,713));
            } finally { server[0]=false; }
        };
        var host=new InspectScenarioHost(() -> 1L,() -> client[0],() -> server[0],action -> {
            client[0]=true;
            try { action.run(); } finally { client[0]=false; afterResume.run(); }
        },action -> { server[0]=true; try { action.run(); } finally { server[0]=false; } },null);
        var scope=host.beginWork(new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RUN,Path.of("simulated-resume")));
        binding[0]=witnessBinding(context,host,scope); watch.bind(binding[0]);
        for(var sample:samples) if(sample.tick()<=298) {
            watch.append(binding[0],sample,binding[0].client(),4.5);
            if(sample.phase().equals("END")) {
                var read=watch.beginServerRead(); var scalar=reading(binding[0].server(),sample,4.5,(int)sample.tick()+400);
                if(sample.tick()==298) scalar=new ScenarioObserver.ServerReading(scalar.binding(),scalar.x(),scalar.y(),6.1,scalar.reach(),scalar.age(),scalar.worldTime(),scalar.serverTick());
                watch.commitServer(read,scalar);
            }
        }
        while(fieldCallbacks(watch)<(earlyCallback?709:712)) assertNull(watch.beginServerRead());
        engine.player=new com.mapartrunner.core.movement.MovementPolicy.PlayerObservation(11.5,66,6.165848892158152,0); engine.engine.pause();
        Supplier<ScenarioRunner.Live> capture=() -> {
            var live=engine.sample(); var s=live.sample();
            return witnessLive(new ScenarioObserver.Sample(298,"TEST",s.worldIdentity(),true,s.pose(),s.keys(),false,s.status(),List.of()));
        };
        var paused=capture.get(); var diagnostics=new java.util.concurrent.atomic.AtomicInteger();
        var transition=host.inScope(scope,() -> ScenarioRunner.resumeShift(host,c -> capture.get(),c -> engine.engine.resume(context),paused,context.runId(),local ->
                host.computeOnServer(s -> {
                    assertFalse(client[0]); diagnostics.incrementAndGet();
                    var state=new com.google.gson.JsonObject(); state.addProperty("x",11.5); state.addProperty("z",upperZ);
                    return new ScenarioRunner.Live(local.sample(),local.client(),new ScenarioRunner.Side(state,716,716,716,List.of()),null,List.of(),0,"");
                }),(c,r) -> {
                    assertTrue(client[0]); assertFalse(server[0]); assertEquals(0,diagnostics.get());
                    watch.armResume(binding[0],r,binding[0].client());
                    assertSame(r,watch.retained().resume().transition());
                }));
        assertEquals(2,diagnostics.get());
        while(fieldCallbacks(watch)<726) assertNull(watch.beginServerRead());
        for(var sample:samples) if(sample.tick()>=302) {
            watch.append(binding[0],sample,binding[0].client(),4.5);
            if(sample.phase().equals("END")) {
                var read=watch.beginServerRead(); watch.commitServer(read,reading(binding[0].server(),sample,4.5,(int)sample.tick()+425));
            }
        }
        assertNull(watch.retained().failure());
        assertDoesNotThrow(() -> ScenarioRunner.evaluateTraversal(context.runId(),watch.retained(),null),"Surrounding traversal must independently pass");
        return new ResumeSchedule(context,watch,transition.after());
    }

    private static long fieldCallbacks(ScenarioObserver.Watch watch) {
        try { return field("serverCallbacks").getLong(watch); }
        catch(Exception error) { throw new AssertionError(error); }
    }

    private static List<ScenarioObserver.Sample> resumeSamples(RunLifecycle.Context context,double upperZ) {
        var lane=com.mapartrunner.core.run.ForwardTraversal.Substage.LANE;
        var endpoint=com.mapartrunner.core.run.ForwardTraversal.Substage.ENDPOINT;
        var shift=com.mapartrunner.core.run.ForwardTraversal.Substage.SHIFT;
        var entry=com.mapartrunner.core.run.ForwardTraversal.Substage.NEXT_ENTRY;
        var ends=List.of(witnessSample(context,292,lane,0,"EAST",.5,2.5,false,0,0),
                witnessSample(context,293,lane,0,"EAST",.5,2.5,true,0,0),witnessSample(context,294,lane,0,"EAST",6,2.5,true,0,0),
                witnessSample(context,295,endpoint,0,"EAST",11.5,2.5,false,0,1),witnessSample(context,296,shift,0,"SOUTH",11.5,2.5,true,0,2),
                witnessSample(context,297,shift,0,"SOUTH",11.5,6.0,true,0,3),witnessSample(context,298,shift,0,"SOUTH",11.5,6.165848892158152,true,0,4),
                witnessSample(context,299,shift,0,"SOUTH",11.5,upperZ,true,0,5),witnessSample(context,300,shift,0,"SOUTH",11.5,upperZ,true,0,6),
                witnessSample(context,301,shift,0,"SOUTH",11.5,upperZ,true,0,7),witnessSample(context,302,entry,1,"WEST",11.5,7.5,false,0,8),
                witnessSample(context,303,entry,1,"WEST",11.5,7.5,false,1,9),witnessSample(context,304,lane,1,"WEST",11.5,7.5,false,0,0),
                witnessSample(context,305,lane,1,"WEST",11.5,7.5,true,0,0),witnessSample(context,306,lane,1,"WEST",5,7.5,true,0,0));
        var result=new java.util.ArrayList<ScenarioObserver.Sample>(); var previous=ends.getFirst();
        for(var end:ends) {
            var e=previous.status().engine();
            var before=new BuildEngine.Snapshot(e.lifecycle(),end.tick()-1,0,e.traversal(),List.of(),Set.of(),Optional.empty(),List.of());
            result.add(new ScenarioObserver.Sample(end.tick(),"START",12,true,previous.pose(),previous.keys(),false,
                    new com.mapartrunner.fabric.runtime.FabricBuildAdapter.Status(before,previous.status().controlOwner(),Optional.empty()),List.of()));
            result.add(end); previous=end;
        }
        return result;
    }

    private static List<ScenarioObserver.Sample> witnessSamples(RunLifecycle.Context context) {
        return witnessSamples(context,false);
    }

    private static List<ScenarioObserver.Sample> witnessSamples(RunLifecycle.Context context,boolean lateShift) {
        var ends=new java.util.ArrayList<ScenarioObserver.Sample>();
        var lane=com.mapartrunner.core.run.ForwardTraversal.Substage.LANE;
        var endpoint=com.mapartrunner.core.run.ForwardTraversal.Substage.ENDPOINT;
        var shift=com.mapartrunner.core.run.ForwardTraversal.Substage.SHIFT;
        var entry=com.mapartrunner.core.run.ForwardTraversal.Substage.NEXT_ENTRY;
        ends.add(witnessSample(context,1,lane,0,"EAST",.5,2.5,false,0,0));
        ends.add(witnessSample(context,2,lane,0,"EAST",.5,2.5,true,0,0));
        ends.add(witnessSample(context,3,lane,0,"EAST",6,2.5,true,0,0));
        ends.add(witnessSample(context,4,endpoint,0,"EAST",11.5,2.5,false,0,1));
        ends.add(witnessSample(context,5,shift,0,"SOUTH",11.5,2.5,true,0,2));
        ends.add(witnessSample(context,6,shift,0,"SOUTH",11.5,3,true,0,3));
        int offset=lateShift?1:0;
        if(lateShift) ends.add(witnessSample(context,7,shift,0,"SOUTH",11.5,7.5,true,0,4));
        ends.add(witnessSample(context,7+offset,entry,1,"WEST",11.5,7.5,false,0,4+offset));
        ends.add(witnessSample(context,8+offset,entry,1,"WEST",11.5,7.5,false,1,5+offset));
        ends.add(witnessSample(context,9+offset,lane,1,"WEST",11.5,7.5,false,0,0));
        ends.add(witnessSample(context,10+offset,lane,1,"WEST",11.5,7.5,true,0,0));
        ends.add(witnessSample(context,11+offset,lane,1,"WEST",5,7.5,true,0,0));
        var result=new java.util.ArrayList<ScenarioObserver.Sample>();
        ScenarioObserver.Sample previous=ends.getFirst();
        for(var end:ends) {
            var e=previous.status().engine();
            var before=new BuildEngine.Snapshot(e.lifecycle(),end.tick()-1,0,e.traversal(),List.of(),Set.of(),Optional.empty(),List.of());
            result.add(new ScenarioObserver.Sample(end.tick(),"START",12,true,previous.pose(),previous.keys(),false,
                    new com.mapartrunner.fabric.runtime.FabricBuildAdapter.Status(before,previous.status().controlOwner(),Optional.empty()),List.of()));
            result.add(end); previous=end;
        }
        return result;
    }

    private static ScenarioObserver.Sample witnessSample(RunLifecycle.Context context,long frame,
            com.mapartrunner.core.run.ForwardTraversal.Substage substage,int index,String direction,double x,double z,
            boolean forward,int aligned,int transition) {
        var d=com.mapartrunner.core.traversal.LanePlanner.Direction.valueOf(direction);
        var corridor=new com.mapartrunner.core.traversal.LanePlanner.Corridor(0,11,0,9);
        var floor=new com.mapartrunner.core.traversal.LanePlanner.ExpectedFloor(java.util.Map.of());
        var start=new BuildPlan.GridPos(direction.equals("WEST")?11:direction.equals("SOUTH")?11:0,0,direction.equals("EAST")?2:direction.equals("SOUTH")?2:7);
        var finish=new BuildPlan.GridPos(direction.equals("WEST")?0:11,0,direction.equals("EAST")?2:7);
        var lane=new com.mapartrunner.core.traversal.LanePlanner.Lane(index,d,start,finish,corridor,floor);
        var stage=substage==com.mapartrunner.core.run.ForwardTraversal.Substage.LANE
                || substage==com.mapartrunner.core.run.ForwardTraversal.Substage.SHIFT
                ?com.mapartrunner.core.movement.MovementPolicy.Stage.WALK:com.mapartrunner.core.movement.MovementPolicy.Stage.ENTER_YAW;
        var entry=new com.mapartrunner.core.movement.MovementPolicy.LaneEntry(lane,stage,aligned,aligned,aligned,0,0,Set.of(),false);
        var traversal=new com.mapartrunner.core.run.ForwardTraversal.Snapshot(index,substage,entry,transition,Optional.empty(),
                com.mapartrunner.core.run.ForwardTraversal.Pass.FORWARD,0);
        var lifecycle=new RunLifecycle.Snapshot(RunLifecycle.Phase.RUNNING,Optional.of(context),
                Optional.of(substage==com.mapartrunner.core.run.ForwardTraversal.Substage.SHIFT?RunLifecycle.Activity.SHIFT:RunLifecycle.Activity.BUILD),Optional.empty());
        var engine=new BuildEngine.Snapshot(lifecycle,frame,0,Optional.of(traversal),List.of(),Set.of(),Optional.empty(),List.of());
        var pose=new ScenarioObserver.Pose(x,66,z,net.minecraft.util.math.Vec3d.ZERO,net.minecraft.util.math.Vec3d.ZERO,
                (int)frame,frame,(float)d.yawDegrees(),(float)d.yawDegrees(),(float)d.yawDegrees(),false,false,1,64,64);
        return new ScenarioObserver.Sample(frame,"END",12,true,pose,List.of(forward,false,false,false,false,false,false,false,false),false,
                new com.mapartrunner.fabric.runtime.FabricBuildAdapter.Status(engine,BuildEngine.ControlOwner.NATIVE,Optional.empty()),List.of());
    }

    private static ScenarioRunner.Live witnessLive(ScenarioObserver.Sample sample) {
        var state=new com.google.gson.JsonObject(); state.addProperty("x",sample.pose().x()); state.addProperty("z",sample.pose().z());
        var side=new ScenarioRunner.Side(state,(int)sample.tick(),sample.tick(),(int)sample.tick(),List.of());
        return new ScenarioRunner.Live(sample,side,side,null,List.of(),0,"");
    }

    private static ScenarioObserver.Binding witnessBinding(RunLifecycle.Context context,Inspect host,Scope scope) {
        var player=UUID.randomUUID();
        return new ScenarioObserver.Binding(UUID.randomUUID(),context,
                new ScenarioObserver.ClientBinding(new Object(),new Object(),new Object(),new Object(),player,context.dimension(),true,true,true),
                new ScenarioObserver.ServerBinding(new Object(),new Object(),new Object(),player,context.dimension(),true,true),host,scope);
    }

    private static ScenarioObserver.Watch pairedWatch(RunLifecycle.Context context,List<ScenarioObserver.Sample> samples,Inspect host,Scope scope) {
        return batchedWatch(context,samples,host,scope,samples.stream().filter(s -> s.phase().equals("END"))
                .map(ScenarioObserver.Sample::tick).collect(java.util.stream.Collectors.toSet()));
    }

    private static ScenarioObserver.Watch batchedWatch(RunLifecycle.Context context,List<ScenarioObserver.Sample> samples,
                                                       Inspect host,Scope scope,Set<Long> drainFrames) {
        var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,host,scope); watch.bind(binding);
        for(var sample:samples) {
            watch.append(binding,sample,binding.client(),4.5);
            if(sample.phase().equals("END") && drainFrames.contains(sample.tick())) {
                var read=watch.beginServerRead();
                assertNotNull(read);
                watch.commitServer(read,reading(binding.server(),sample,4.5,(int)sample.tick()+100));
            }
        }
        assertNull(watch.retained().failure());
        return watch;
    }

    private static ScenarioObserver.ServerReading reading(ScenarioObserver.ServerBinding binding,ScenarioObserver.Sample sample,double reach,int tick) {
        return new ScenarioObserver.ServerReading(binding,sample.pose().x(),sample.pose().y(),sample.pose().z(),reach,
                (int)sample.tick()+20,sample.tick()+200,tick);
    }

    private static void assertRejected(ScenarioObserver.Watch.Retained original,List<ScenarioObserver.ServerWitness> witnesses,
                                       List<ScenarioObserver.Sample> samples) {
        var changed=new ScenarioObserver.Watch.Retained(samples,original.failure(),original.binding(),witnesses,null,null);
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(original.binding().context().runId(),changed,null));
    }

    private static List<ScenarioObserver.ServerWitness> changeWitness(ScenarioObserver.Watch.Retained retained,long frame,
            UnaryOperator<ScenarioObserver.ServerWitness> change) {
        return retained.witnesses().stream().map(w -> w.publication().sample().tick()==frame?change.apply(w):w).toList();
    }

    @Test void evaluatorRequiresIndependentServerEndpointAndCannotSubstituteShiftGeometry() {
        var context=witnessContext(); var r=pairedWatch(context,witnessSamples(context),null,null).retained();
        assertRejected(r,r.witnesses().stream().filter(w -> w.publication().sample().tick()!=4).toList(),r.samples());
        for(double x:List.of(10.7,11.249,11.751)) {
            var wrong=changeWitness(r,4,w -> new ScenarioObserver.ServerWitness(w.publication(),w.callback(),
                    new ScenarioObserver.ServerReading(w.reading().binding(),x,66,2.5,4.5,24,204,w.reading().serverTick())));
            assertRejected(r,wrong,r.samples());
        }
        var wrongZ=r.witnesses().stream().map(w -> w.publication().sample().tick()==7 || w.publication().sample().tick()==8?
                new ScenarioObserver.ServerWitness(w.publication(),w.callback(),
                    new ScenarioObserver.ServerReading(w.reading().binding(),11.5,66,7.249,4.5,27,207,w.reading().serverTick())):w).toList();
        assertRejected(r,wrongZ,r.samples());
    }

    @Test void evaluatorRejectsWrongBindingRunContextAndReorderedClocks() {
        var context=witnessContext(); var r=pairedWatch(context,witnessSamples(context),null,null).retained();
        var foreign=witnessBinding(context,null,null);
        var forged=changeWitness(r,4,w -> new ScenarioObserver.ServerWitness(
                new ScenarioObserver.EndPublication(foreign,w.publication().sample(),4.5),w.callback(),w.reading()));
        assertRejected(r,forged,r.samples());
        var wrongPlayer=new ScenarioObserver.ServerBinding(r.binding().server().server(),r.binding().server().world(),new Object(),
                r.binding().server().playerId(),context.dimension(),true,true);
        assertRejected(r,changeWitness(r,4,w -> new ScenarioObserver.ServerWitness(w.publication(),w.callback(),
                reading(wrongPlayer,w.publication().sample(),4.5,w.reading().serverTick()))),r.samples());
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(UUID.randomUUID(),r,null));
        var samples=new java.util.ArrayList<>(r.samples());
        var original=samples.get(7);
        var changed=witnessSample(witnessContext(),4,com.mapartrunner.core.run.ForwardTraversal.Substage.ENDPOINT,0,"EAST",11.5,2.5,false,0,1);
        samples.set(7,changed);
        assertRejected(r,r.witnesses(),samples);
        for(int kind=0;kind<3;kind++) {
            final int clock=kind;
            var bad=changeWitness(r,4,w -> {
                var previous=r.witnesses().get(2);
                return new ScenarioObserver.ServerWitness(clock==0?previous.publication():w.publication(),
                        clock==1?previous.callback():w.callback(),clock==2?
                        reading(w.reading().binding(),w.publication().sample(),4.5,previous.reading().serverTick()):w.reading());
            });
            assertRejected(r,bad,r.samples());
        }
        var reversed=new java.util.ArrayList<>(r.witnesses()); java.util.Collections.swap(reversed,3,4);
        assertRejected(r,reversed,r.samples());
        var duplicate=new java.util.ArrayList<>(r.samples()); duplicate.add(8,original);
        assertRejected(r,r.witnesses(),duplicate);
    }

    @Test void evaluatorRequiresNativeForwardEachReachAndControllerWestInputs() {
        var context=witnessContext(); var dense=witnessSamples(context); var r=pairedWatch(context,dense,null,null).retained();
        // Per-side actual reach is independently checked at and below the displacement boundary.
        for(boolean east:List.of(false,true)) for(boolean client:List.of(false,true)) for(double excess:List.of(0.,.1)) {
            double reach=(east?5.5:6.5)+excess;
            var bad=r.witnesses().stream().map(w -> {
                if(w.publication().sample().tick()!=(east?2:10) && w.publication().sample().tick()!=(east?3:11)) return w;
                return new ScenarioObserver.ServerWitness(client?new ScenarioObserver.EndPublication(r.binding(),w.publication().sample(),reach):w.publication(),
                        w.callback(),client?w.reading():reading(w.reading().binding(),w.publication().sample(),reach,w.reading().serverTick()));
            }).toList();
            assertRejected(r,bad,r.samples());
        }
        var held=dense.stream().map(s -> {
            if(!s.phase().equals("END") || s.tick()!=2 && s.tick()!=3) return s;
            return new ScenarioObserver.Sample(s.tick(),s.phase(),s.worldIdentity(),s.originalWorld(),s.pose(),
                    List.of(false,false,false,false,false,false,false,false,false),s.paused(),s.status(),s.observations());
        }).toList();
        var heldR=pairedWatch(context,held,null,null).retained(); assertRejected(heldR,heldR.witnesses(),heldR.samples());
        for(long frame:List.of(8L,9L)) {
            var missing=r.samples().stream().filter(s -> s.tick()!=frame).toList();
            var points=r.witnesses().stream().filter(w -> w.publication().sample().tick()!=frame).toList();
            assertRejected(r,points,missing);
        }
        var reverseInputs=new java.util.ArrayList<>(r.samples());
        java.util.Collections.swap(reverseInputs,15,17); assertRejected(r,r.witnesses(),reverseInputs);
        var schedule=scheduledResume(true,6.165848892158152,6.415356906947316);
        var resumed=schedule.resumed(); var history=schedule.watch().retained(); var run=schedule.context().runId();
        assertDoesNotThrow(() -> ScenarioRunner.evaluateTraversal(run,history,resumed));
        var late=witnessLive(history.samples().stream().filter(s -> s.tick()==301 && s.phase().equals("END")).findFirst().orElseThrow());
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(run,history,late));
        // Late diagnostics cannot replace L; client-only post-R progress still fails against the actual server lower.
        var clientOnly=scheduledResume(true,6.415356906947316,6.415356906947316);
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(clientOnly.context().runId(),clientOnly.watch().retained(),clientOnly.resumed()));
    }

    @Test void watchFreezeLinearizesAgainstInflightReadsAndMoreThan6000LateCallbacks() throws Exception {
        var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),null,null);
        var binding=watch.retained().binding();
        watch.append(binding,laterEnd(context,12),binding.client(),4.5);
        watch.append(binding,laterEnd(context,13),binding.client(),4.5);
        var started=new java.util.concurrent.CountDownLatch(1); var finish=new java.util.concurrent.CountDownLatch(1);
        var inFlight=new java.util.concurrent.atomic.AtomicReference<ScenarioObserver.ServerRead>();
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var server=new Thread(() -> {
            try {
                var read=watch.beginServerRead(); inFlight.set(read); started.countDown();
                if(!finish.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Freeze test did not release server read");
                watch.commitServer(read,reading(read.publications().getFirst().binding().server(),read.publications().getFirst().sample(),4.5,999));
                watch.failRead(read,new AssertionError("late read must not mutate failure"));
            } catch(Throwable failure) { error.set(failure); }
        },"simulated-server-read");
        server.start();
        ScenarioObserver.Watch.Retained archived;
        try {
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(inFlight.get());
            assertEquals(2,inFlight.get().publications().size());
            watch.append(binding,laterEnd(context,14),binding.client(),4.5);
            assertEquals(1,((List<?>)field("pending").get(watch)).size());
            watch.freeze(); archived=watch.retained();
        } finally { finish.countDown(); server.join(2000); }
        assertFalse(server.isAlive()); assertNull(error.get());
        var read=inFlight.get();
        long callbacks=field("serverCallbacks").getLong(watch);
        for(int n=0;n<6002;n++) {
            assertNull(watch.beginServerRead()); watch.recordServer(null);
            watch.append(archived.binding(),read.publications().getFirst().sample(),archived.binding().client(),4.5);
        }
        assertEquals(archived,watch.retained());
        assertEquals(callbacks,field("serverCallbacks").getLong(watch));
        assertNull(field("inFlight").get(watch)); assertTrue(((List<?>)field("pending").get(watch)).isEmpty());
        watch.clear();
        var fresh=witnessBinding(witnessContext(),null,null); watch.bind(fresh);
        var freshEnd=witnessSamples(fresh.context()).get(7); watch.append(fresh,freshEnd,fresh.client(),4.5);
        var freshRead=watch.beginServerRead(); assertNotNull(freshRead); assertEquals(1,freshRead.callback());
        watch.commitServer(read,reading(archived.binding().server(),read.publications().getFirst().sample(),4.5,1000));
        watch.failRead(read,new AssertionError("old completion cannot consume fresh claim"));
        assertSame(freshRead,field("inFlight").get(watch));
        assertTrue(watch.retained().witnesses().isEmpty()); assertSame(fresh,watch.retained().binding());
        watch.commitServer(freshRead,reading(fresh.server(),freshEnd,4.5,101));
        assertEquals(1,watch.retained().witnesses().size());
        assertEquals(11,archived.witnesses().size());
        assertThrows(UnsupportedOperationException.class,() -> archived.witnesses().clear());
    }

    @Test void pairedResumeRequiresServerReadAfterIndependentServerAnchor() {
        var context=witnessContext(); var r=pairedWatch(context,witnessSamples(context),null,null).retained();
        var resume=witnessLive(r.samples().get(9));
        for(int serverTick:List.of(106,107)) {
            var independent=new ScenarioRunner.Side(resume.server().state(),25,205,serverTick,List.of());
            var anchor=new ScenarioRunner.Live(resume.sample(),resume.client(),independent,null,List.of(),0,"");
            // Client frame6 and server z3 exceed resume frame5/z2.5, but the server read precedes/equal anchor.
            assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(context.runId(),r,anchor));
        }
    }

    @Test void automaticObserverCloseDisarmsPairedReadsWithoutClearingRetainedBinding() {
        var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),null,null);
        var binding=watch.retained().binding(); watch.append(binding,laterEnd(context,12),binding.client(),4.5);
        var read=watch.beginServerRead(); assertNotNull(read);
        watch.append(binding,laterEnd(context,13),binding.client(),4.5);
        watch.closeWorld(); var archived=watch.retained();
        assertNotNull(archived.binding());
        watch.commitServer(read,reading(archived.binding().server(),read.publications().getFirst().sample(),4.5,999));
        assertNull(watch.beginServerRead()); assertEquals(archived,watch.retained());
    }

    @Test void uniqueEndPublicationsBoundCommittedPendingAndClaimedRowsByCombinedSampleCap() throws Exception {
        var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
        ScenarioObserver.ServerRead claim=null;
        for(int n=1;n<=6000;n++) {
            watch.append(binding,laterEnd(context,n),binding.client(),4.5);
            if(n%2000==0) {
                int claimed=claim==null?0:claim.publications().size();
                assertEquals(n,watch.retained().witnesses().size()+((List<?>)field("pending").get(watch)).size()+claimed);
                if(claim!=null) watch.commitServer(claim,reading(binding.server(),claim.publications().getLast().sample(),4.5,n));
                claim=watch.beginServerRead(); assertNotNull(claim);
                assertEquals(2000,claim.publications().size());
            }
        }
        watch.commitServer(claim,reading(binding.server(),claim.publications().getLast().sample(),4.5,6200));
        assertEquals(6000,watch.retained().witnesses().size());
        assertEquals(6000,watch.retained().samples().size()); assertNull(watch.retained().failure());
        assertEquals(6000,watch.retained().witnesses().stream().map(w -> w.publication().sample().tick()).distinct().count());
        assertNull(watch.beginServerRead());
        watch.append(binding,laterEnd(context,6001),binding.client(),4.5);
        var failure=watch.retained().failure(); assertInstanceOf(AssertionError.class,failure);
        assertEquals("Client observer exceeded 6000 samples",failure.getMessage());
        watch.failRead(claim,new IllegalStateException("second cause")); assertSame(failure,watch.retained().failure());
        watch.freeze(); var retained=watch.retained();
        for(int n=0;n<6002;n++) watch.recordServer(null);
        assertEquals(retained,watch.retained());
    }

    private static ScenarioObserver.Sample laterEnd(RunLifecycle.Context context,long frame) {
        return witnessSample(context,frame,com.mapartrunner.core.run.ForwardTraversal.Substage.LANE,1,"WEST",5,7.5,true,0,0);
    }

    @Test void watchRejectsClientAndServerIdentityLossAndNeverPublishesStartOrTest() {
        for(String side:List.of("client-world","client-player","handler","connection","client-uuid","client-dimension","client-thread",
                "server","server-world","server-player","server-uuid","server-dimension","server-thread","member")) {
            var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var b=witnessBinding(context,null,null); watch.bind(b);
            var sample=witnessSamples(context).get(7); var c=b.client(); var s=b.server();
            watch.append(b,new ScenarioObserver.Sample(sample.tick(),"START",12,true,sample.pose(),sample.keys(),false,sample.status(),List.of()),c,4.5);
            assertNull(watch.beginServerRead());
            watch.append(b,new ScenarioObserver.Sample(sample.tick(),"TEST",12,true,sample.pose(),sample.keys(),false,sample.status(),List.of()),c,4.5);
            assertNull(watch.beginServerRead());
            var client=new ScenarioObserver.ClientBinding(side.equals("client-world")?new Object():c.world(),side.equals("client-player")?new Object():c.player(),
                    side.equals("handler")?new Object():c.handler(),side.equals("connection")?new Object():c.connection(),
                    side.equals("client-uuid")?UUID.randomUUID():c.playerId(),side.equals("client-dimension")?"foreign":c.dimension(),!side.equals("client-thread"),true,true);
            watch.append(b,sample,client,4.5);
            if(side.startsWith("server") || side.equals("member")) {
                var read=watch.beginServerRead(); assertNotNull(read);
                var server=new ScenarioObserver.ServerBinding(side.equals("server")?new Object():s.server(),side.equals("server-world")?new Object():s.world(),
                        side.equals("server-player")?new Object():s.player(),side.equals("server-uuid")?UUID.randomUUID():s.playerId(),
                        side.equals("server-dimension")?"foreign":s.dimension(),!side.equals("server-thread"),!side.equals("member"));
                watch.commitServer(read,reading(server,sample,4.5,101));
            }
            assertNotNull(watch.retained().failure(),side); assertTrue(watch.retained().witnesses().isEmpty(),side);
        }
    }

    @Test void endPublicationRequiresOpenConnectionAndHandlerWorldForAutomaticAndInspect() {
        assertAll(
                () -> rejectLostLiveClientFacts(false,false,true),
                () -> rejectLostLiveClientFacts(false,true,false),
                () -> rejectLostLiveClientFacts(true,false,true),
                () -> rejectLostLiveClientFacts(true,true,false));
    }

    private void rejectLostLiveClientFacts(boolean inspect,boolean connectionOpen,boolean handlerWorldMatches) {
        var host=inspect?new InspectScenarioHost(() -> 1L,() -> false,() -> false,
                a -> fail("No client dispatch"),a -> fail("No server dispatch"),null):null;
        var scope=inspect?host.beginWork(new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RUN,directory)):null;
        if(inspect) assertTrue(host.observationScopeValid(scope));
        var context=witnessContext(); var watch=new ScenarioObserver.Watch();
        var binding=witnessBinding(context,host,scope); watch.bind(binding); var client=binding.client();
        var samples=witnessSamples(context); var previous=samples.get(5); var next=samples.get(7);
        watch.append(binding,previous,client,4.5); var inFlight=watch.beginServerRead(); assertNotNull(inFlight);
        var invalid=new ScenarioObserver.ClientBinding(client.world(),client.player(),client.handler(),client.connection(),
                client.playerId(),client.dimension(),client.onThread(),connectionOpen,handlerWorldMatches);
        watch.append(binding,next,invalid,4.5);
        var first=watch.retained().failure(); assertNotNull(first,"same references lost live connection/world binding");
        assertNull(watch.beginServerRead()); assertTrue(watch.retained().witnesses().isEmpty());
        watch.commitServer(inFlight,reading(binding.server(),previous,4.5,101));
        watch.failRead(inFlight,new AssertionError("later server failure must not replace client binding loss"));
        watch.append(binding,next,client,4.5);
        assertSame(first,watch.retained().failure()); assertNull(watch.beginServerRead());
        assertTrue(watch.retained().witnesses().isEmpty());
        if(inspect) assertTrue(host.observationScopeValid(scope)); // Refusal is the per-END binding, not scope cancellation.
    }

    @Test void watchRejectsEveryChangedRunContextFieldAndOldGenerationPublication() {
        var context=witnessContext();
        var alternatives=List.of(
                new RunLifecycle.Context(UUID.randomUUID(),context.connectionId(),context.worldId(),context.dimension(),context.planFingerprint(),context.origin(),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),"foreign",context.worldId(),context.dimension(),context.planFingerprint(),context.origin(),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),context.connectionId(),"foreign",context.dimension(),context.planFingerprint(),context.origin(),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),context.connectionId(),context.worldId(),"foreign",context.planFingerprint(),context.origin(),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),context.connectionId(),context.worldId(),context.dimension(),"foreign",context.origin(),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),context.connectionId(),context.worldId(),context.dimension(),context.planFingerprint(),new BuildPlan.GridPos(1,65,0),context.settingsFingerprint()),
                new RunLifecycle.Context(context.runId(),context.connectionId(),context.worldId(),context.dimension(),context.planFingerprint(),context.origin(),"foreign"));
        for(var foreign:alternatives) {
            var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
            var sample=witnessSamples(foreign).get(7); watch.append(binding,sample,binding.client(),4.5);
            assertNotNull(watch.retained().failure()); assertNull(watch.beginServerRead());
        }
        var watch=new ScenarioObserver.Watch(); var old=witnessBinding(context,null,null); watch.bind(old); watch.freeze(); watch.clear();
        var fresh=witnessBinding(context,null,null); watch.bind(fresh); var retained=watch.retained();
        watch.append(old,witnessSamples(context).get(7),old.client(),4.5);
        assertEquals(retained,watch.retained()); assertNotEquals(old.generation(),fresh.generation());
    }

    @Test void realHostObservationCheckDoesNotWaitForHostLock() throws Exception {
        var host=new InspectScenarioHost(() -> 1L,() -> false,() -> false,a -> fail("No queue"),a -> fail("No queue"),null);
        var op=new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RUN,directory); var work=host.beginWork(op);
        var field=InspectScenarioHost.class.getDeclaredField("lock"); field.setAccessible(true);
        var lock=(java.util.concurrent.locks.ReentrantLock)field.get(host);
        var checked=new java.util.concurrent.CountDownLatch(1); var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        var reader=new Thread(() -> { accepted.set(host.observationScopeValid(work)); checked.countDown(); },"scope-observer");
        lock.lock();
        try { reader.start(); assertTrue(checked.await(1,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(accepted.get()); }
        finally { lock.unlock(); reader.join(2000); }
        assertFalse(reader.isAlive());
    }

    @Test void realHostObservationScopeRejectsExpiryCancellationReplacementAndLookalikes() {
        for(String invalidation:List.of("expiry","cancel","replacement","stopping")) {
            var now=new java.util.concurrent.atomic.AtomicLong(1);
            var nativeThread=ThreadLocal.withInitial(() -> false);
            var host=new InspectScenarioHost(now::get,nativeThread::get,() -> false,a -> fail("No client dispatch"),a -> fail("No server dispatch"),null);
            var operation=new Operation(UUID.randomUUID(),1,UUID.randomUUID(),Command.RUN,directory);
            var work=host.beginWork(operation);
            assertSame(work,host.inScope(work,() -> host.requireScope(operation,Kind.WORK)));
            assertTrue(host.observationScopeValid(work));
            assertFalse(host.observationScopeValid(new Scope(operation,Kind.WORK,work.deadlineNanos())));
            assertFalse(host.observationScopeValid(null));
            var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),host,work);
            var legitimate=watch.retained().binding();
            var lookalike=new ScenarioObserver.Binding(UUID.randomUUID(),context,legitimate.client(),legitimate.server(),host,
                    new Scope(operation,Kind.WORK,work.deadlineNanos()));
            assertThrows(IllegalStateException.class,() -> new ScenarioObserver.Watch().bind(lookalike));
            watch.append(legitimate,laterEnd(context,12),legitimate.client(),4.5);
            var read=watch.beginServerRead(); assertNotNull(read);
            switch(invalidation) {
                case "expiry" -> now.set(work.deadlineNanos());
                case "cancel" -> host.cancelSession(new IllegalStateException("cancelled"));
                case "replacement" -> {
                    var cleanup=host.beginCleanup(work,null);
                    assertFalse(host.observationScopeValid(cleanup));
                    host.settleCleanup(cleanup,new FinalOutcome(operation,Verdict.SUCCESS,CleanupStatus.SETTLED));
                    var next=new Operation(operation.sessionId(),2,UUID.randomUUID(),Command.RUN,directory);
                    assertTrue(host.observationScopeValid(host.beginWork(next)));
                }
                case "stopping" -> { nativeThread.set(true); try { host.onStopping(null); } finally { nativeThread.remove(); } }
            }
            var retained=watch.retained();
            assertFalse(host.observationScopeValid(work)); assertNull(watch.beginServerRead());
            watch.commitServer(read,reading(retained.binding().server(),read.publications().getFirst().sample(),4.5,999));
            watch.append(retained.binding(),read.publications().getFirst().sample(),retained.binding().client(),4.5);
            assertEquals(retained,watch.retained());
        }
    }

    @Test void commandAuthorityRefusalHasNoOrdinalEvidenceOrTransportEffects() throws Exception {
        var host=new InspectTransport(); var runner=runner(host);
        var session=runner.inspectSessionId();
        assertThrows(AssertionError.class,() -> runner.requireInspectCommandWorld(null));
        assertEquals(0,host.effects); assertEquals(0,runnerField("inspectOrdinal").getInt(runner));
        assertNull(runnerField("inspectOperation").get(runner)); assertFalse(Files.exists(rootsDirectory(runner).evidence()));
        var operation=runner.beginInspectOperation(Command.RUN);
        assertEquals(session,operation.sessionId()); assertEquals(1,operation.ordinal());
    }

    private static ScenarioRunner.Roots rootsDirectory(ScenarioRunner runner) throws Exception {
        return (ScenarioRunner.Roots)runnerField("roots").get(runner);
    }

    @Test void realStoppingHostResultsAreConsumedOnceBeforeWorkerSealsEvidence() throws Exception {
        var nativeThread=ThreadLocal.withInitial(() -> false); var outcomes=new java.util.ArrayList<FinalOutcome>();
        var host=new InspectScenarioHost(() -> 1L,nativeThread::get,() -> false,
                action -> fail("Stopped host must not enqueue"),action -> fail("Stopped server must not enqueue"),outcomes::add);
        var attempts=new int[2]; var watch=new ScenarioObserver.Watch();
        var runner=new ScenarioRunner(host,watch,roots(),() -> attempts[0]++,() -> attempts[1]++);
        var op=runner.beginInspectOperation(Command.RUN); var work=host.beginWork(op);
        var original=new IllegalStateException("original work failure"); var cleanup=host.beginCleanup(work,original);
        runnerField("worldCreated").setBoolean(runner,true);
        nativeThread.set(true);
        try { host.onStopping(null); } finally { nativeThread.remove(); }
        // No installed native binding: actual Runner refuses all release/capture effects, freezes its old Watch.
        assertTrue(field("frozen").getBoolean(watch));
        assertSame(original,assertThrows(IllegalStateException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,original))));
        assertArrayEquals(new int[]{0,0},attempts); assertEquals(CleanupStatus.UNRESOLVED,outcomes.getFirst().cleanup());
        assertEquals(Verdict.FAILURE,outcomes.getFirst().verdict());
        var closed=closedFileHashes(op.evidenceDirectory());
        nativeThread.set(true); try { host.onStopping(null); } finally { nativeThread.remove(); }
        assertEquals(closed,closedFileHashes(op.evidenceDirectory())); assertTrue(runner.lastInspectCompletion().isEmpty());
    }

    @Test void startupReservesOrdinalZeroAndInitializationBindsItsScopeInternally() throws Exception {
        var host=new InspectTransport(); var runner=runner(host);
        invokeRunner(runner,"reserveInspectStartup");
        assertEquals(Kind.STARTUP,host.current.kind()); assertEquals(0,host.current.operation().ordinal());
        assertEquals(Command.STARTUP,host.current.operation().command());
        assertTrue(Files.isRegularFile(host.current.operation().evidenceDirectory().resolve("operation.json")));
        assertThrows(AssertionError.class,() -> runner.initializeInspectWorld(() -> fail("Publication before binding"))); // Native double refuses first client query.
        assertEquals(1,host.effects); assertNull(host.bound);
        assertThrows(AssertionError.class,() -> invokeRunner(runner,"reserveInspectStartup"));
    }

    @Test void startupPublicationFailurePreservesClosedFilesAndNeverCreatesOrdinaryLastOrShutdownLineage() throws Exception {
        for(boolean linked : List.of(false,true)) {
            var host=new InspectTransport(); var runner=runner(host); invokeRunner(runner,"reserveInspectStartup");
            var startup=host.current; var op=startup.operation();
            Files.writeString(op.evidenceDirectory().resolve("completion.json"),"closed owned binding evidence");
            var oldFiles=closedFileHashes(op.evidenceDirectory()); var receipt=directory.resolve("ready-"+linked);
            var original=new IllegalStateException("publication failed "+linked);
            Runnable publish=() -> {
                assertSame(startup,host.requireScope(op,Kind.STARTUP));
                if(linked) assertDoesNotThrow(() -> Files.writeString(receipt,"visible immutable READY"));
                throw original;
            };
            // Synthetic completed-binding envelope exercises production publication/failure ordering only.
            Runnable initialization=() -> invokeRunner(runner,"publishInspectStartup",publish);
            assertSame(original,assertThrows(IllegalStateException.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization)));
            assertEquals(linked,Files.exists(receipt)); if(linked) assertEquals("visible immutable READY",Files.readString(receipt));
            for(var entry:oldFiles.entrySet()) assertEquals(entry.getValue(),closedFileHashes(op.evidenceDirectory()).get(entry.getKey()));
            assertEquals(Verdict.FAILURE,host.outcome.verdict()); assertEquals(CleanupStatus.SETTLED,host.outcome.cleanup());
            assertSame(op,host.outcome.operation()); assertTrue(runner.lastInspectCompletion().isEmpty());
            assertEquals(0,host.shutdownScopes);
            for(var command:List.of(Command.RESET,Command.RUN,Command.CLOSE)) assertThrows(AssertionError.class,() -> runner.beginInspectOperation(command));
            assertThrows(AssertionError.class,runner::close);
            assertThrows(AssertionError.class,() -> runner.failInspectAdmission(op,new Exception("No N1 reclassification")));
            assertThrows(AssertionError.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization));
            assertEquals(1,host.settlements);
        }
    }

    @Test void startupSettlementPrecedesReturnAndLaterErrorCannotReviveItsPermission() throws Exception {
        var host=new InspectTransport(); var runner=runner(host); invokeRunner(runner,"reserveInspectStartup");
        var startup=host.current; var original=new IllegalStateException("after startup settlement");
        Runnable initialization=() -> {
            invokeRunner(runner,"publishInspectStartup",() -> assertSame(startup,host.current));
            assertNull(host.current); throw original;
        };
        assertSame(original,assertThrows(IllegalStateException.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization)));
        assertEquals(0,host.settlements); assertNull(host.outcome); assertEquals(0,host.effects); assertEquals(0,host.shutdownScopes);
        assertTrue(runner.lastInspectCompletion().isEmpty()); assertThrows(AssertionError.class,runner::close);
        assertThrows(AssertionError.class,() -> runner.beginInspectOperation(Command.RUN));
    }

    @Test void prospectiveSaveRejectsForeignNamesParentsAndExistingEntries() throws Exception {
        var roots = roots(); Files.createDirectory(roots.saves());
        var session = UUID.randomUUID();
        var prospective = roots.saves().resolve("mapart-inspect-" + session);
        ScenarioRunner.requireProspectiveInspectSave(roots, session, prospective);
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, roots.saves().resolve("other")));
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, roots.game().resolve(prospective.getFileName())));
        Files.writeString(prospective, "existing save entry");
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, prospective));
        assertEquals("existing save entry", Files.readString(prospective));
    }

    @Test void cleanupEffectsKeepOriginalObservationsAndAttemptFailuresOnlyOnceBeforeSeal() {
        var op = new Operation(UUID.randomUUID(), 1, UUID.randomUUID(), Command.RUN, directory);
        var scope = new Scope(op, Kind.CLEANUP, Long.MAX_VALUE);
        var effects = new ScenarioRunner.InspectEffects();
        var order = new java.util.ArrayList<String>();
        var release = new IllegalStateException("original release failure");
        var result = effects.attempt(scope, () -> { order.add("observe"); return "original status/watch/fault"; },
                () -> { order.add("release"); throw release; }, () -> order.add("freeze"), () -> "aftermath");
        assertSame(release, result.releaseFailure());
        assertEquals("original status/watch/fault", result.before());
        assertSame(result, effects.seal(scope, null));
        assertSame(result, effects.attempt(scope, () -> fail("sealed observation"), () -> fail("retried release"),
                () -> fail("retried freeze"), () -> fail("sealed aftermath")));
        assertEquals(List.of("observe", "release", "freeze"), order);
        assertThrows(AssertionError.class, () -> effects.seal(new Scope(op, Kind.CLEANUP, Long.MAX_VALUE), null));
    }

    @Test void finalOutcomeReportsTypedTimeoutAndCleanupOnlyFailure() throws Exception {
        var host = new InspectTransport(); var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation); var timeout = new ScopeTimeoutException();
        var cleanup = host.beginCleanup(work, timeout);
        var result = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout));
        assertSame(timeout, result.failure()); assertSame(operation, host.outcome.operation());
        assertEquals(Verdict.TIMEOUT, host.outcome.verdict()); assertEquals(CleanupStatus.SETTLED, host.outcome.cleanup());
    }

    @Test void cleanupOnlyTimeoutIsFailureAndUnresolvedRatherThanSuccessfulWorkTimeout() throws Exception {
        // Synthetic asserted success, real owner finalization; no world/control acceptance is claimed.
        var host=new InspectTransport(); var watch=new ScenarioObserver.Watch(); var cleanupFailure=new ScopeTimeoutException();
        var runner=new ScenarioRunner(host,watch,roots(),() -> { throw cleanupFailure; },watch::freeze);
        var op=runner.beginInspectOperation(Command.RUN); var work=host.beginWork(op);
        var success=successfulOwnerState(runner,op,checkpoint(),"synthetic-success");
        host.inScope(work,() -> runner.assertResult(success));
        runnerField("worldCreated").setBoolean(runner,true);
        var cleanup=host.beginCleanup(work,null);
        assertSame(cleanupFailure,assertThrows(ScopeTimeoutException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,null))));
        assertEquals(Verdict.FAILURE,host.outcome.verdict()); assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup());
        assertTrue(runner.lastInspectCompletion().isEmpty());
    }

    @Test void completedNativeCloseCannotEraseEarlierUnresolvedOwnedEffects() throws Exception {
        var host=new InspectTransport(); var runner=runner(host); var op=runner.beginInspectOperation(Command.CLOSE);
        var cleanup=host.beginCleanup(host.beginWork(op),null); var failedRelease=new IllegalStateException("unverified close release");
        // Synthetic native-close outcome only; test owner latch retention, never world shutdown acceptance.
        runnerField("worldCreated").setBoolean(runner,true); runnerField("closed").setBoolean(runner,true);
        runnerField("normalClose").setBoolean(runner,true); runnerField("inspectUnresolvedCleanupFailure").set(runner,failedRelease);
        assertSame(failedRelease,assertThrows(IllegalStateException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,null))));
        assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup()); assertEquals(Verdict.FAILURE,host.outcome.verdict());
        assertTrue(runner.lastInspectCompletion().isEmpty()); assertThrows(AssertionError.class,() -> runner.beginInspectOperation(Command.RESET));
    }

    @Test void admittedFailuresHaveDistinctClosedEvidenceWithoutRunUuid() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var first = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(first);
        var failure = new AssertionError("reset failed before UUID");
        var cleanup = host.beginCleanup(work, failure);
        var a = host.inScope(cleanup, () -> runner.finishInspectOperation(first, cleanup, failure));
        assertSame(failure, a.failure());
        assertTrue(a.runId().isEmpty());
        assertTrue(a.evidenceComplete());
        assertTrue(a.cleanupSettled());
        byte[] closed = Files.readAllBytes(a.summaryPath());
        var second = runner.beginInspectOperation(Command.RUN);
        assertEquals(first.ordinal() + 1, second.ordinal());
        assertNotEquals(first.operationId(), second.operationId());
        assertNotEquals(first.evidenceDirectory(), second.evidenceDirectory());
        var secondWork = host.beginWork(second);
        var secondCleanup = host.beginCleanup(secondWork, failure);
        var b = host.inScope(secondCleanup, () -> runner.finishInspectOperation(second, secondCleanup, failure));
        assertArrayEquals(closed, Files.readAllBytes(a.summaryPath()));
        assertEquals(second.evidenceDirectory(), b.evidenceDirectory());
        assertSame(b, runner.lastInspectCompletion().orElseThrow());
        assertThrows(AssertionError.class, () -> runner.finishInspectOperation(first, cleanup, failure));
        assertSame(b, runner.lastInspectCompletion().orElseThrow());
    }

    @Test void beginFailureClosesAdmissionWithoutSuccessorOrLastReplacement() throws Exception {
        for (boolean finalLinkVisible : List.of(false, true)) {
            var host = new InspectTransport();
            var runner = runner(host);
            var earlier = runner.beginInspectOperation(Command.RESET);
            var earlierWork = host.beginWork(earlier);
            var earlierFailure = new AssertionError("prior admitted reset failure");
            var earlierCleanup = host.beginCleanup(earlierWork, earlierFailure);
            var last = host.inScope(earlierCleanup, () -> runner.finishInspectOperation(earlier, earlierCleanup, earlierFailure));
            byte[] previous = Files.readAllBytes(last.summaryPath());
            var operation = runner.beginInspectOperation(Command.RUN);
            if (finalLinkVisible) Files.writeString(operation.evidenceDirectory().resolve("visible-BEGIN"), "ambiguous publication");
            var failure = new IllegalStateException("BEGIN publication failed");
            runner.failInspectAdmission(operation, failure);
            assertSame(failure, runner.inspectAdmissionFailure().orElseThrow());
            assertSame(operation, runner.failedInspectAdmission().orElseThrow());
            assertTrue(host.cancelled);
            for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN))
                assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
            assertSame(last, runner.lastInspectCompletion().orElseThrow());
            assertArrayEquals(previous, Files.readAllBytes(last.summaryPath()));
            assertEquals(0, host.effects);
            runner.close(); // Fresh shutdown scope, no successor ordinal and no game effects without an owned world.
            assertSame(operation, host.shutdownOperation);
        }
    }

    @Test void allocationFailureLatchesReservedIdentityAndCancelsHost() throws Exception {
        var host = new InspectTransport();
        var roots = roots();
        Files.writeString(roots.evidence(), "blocks evidence allocation");
        var runner = new ScenarioRunner(host, new ScenarioObserver.Watch(), roots);
        assertThrows(RuntimeException.class, () -> runner.beginInspectOperation(Command.RESET));
        var reserved = runner.failedInspectAdmission().orElseThrow();
        assertEquals(1, reserved.ordinal());
        assertTrue(host.cancelled);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RUN));
        assertSame(reserved, runner.failedInspectAdmission().orElseThrow());
        assertEquals(0, host.effects);
    }

    @Test void headerCollisionLatchesOriginalCauseAndNeverRepairsNamespace() throws Exception {
        var host = new InspectTransport();
        var runner = new ScenarioRunner(host, new ScenarioObserver.Watch(), roots(), operation -> {
            assertDoesNotThrow(() -> Files.writeString(operation.evidenceDirectory().resolve("operation.json"), "existing header"));
        });
        var failure = assertThrows(java.io.UncheckedIOException.class, () -> runner.beginInspectOperation(Command.RERUN));
        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        var failed = runner.failedInspectAdmission().orElseThrow();
        assertSame(failure, runner.inspectAdmissionFailure().orElseThrow());
        assertEquals("existing header", Files.readString(failed.evidenceDirectory().resolve("operation.json")));
        assertTrue(host.cancelled);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        runner.close();
        assertSame(failed, host.shutdownOperation);
        assertEquals("existing header", Files.readString(failed.evidenceDirectory().resolve("operation.json")));
        assertEquals(0, host.effects);
    }

    @Test void finalizationCannotInventRunSuccessAndCaptureRunsOnlyOnce() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation);
        var original = new IllegalStateException("before native start");
        host.inScope(work, () -> runner.captureFailure(original));
        byte[] first = Files.readAllBytes(operation.evidenceDirectory().resolve("failure.txt"));
        var cleanup = host.beginCleanup(work, original);
        var completion = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, null));
        assertSame(original, completion.failure());
        assertArrayEquals(first, Files.readAllBytes(operation.evidenceDirectory().resolve("failure.txt")));
        assertEquals(0, original.getSuppressed().length);

        var next = runner.beginInspectOperation(Command.RUN);
        var nextWork = host.beginWork(next);
        var nextCleanup = host.beginCleanup(nextWork, null);
        var failed = host.inScope(nextCleanup, () -> runner.finishInspectOperation(next, nextCleanup, null));
        assertEquals("FAIL", failed.verdict());
        assertTrue(failed.failure().getMessage().contains("independently asserted success"));
        assertTrue(failed.runId().isEmpty());
    }

    @Test void exactBoundScopesProtectWorkAndFinalizationAndSettlementGatesAdmission() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        assertThrows(AssertionError.class, () -> runner.run(ScenarioDefinition.load()));
        var work = host.beginWork(operation);
        var cleanup = host.beginCleanup(work, null);
        var equal = new Scope(operation, Kind.CLEANUP, cleanup.deadlineNanos());
        assertThrows(AssertionError.class, () -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(operation, equal, null)));
        host.settlementFailure = new IllegalStateException("END/settlement failure");
        var timeout = new IllegalStateException("original WORK timeout");
        assertSame(timeout, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout))));
        assertArrayEquals(new Throwable[]{host.settlementFailure}, timeout.getSuppressed());
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        assertTrue(runner.lastInspectCompletion().isEmpty());
        assertEquals(0, host.effects);
    }

    @Test void resultIdentityUsesActualOperationDirectoryAndNeverRelabelsPriorResult() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var definition = ScenarioDefinition.load();
        var id = java.util.UUID.randomUUID();
        var result = new ScenarioRunner.ScenarioResult(id, new com.mapartrunner.core.run.BuildEngine().snapshot(),
                definition.definitionHash(), "independent-start", List.of(), operation.evidenceDirectory());
        ScenarioRunner.requireResultIdentity(result, result, java.util.Set.of(id), operation.evidenceDirectory(), definition);
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireResultIdentity(result, result,
                java.util.Set.of(id), operation.evidenceDirectory().getParent(), definition));
        assertEquals(operation.evidenceDirectory(), result.artifacts());
    }

    @Test void archiveFailurePrecedesRestoreAndKeepsOriginalClosedBytes() throws Exception {
        var original = directory.resolve("original-closed.txt");
        Files.writeString(original, "closed original evidence");
        byte[] before = Files.readAllBytes(original);
        var failure = new IllegalStateException("archive failed before COMMITTED");
        var restores = new int[1];
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ScenarioRunner.archiveThenRestore(() -> {
            assertArrayEquals(before, assertDoesNotThrow(() -> Files.readAllBytes(original)));
            throw failure;
        }, () -> restores[0]++)));
        assertEquals(0, restores[0]);
        assertArrayEquals(before, Files.readAllBytes(original));
    }

    @Test void retainedOperationHashMismatchBlocksTheRealArchiveValidationBeforeRestore() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(operation);
        var failure = new IllegalStateException("owned temporary evidence");
        var cleanup = host.beginCleanup(work, failure);
        host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, failure));
        invokeRunner(runner, "verifyInspectPriorEvidence");
        // Only this test's fresh TempDir evidence is altered; retained/native evidence is never touched.
        Files.writeString(operation.evidenceDirectory().resolve("failure.txt"), "changed closed fixture file");
        var restores = new int[1];
        assertThrows(AssertionError.class, () -> ScenarioRunner.archiveThenRestore(
                () -> invokeRunner(runner, "verifyInspectPriorEvidence"), () -> restores[0]++));
        assertEquals(0, restores[0]);
    }

    @Test void automaticRepeatLimitAndDirtyPreludeStayBoundedWhileInspectAttemptsContinue() {
        assertTrue(ScenarioRunner.startAllowed(false, true, 0, 2, true));
        assertTrue(ScenarioRunner.startAllowed(false, true, 1, 2, false));
        assertFalse(ScenarioRunner.startAllowed(false, true, 2, 2, false));
        assertFalse(ScenarioRunner.startAllowed(false, true, 1, 1, false));
        assertTrue(ScenarioRunner.startAllowed(true, true, 20, 2, false));
        assertFalse(ScenarioRunner.startAllowed(true, false, 0, 2, false));
    }

    @Test void successfulComparisonPairSurvivesAnInterveningFailedAttempt() throws Exception {
        // Synthetic owner state exercises assertion/accounting only, never native fixture success.
        var host = new InspectTransport();
        var runner = runner(host);
        var first = runner.beginInspectOperation(Command.RUN);
        var firstWork = host.beginWork(first);
        var firstStart = checkpoint();
        var a = successfulOwnerState(runner, first, firstStart, "start-A");
        host.inScope(firstWork, () -> runner.assertResult(a));
        var firstCleanup = host.beginCleanup(firstWork, null);
        var completion = host.inScope(firstCleanup, () -> runner.finishInspectOperation(first, firstCleanup, null));
        assertEquals("PASS", completion.verdict());
        var original = Files.readAllBytes(completion.summaryPath());

        var failed = runner.beginInspectOperation(Command.RERUN);
        var failedWork = host.beginWork(failed);
        runnerField("currentCountedStart").set(runner, checkpoint());
        var failure = new IllegalStateException("attempt B failed before assertions");
        var failedCleanup = host.beginCleanup(failedWork, failure);
        host.inScope(failedCleanup, () -> runner.finishInspectOperation(failed, failedCleanup, failure));
        assertSame(firstStart, runnerField("priorCountedStart").get(runner));
        assertSame(a, runnerField("priorCountedSuccess").get(runner));

        var next = runner.beginInspectOperation(Command.RUN);
        var nextWork = host.beginWork(next);
        var nextStart = checkpoint();
        var c = successfulOwnerState(runner, next, nextStart, "start-C");
        assertSame(a, runnerField("priorCountedSuccess").get(runner));
        host.inScope(nextWork, () -> runner.assertResult(c));
        assertSame(c, runnerField("priorCountedSuccess").get(runner));
        assertSame(nextStart, runnerField("priorCountedStart").get(runner));
        assertArrayEquals(original, Files.readAllBytes(completion.summaryPath()));
        assertEquals(0, host.effects);
    }

    @Test void expiredWorkWithRefusedOwnedCleanupRemainsUnresolvedAfterTransportSettlement() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation);
        host.expiredWork = true;
        assertThrows(AssertionError.class, () -> host.inScope(work, () -> runner.run(ScenarioDefinition.load())));
        assertEquals(0, host.effects);
        var timeout = new IllegalStateException("original absolute WORK timeout");
        var cleanup = host.beginCleanup(work, timeout);
        assertNotSame(work, cleanup);
        assertThrows(AssertionError.class, () -> host.beginCleanup(work, timeout));
        // The doubled native transport refuses every capture/release attempt, exercising primary retention.
        runnerField("worldCreated").setBoolean(runner, true);
        assertSame(timeout, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout))));
        assertTrue(timeout.getSuppressed().length > 0);
        assertNull(host.current); // Transport settlement returned normally; Runner still owns unresolved cleanup.
        assertTrue(runner.lastInspectCompletion().isEmpty());
        int effects = host.effects;
        int ordinal = runnerField("inspectOrdinal").getInt(runner);
        for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN))
            assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
        assertEquals(ordinal, runnerField("inspectOrdinal").getInt(runner));
        assertEquals(effects, host.effects);
    }

    @Test void releaseFailureKeepsPriorLastAndBlocksSuccessorAndRenewedShutdown() throws Exception {
        unresolvedOwnedCleanup("release");
    }

    @Test void freezeFailureKeepsPriorLastAndBlocksSuccessorAndRenewedShutdown() throws Exception {
        unresolvedOwnedCleanup("freeze");
    }

    private void unresolvedOwnedCleanup(String failingEffect) throws Exception {
        // Doubled owner effects test actual Runner orchestration; they confer no native release/physics credit.
        var host = new InspectTransport();
        var watch = new ScenarioObserver.Watch();
        var active = new Operation[1];
        var expectedCleanup = new Scope[1];
        var attempts = new int[2];
        var releaseFailure = new IllegalStateException("owned release could not be verified");
        var freezeFailure = new IllegalStateException("owned Watch freeze dispatch failed");
        var runner = new ScenarioRunner(host, watch, roots(), () -> {
            assertSame(expectedCleanup[0], host.requireScope(active[0], Kind.CLEANUP));
            attempts[0]++;
            if (failingEffect.equals("release")) throw releaseFailure;
        }, () -> {
            assertSame(expectedCleanup[0], host.requireScope(active[0], Kind.CLEANUP));
            attempts[1]++;
            if (failingEffect.equals("freeze")) throw freezeFailure;
            watch.freeze();
        });
        var prior = settledPrior(runner, host);
        var priorFiles = closedFileHashes(prior.evidenceDirectory());
        var operation = runner.beginInspectOperation(Command.RUN);
        active[0] = operation;
        var work = host.beginWork(operation);
        var original = new IllegalStateException("original scenario failure before cleanup");
        var cleanup = host.beginCleanup(work, original);
        expectedCleanup[0] = cleanup;
        runnerField("worldCreated").setBoolean(runner, true);
        field("frozen").setBoolean(watch, false);
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, original))));
        assertNull(host.current); // Successful transport settlement cannot prove either owned effect.
        assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup());
        assertEquals(Verdict.FAILURE,host.outcome.verdict());
        assertEquals(2, host.settlements);
        assertArrayEquals(new int[]{1, 1}, attempts);
        assertTrue(List.of(original.getSuppressed()).contains(failingEffect.equals("release") ? releaseFailure : freezeFailure));
        assertSame(original, runnerField("inspectUnresolvedCleanupFailure").get(runner));
        assertSame(operation, runnerField("inspectOperation").get(runner));
        assertEquals(failingEffect.equals("release"), field("frozen").getBoolean(watch));
        var failedFiles = closedFileHashes(operation.evidenceDirectory());
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(operation.evidenceDirectory().resolve("completion.json"))).getAsJsonObject();
        assertEquals("FAILED", manifest.get("cleanupStatus").getAsString());
        assertTrue(manifest.get("failure").getAsString().contains(original.getMessage()));
        int ordinal = runnerField("inspectOrdinal").getInt(runner);
        int effects = host.effects;
        for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN, Command.CLOSE))
            assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
        assertThrows(AssertionError.class, () -> runner.run(ScenarioDefinition.load()));
        assertThrows(AssertionError.class, () -> runner.failInspectAdmission(operation, new IllegalStateException("cannot reclassify cleanup as BEGIN failure")));
        assertThrows(AssertionError.class, runner::close);
        assertNull(host.shutdownOperation);
        assertEquals(0, host.shutdownScopes);
        assertEquals(effects, host.effects);
        assertEquals(ordinal, runnerField("inspectOrdinal").getInt(runner));
        assertSame(prior, runner.lastInspectCompletion().orElseThrow());
        assertEquals(priorFiles, closedFileHashes(prior.evidenceDirectory()));
        assertEquals(failedFiles, closedFileHashes(operation.evidenceDirectory()));
    }

    @Test void expiredWorkAndEvidenceOnlyFailureCanSettleAfterSuccessfulOwnedCleanup() throws Exception {
        // These scoped effect doubles execute successfully; real native ownership remains a later gate.
        var host = new InspectTransport();
        var watch = new ScenarioObserver.Watch();
        var active = new Operation[1];
        var attempts = new int[2];
        var runner = new ScenarioRunner(host, watch, roots(), () -> {
            host.requireScope(active[0], Kind.CLEANUP); attempts[0]++;
        }, () -> {
            host.requireScope(active[0], Kind.CLEANUP); attempts[1]++; watch.freeze();
        });
        var prior = settledPrior(runner, host);
        var priorFiles = closedFileHashes(prior.evidenceDirectory());
        var operation = runner.beginInspectOperation(Command.RUN);
        active[0] = operation;
        var work = host.beginWork(operation);
        host.expiredWork = true;
        assertThrows(AssertionError.class, () -> host.inScope(work, () -> runner.run(ScenarioDefinition.load())));
        var timeout = new IllegalStateException("original absolute WORK timeout");
        var cleanup = host.beginCleanup(work, timeout);
        assertNotSame(work, cleanup);
        runnerField("worldCreated").setBoolean(runner, true);
        var completion = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout));
        assertSame(timeout, completion.failure());
        assertTrue(timeout.getSuppressed().length > 0); // Native evidence reads were refused by the test transport.
        assertFalse(completion.evidenceComplete());
        assertEquals("RELEASED", completion.cleanupStatus());
        assertTrue(completion.cleanupSettled());
        assertArrayEquals(new int[]{1, 1}, attempts);
        assertTrue(field("frozen").getBoolean(watch));
        assertNull(runnerField("inspectUnresolvedCleanupFailure").get(runner));
        assertSame(completion, runner.lastInspectCompletion().orElseThrow());
        int effects = host.effects;
        assertEquals(operation.ordinal() + 1, runner.beginInspectOperation(Command.RESET).ordinal());
        assertEquals(effects, host.effects);
        assertEquals(priorFiles, closedFileHashes(prior.evidenceDirectory()));
    }

    private static ScenarioRunner.InspectCompletion settledPrior(ScenarioRunner runner, InspectTransport host) {
        var operation = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(operation);
        var failure = new IllegalStateException("previous settled scenario failure");
        var cleanup = host.beginCleanup(work, failure);
        return host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, failure));
    }

    private static java.util.Map<String, String> closedFileHashes(Path directory) throws Exception {
        var hashes = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (var file : paths.filter(Files::isRegularFile).toList())
                hashes.put(directory.relativize(file).toString(), Files.size(file) + ":" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return hashes;
    }

    private static Object checkpoint() throws Exception {
        var constructor = runnerField("currentCountedStart").getType().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null);
    }

    private static ScenarioRunner.ScenarioResult successfulOwnerState(ScenarioRunner runner, Operation operation,
                                                                      Object checkpoint, String hash) throws Exception {
        var id = UUID.randomUUID();
        var context = new RunLifecycle.Context(id, "unit-connection", "unit-world", "minecraft:overworld", "unit-plan",
                new BuildPlan.GridPos(0, 65, 0), "unit-settings");
        var lifecycle = new RunLifecycle.Snapshot(RunLifecycle.Phase.TERMINAL, Optional.of(context), Optional.empty(),
                Optional.of(RunLifecycle.Outcome.SUCCESS));
        var snapshot = new BuildEngine.Snapshot(lifecycle, 0, 0, Optional.empty(), List.of(), Set.of(), Optional.empty(), List.of());
        var definition = ScenarioDefinition.load();
        var result = new ScenarioRunner.ScenarioResult(id, snapshot, definition.definitionHash(), hash, List.of(), operation.evidenceDirectory());
        @SuppressWarnings("unchecked") var runs = (Set<UUID>) runnerField("runs").get(runner);
        runs.add(id);
        runnerField("inspectRun").set(runner, id);
        runnerField("admitted").set(runner, definition);
        runnerField("previous").set(runner, result);
        runnerField("currentCountedStart").set(runner, checkpoint);
        return result;
    }

    private static Field runnerField(String name) throws Exception {
        var field = ScenarioRunner.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static void invokeRunner(ScenarioRunner runner, String name) {
        try {
            var method = ScenarioRunner.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(runner);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw (RuntimeException) error.getCause();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invokeRunner(ScenarioRunner runner,String name,Runnable action) {
        try {
            var method=ScenarioRunner.class.getDeclaredMethod(name,Runnable.class); method.setAccessible(true); method.invoke(runner,action);
        } catch(java.lang.reflect.InvocationTargetException error) {
            if(error.getCause() instanceof Error fatal) throw fatal;
            throw (RuntimeException)error.getCause();
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private ScenarioRunner runner(InspectTransport host) throws Exception {
        return new ScenarioRunner(host, new ScenarioObserver.Watch(), roots());
    }

    private ScenarioRunner.Roots roots() throws Exception {
        var module = Files.createTempDirectory(directory, "module-");
        var build = Files.createDirectory(module.resolve("build"));
        var game = Files.createDirectories(build.resolve("run/inspect"));
        return new ScenarioRunner.Roots(module, game, build, build.resolve("scenario-results"), game.resolve("saves"));
    }

    /** Only dispatch permission is doubled. No world/physics/native claim comes from this transport. */
    private static final class InspectTransport implements Inspect {
        Scope bound, current;
        boolean cancelled;
        boolean expiredWork;
        int effects;
        Throwable settlementFailure;
        Operation shutdownOperation;
        int settlements, shutdownScopes;
        FinalOutcome outcome;
        public void bindStoppingOwner(BiConsumer<MinecraftClient, Scope> owner) { }
        public void requireStoppingScope(Scope cleanup) { fail("No native stopping claim"); }
        public void createWorld(BiConsumer<MinecraftClient, Path> guard) { effects++; fail("No native world available"); }
        public Path takeScreenshot(Path directory,String name,Consumer<MinecraftClient> guard) { effects++; return fail("No native world available"); }
        public void closeWorld(Consumer<MinecraftClient> guard) { effects++; fail("No native world available"); }
        public void awaitNeutralChat() { effects++; fail("No native world available"); }
        public Scope beginStartup(Operation op) { return current = new Scope(op, Kind.STARTUP, Long.MAX_VALUE); }
        public void settleStartup(Scope scope) { assertSame(current, scope); current = null; }
        public Scope beginWork(Operation op) { assertFalse(cancelled); return current = new Scope(op, Kind.WORK, Long.MAX_VALUE); }
        public Scope beginCleanup(Scope work, Throwable primary) {
            assertSame(current, work); return current = new Scope(work.operation(), Kind.CLEANUP, Long.MAX_VALUE);
        }
        public void settleCleanup(Scope scope, FinalOutcome outcome) {
            assertSame(current, scope);
            assertSame(scope.operation(), outcome.operation()); this.outcome = outcome;
            if (settlementFailure != null) throw (RuntimeException) settlementFailure;
            settlements++;
            current = null;
        }
        public void cancelSession(Throwable primary) { cancelled = true; }
        public Scope beginShutdownCleanup(Operation failed, Throwable primary) {
            shutdownScopes++;
            shutdownOperation = failed;
            return current = new Scope(failed, Kind.CLEANUP, Long.MAX_VALUE);
        }
        public Scope requireScope(Operation expected, Kind kind) {
            assertNotNull(bound); assertSame(current, bound); assertSame(expected, bound.operation());
            assertFalse(kind == Kind.WORK && expiredWork, "WORK expired");
            assertEquals(kind, bound.kind()); return bound;
        }
        public void inScope(Scope scope, Runnable action) { inScope(scope, () -> { action.run(); return null; }); }
        public <V> V inScope(Scope scope, Supplier<V> action) {
            var old = bound; bound = scope; try { return action.get(); } finally { bound = old; }
        }
        public void runOnClient(Consumer<MinecraftClient> action) { requireScope(current.operation(),current.kind()); effects++; fail("No native world available"); }
        public <V> V computeOnClient(Function<MinecraftClient, V> action) { requireScope(current.operation(),current.kind()); effects++; return fail("No native world available"); }
        public void runOnServer(Consumer<MinecraftServer> action) { effects++; fail("No native world available"); }
        public <V> V computeOnServer(Function<MinecraftServer, V> action) { effects++; return fail("No native world available"); }
        public void waitTick() { effects++; fail("No native world available"); }
        public void createWorld() { effects++; fail("No native world available"); }
        public Path saveDirectory() { effects++; return fail("No native world available"); }
        public Path takeScreenshot(Path path, String name) { effects++; return fail("No native world available"); }
        public void closeWorld() { effects++; fail("No native world available"); }
    }

    @Test void frozenWatchPreservesSamplesFailureAndBindingPastIdleBudget() throws Exception {
        var watch = new ScenarioObserver.Watch();
        var plan = new BuildPlan("retained", new BuildPlan.GridSize(1, 1, 1), List.of());
        var original = new AssertionError("original observer failure");
        field("plan").set(watch, plan);
        field("failure").set(watch, original);
        var sample = new ScenarioObserver.Sample(1, "END", 12, true, null, List.of(), false, null, List.of());
        @SuppressWarnings("unchecked") var samples = (List<ScenarioObserver.Sample>) field("samples").get(watch);
        samples.add(sample);
        watch.freeze();
        var retained = watch.retained();
        for (int i = 0; i < 12002; i++) watch.record(null, i % 2 == 0 ? "START" : "END");
        assertEquals(List.of(sample), watch.retained().samples());
        assertEquals(retained, watch.retained());
        assertSame(original, watch.retained().failure());
        assertSame(plan, field("plan").get(watch));
        assertThrows(UnsupportedOperationException.class, () -> retained.samples().clear());
        watch.clear(); // Only the explicit archive-before-clear/arm route restarts recording.
        assertTrue(watch.retained().samples().isEmpty());
        assertNull(watch.retained().failure());
    }

    @Test void frozenHealthyWatchDoesNotResumeRecordingDuringIdleCallbacks() throws Exception {
        var watch = new ScenarioObserver.Watch();
        field("plan").set(watch, new BuildPlan("healthy-retained", new BuildPlan.GridSize(1, 1, 1), List.of()));
        watch.freeze();
        var before = watch.retained();
        for (int i = 0; i < 12002; i++) watch.record(null, i % 2 == 0 ? "START" : "END");
        assertEquals(before, watch.retained());
        assertNull(watch.retained().failure());
        assertEquals(0L, field("tick").getLong(watch));
    }

    private static Field field(String name) throws Exception {
        var field = ScenarioObserver.Watch.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
