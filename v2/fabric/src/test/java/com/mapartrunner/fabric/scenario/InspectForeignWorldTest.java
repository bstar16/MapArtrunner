package com.mapartrunner.fabric.scenario;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.Condition;
import java.util.function.Supplier;
import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner queues with synthetic native boundaries; no native world authority is inferred. */
class InspectForeignWorldTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize(); }
    @TempDir Path directory;
    private final String[] names={"mapart.test.inspect","mapart.test.inspect.acceptance","mapart.test.inspect.acceptance.case","fabric.client.gametest"};
    private String[] old;
    @BeforeEach void gates() {
        old=Arrays.stream(names).map(System::getProperty).toArray(String[]::new);
        System.setProperty(names[0],"true"); System.setProperty(names[1],"true");
        System.setProperty(names[2],"foreign-world"); System.clearProperty(names[3]);
    }
    @AfterEach void restore() {
        for(int i=0;i<names.length;i++) { if(old[i]==null) System.clearProperty(names[i]); else System.setProperty(names[i],old[i]); }
    }
    @Test void actualResetStopWrapperIsHeldBeforeItsAction() throws Exception {
        String[] names={"mapart.test.inspect","mapart.test.inspect.acceptance","mapart.test.inspect.acceptance.case","fabric.client.gametest"};
        String[] old=java.util.Arrays.stream(names).map(System::getProperty).toArray(String[]::new);
        var queue=new LinkedBlockingQueue<Runnable>(); var effects=new AtomicInteger();
        var nativeThread=ThreadLocal.withInitial(() -> false);
        var host=new InspectScenarioHost(() -> 1L,nativeThread::get,() -> false,queue::add,queue::add,o -> {});
        var operation=new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RERUN,Path.of("synthetic-evidence"));
        var work=host.beginWork(operation); var failure=new AtomicReference<Throwable>();
        try {
            System.setProperty(names[0],"true"); System.setProperty(names[1],"true");
            System.setProperty(names[2],"foreign-world"); System.clearProperty(names[3]);
            var worker=new Thread(() -> {
                try { host.inScope(work,() -> {
                    host.armForeignResetStop(operation);
                    host.runOnClient(c -> effects.incrementAndGet());
                }); } catch(Throwable error) { failure.set(error); }
            }); worker.start();
            var wrapper=queue.poll(5,TimeUnit.SECONDS); assertNotNull(wrapper);
            nativeThread.set(true); try { wrapper.run(); } finally { nativeThread.remove(); }
            var cancellation=new IllegalStateException("actual disconnect"); host.cancelSession(cancellation);
            worker.join(5000); assertFalse(worker.isAlive());
            assertEquals(0,effects.get(),"Eligible real queue wrapper must defer before native action");
            assertSame(cancellation,failure.get());
        } finally { for(int i=0;i<names.length;i++) { if(old[i]==null) System.clearProperty(names[i]); else System.setProperty(names[i],old[i]); } }
    }

    @Test void joinedCommandsReplayMeasurementAndFinalSealPrecedeHonestUnresolvedEnd() throws Exception {
        var f=new Fixture(); var held=f.startCommand(); var retained=f.commands.observeInspect().last();
        var cancellation=new IllegalStateException("disconnect original"); f.commands.cancel(cancellation);
        assertTrue(f.commands.observeInspect().busy()); assertTrue(f.commands.observeInspect().restartRequired());
        assertEquals(0,f.commands.enqueue(Command.RESET,s -> {})); assertEquals(0,f.commands.enqueue(Command.RUN,s -> {}));
        assertEquals(0,f.commands.enqueue(Command.RERUN,s -> {}));
        assertEquals(3,(int)get(f.runner,"inspectOrdinal")); assertEquals(retained,f.commands.observeInspect().last());
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,held.work.operation().operationId(),held.work.deadlineNanos())));
        assertTrue(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)));
        assertFalse(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)));
        var replay=f.take(); assertSame(held.wrapper,replay,"Replay retains the actual original wrapper");
        assertNull(get(get(f.host,"foreign"),"continuation"));
        assertFalse(f.commands.observeForeignAcceptance().replayRejected(),"Queue submission is not rejection proof");
        f.nativeRun(replay); f.nativeRun(replay);
        var rejected=f.commands.observeForeignAcceptance(); assertTrue(rejected.replayRejected());
        assertEquals("REPLAY_REJECTED",rejected.stage()); assertSame(held.work,get(f.host,"current"));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,UUID.randomUUID(),held.work.deadlineNanos())));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,rejected.operationId(),held.work.deadlineNanos()+1)));
        assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,rejected.operationId(),held.work.deadlineNanos())));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,rejected.operationId(),held.work.deadlineNanos())));
        var request=f.pumpToFinal();
        assertTrue(f.outcomes.isEmpty()); assertFalse(Files.exists(f.artifacts().resolve("completion.json")));
        assertEquals(held.work.operation().operationId(),request.operationId()); assertEquals(1,request.requestOrdinal());
        assertEquals(f.now.get()+10_000_000_000L,request.cleanupDeadlineNanos()); assertTrue(request.boundaryRetired());
        assertTrue(request.finalFacts().retainedWatchUnchanged()); assertTrue(request.finalFacts().closedRunEvidenceUnchanged());
        assertEquals(cancellation.getClass().getName(),request.finalFacts().primaryClass());
        assertEquals("disconnect original",request.finalFacts().primaryMessage());
        assertTrue(request.guards().stream().anyMatch(g -> g.site().equals("CLEANUP_PREFLIGHT") && g.entered()==1 && g.refused()==1 && g.passed()==0));
        assertTrue(request.guards().stream().allMatch(g -> g.effectCalled()==0));
        assertFalse(request.guards().stream().anyMatch(g -> Set.of("RESET_STOP","RESET_CLEAR","RESTORE_CLIENT","RESTORE_SERVER","SCREENSHOT","CLEANUP_RELEASE").contains(g.site())),"Unreached guards earn no credit");
        assertEquals(0,f.serverCalls.get(),"Live client refusal precedes old-server dispatch");
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(UUID.randomUUID(),1,"f".repeat(64)))));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(request.operationId(),2,"f".repeat(64)))));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(request.operationId(),1,"bad"))));
        var seal=ack(request.operationId(),1,"f".repeat(64));
        assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,seal)));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,seal)));
        join(held.worker);
        assertEquals(1,f.outcomes.size()); assertEquals(CleanupStatus.UNRESOLVED,f.outcomes.getFirst().cleanup());
        assertEquals(Verdict.FAILURE,f.outcomes.getFirst().verdict()); assertEquals("SEALED_BEFORE_END",f.commands.observeForeignAcceptance().stage());
        assertEquals(seal,f.commands.observeForeignAcceptance().seal()); assertNull(get(get(f.host,"foreign"),"cleanup"));
        assertNull(get(get(f.host,"foreign"),"work")); assertNull(get(get(f.host,"foreign"),"continuation"));
        assertEquals(retained,f.commands.observeInspect().last()); assertEquals(3,(int)get(f.runner,"inspectOrdinal"));
        assertSame(cancellation,get(f.commands,"terminal")); assertNull(get(f.runner,"inspectForeignPrior"));
        assertFalse(f.commands.observeInspect().busy()); assertFalse(f.commands.requestStop());
        assertEquals("SEALED_BEFORE_END",f.commands.observeForeignAcceptance().stage());
        assertTrue(Files.readString(f.artifacts().resolve("completion.json")).contains("unresolved"));
        assertArrayEquals(f.run2Bytes,Files.readAllBytes(f.run2.summaryPath()));
    }

    @Test void everyCancelledHoldFailureStillAttemptsOriginalCleanupOnceAndRetainsPrimary() throws Exception {
        for(String mode:List.of("disabled","expired","expired-replay","interrupted","queue-refused","stop","retirement-notification")) {
            var f=new Fixture(); var held=f.startCommand(); var primary=new IllegalArgumentException("disconnect "+mode);
            f.commands.cancel(primary);
            f.awaitCancelledHold();
            if(mode.equals("retirement-notification")) {
                Condition actual=(Condition)get(f.host,"changed");
                var failed=new AtomicBoolean();
                var wrapped=(Condition)Proxy.newProxyInstance(Condition.class.getClassLoader(),new Class<?>[]{Condition.class},(p,m,a) -> {
                    if(m.getName().equals("signalAll") && get(get(f.host,"foreign"),"work")==null && failed.compareAndSet(false,true))
                        throw new IllegalStateException("retirement notification failed");
                    try { return m.invoke(actual,a); } catch(InvocationTargetException error) { throw error.getCause(); }
                }); set(f.host,"changed",wrapped);
            }
            switch(mode) {
                case "disabled","retirement-notification" -> System.setProperty(names[1],"false");
                case "expired" -> f.now.set(held.work.deadlineNanos()+11_000_000_000L);
                case "expired-replay" -> {
                    assertTrue(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)));
                    var replay=f.take(); f.now.set(held.work.deadlineNanos()); f.nativeRun(replay);
                    assertFalse(f.commands.observeForeignAcceptance().replayRejected());
                    assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,held.work.operation().operationId(),held.work.deadlineNanos())));
                }
                case "interrupted" -> held.worker.interrupt();
                case "queue-refused" -> { f.refuseQueue.set(true); assertTrue(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null))); }
                case "stop" -> { assertFalse(f.commands.requestStop()); assertFalse(f.commands.requestStop()); }
                default -> fail();
            }
            f.pumpUntilDone(held.worker);
            var cleanup=(Scope)get(f.host,"current");
            if(cleanup==null) assertEquals(1,f.outcomes.size());
            else { assertEquals(Kind.CLEANUP,cleanup.kind()); assertSame(held.work.operation(),cleanup.operation()); }
            assertThrows(IllegalStateException.class,() -> f.host.beginCleanup(held.work,primary),"Original transition already consumed once");
            if(mode.equals("expired")) assertEquals(held.work.deadlineNanos()+10_000_000_000L,cleanup.deadlineNanos());
            assertSame(primary,get(f.commands,"terminal")); assertTrue(primary.getSuppressed().length>0,mode);
            if(mode.equals("interrupted")) assertTrue(f.interruptedAtExit.get());
            if(mode.equals("retirement-notification")) assertTrue(Arrays.stream(primary.getSuppressed()).anyMatch(e -> "retirement notification failed".equals(e.getMessage())));
            assertNull(get(get(f.host,"foreign"),"continuation")); assertNull(get(get(f.host,"foreign"),"work"));
            f.nativeRun(held.wrapper); assertEquals(0,f.serverCalls.get());
            assertEquals(3,(int)get(f.runner,"inspectOrdinal")); assertEquals(2,f.commands.observeInspect().last().orElseThrow().ordinal());
            System.setProperty(names[1],"true");
        }
    }

    @Test void finalProofExpiryStopAndMissingSealNeverBecomeComplete() throws Exception {
        for(String mode:List.of("expiry","stop","ack-then-stop","disabled")) {
            var f=new Fixture(); var held=f.startCommand(); var primary=new IllegalStateException("disconnect final "+mode);
            f.commands.cancel(primary); assertTrue(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)));
            f.nativeRun(f.take()); var replay=f.commands.observeForeignAcceptance();
            assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,replay.operationId(),replay.workDeadlineNanos())));
            var request=f.pumpToFinal();
            switch(mode) {
                case "expiry" -> f.now.set(request.cleanupDeadlineNanos());
                case "stop" -> {
                    f.nativeRun(() -> f.host.onStopping(null));
                    var result=get(get(f.runner,"inspectEffects"),"result"); var scope=get(f.host,"current");
                    f.nativeRun(() -> f.host.onStopping(null));
                    assertSame(result,get(get(f.runner,"inspectEffects"),"result")); assertSame(scope,get(f.host,"current"));
                    assertFalse(f.commands.requestStop());
                }
                case "ack-then-stop" -> {
                    // Hold the owner lock so the worker cannot consume the ack ahead of the native stop.
                    var lock=(java.util.concurrent.locks.ReentrantLock)get(f.host,"lock"); lock.lock();
                    try {
                        assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(request.operationId(),1,"e".repeat(64)))));
                        f.nativeRun(() -> f.host.onStopping(null));
                    } finally { lock.unlock(); }
                }
                case "disabled" -> System.setProperty(names[1],"false");
                default -> fail();
            }
            assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(request.operationId(),1,"e".repeat(64)))));
            join(held.worker); assertEquals("INCOMPLETE",f.commands.observeForeignAcceptance().stage());
            if(!mode.equals("ack-then-stop")) assertNull(f.commands.observeForeignAcceptance().seal());
            assertSame(primary,get(f.commands,"terminal"));
            assertTrue(primary.getSuppressed().length>0); assertEquals(2,f.commands.observeInspect().last().orElseThrow().ordinal());
            if(!mode.equals("expiry")) assertEquals(CleanupStatus.UNRESOLVED,f.outcomes.getFirst().cleanup());
            assertFalse(Files.readString(f.artifacts().resolve("completion.json")).contains("\"evidenceComplete\": true"));
            assertNull(get(get(f.host,"foreign"),"cleanup")); System.setProperty(names[1],"true");
        }
    }

    @Test void selectorIsInertAndArmingAndNativeClaimsRequireExactGatesIdentityAndDeadline() {
        for(Command command:Command.values()) assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"foreign-world",command,3,1,true));
        var f=new Fixture(); var work=f.host.beginWork(f.operation());
        assertThrows(IllegalStateException.class,() -> f.host.armForeignResetStop(work.operation()));
        System.setProperty("fabric.client.gametest","true");
        assertThrows(IllegalStateException.class,() -> f.host.inScope(work,() -> f.host.armForeignResetStop(work.operation())));
        System.clearProperty("fabric.client.gametest");
        f.host.inScope(work,() -> f.host.armForeignResetStop(work.operation()));
        assertFalse(f.commands.releaseForeignAcceptanceBoundary(null),"Not a native thread");
        assertFalse(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)),"Not entered/cancelled");
        assertThrows(IllegalStateException.class,() -> f.host.inScope(work,() -> f.host.armForeignResetStop(work.operation())));
    }

    @Test void nativeSchedulingClaimsRefuseContentionWithoutWaiting() throws Exception {
        var f=new Fixture(); var held=f.startCommand(); f.commands.cancel(new IllegalStateException("disconnect contention"));
        f.awaitCancelledHold();
        var lock=(java.util.concurrent.locks.ReentrantLock)get(f.host,"lock");
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var owner=new Thread(() -> {
            lock.lock(); try { locked.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException error) { throw new AssertionError(error); }
            finally { lock.unlock(); }
        }); owner.start(); assertTrue(locked.await(5,TimeUnit.SECONDS));
        var claims=new CompletableFuture<Boolean>();
        var nativeCaller=new Thread(() -> {
            try { claims.complete(f.nativeCall(() -> f.commands.releaseForeignAcceptanceBoundary(null)
                    || f.commands.acknowledgeForeignReplayMeasurement(null,held.work.operation().operationId(),held.work.deadlineNanos())
                    || f.commands.acknowledgeForeignFinalProof(null,ack(held.work.operation().operationId(),1,"e".repeat(64))))); }
            catch(Throwable failure) { claims.completeExceptionally(failure); }
        });
        try { nativeCaller.start(); assertFalse(claims.get(1,TimeUnit.SECONDS)); }
        finally { release.countDown(); join(owner); join(nativeCaller); f.host.abandonForeignAcceptance(); }
        f.pumpUntilDone(held.worker); assertTrue(f.commands.observeInspect().restartRequired());
    }

    @Test void contendedFinalClaimPreservesExactRequestThenSameFrozenClaimIsConsumedOnce() throws Exception {
        var f=new Fixture(); var held=f.startCommand(); f.commands.cancel(new IllegalStateException("disconnect final contention"));
        assertTrue(f.nativeClaim(() -> f.commands.releaseForeignAcceptanceBoundary(null)));
        f.nativeRun(f.take()); var replay=f.commands.observeForeignAcceptance();
        assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignReplayMeasurement(null,replay.operationId(),replay.workDeadlineNanos())));
        var request=f.pumpToFinal(); var frozenSeal=ack(request.operationId(),request.requestOrdinal(),"a".repeat(64));
        var lock=(java.util.concurrent.locks.ReentrantLock)get(f.host,"lock");
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var owner=new Thread(() -> {
            lock.lock(); try { locked.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException error) { throw new AssertionError(error); }
            finally { lock.unlock(); }
        }); owner.start(); assertTrue(locked.await(5,TimeUnit.SECONDS));
        var delivered=new CompletableFuture<Boolean>();
        var nativeCaller=new Thread(() -> {
            try { delivered.complete(f.nativeCall(() -> f.commands.acknowledgeForeignFinalProof(null,frozenSeal))); }
            catch(Throwable failure) { delivered.completeExceptionally(failure); }
        });
        try {
            nativeCaller.start(); assertFalse(delivered.get(1,TimeUnit.SECONDS));
            assertEquals(request,f.commands.observeForeignAcceptance(),"False consumes neither request nor original deadline/facts");
            assertTrue(f.outcomes.isEmpty()); assertFalse(Files.exists(f.artifacts().resolve("completion.json")));
        } finally { release.countDown(); join(owner); join(nativeCaller); }
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,ack(request.operationId(),request.requestOrdinal()+1,"a".repeat(64)))));
        assertEquals(request,f.commands.observeForeignAcceptance(),"Stale claim does not replace pending request");
        // A later synthetic native callback delivers the SAME immutable seal, without another read/measurement/allowance.
        assertTrue(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,frozenSeal)));
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,frozenSeal)));
        join(held.worker);
        assertSame(frozenSeal,f.commands.observeForeignAcceptance().seal());
        assertEquals(request.requestOrdinal(),f.commands.observeForeignAcceptance().requestOrdinal());
        assertEquals(request.cleanupDeadlineNanos(),f.commands.observeForeignAcceptance().cleanupDeadlineNanos());
        assertEquals(CleanupStatus.UNRESOLVED,f.outcomes.getFirst().cleanup());
        f.now.set(request.cleanupDeadlineNanos());
        assertFalse(f.nativeClaim(() -> f.commands.acknowledgeForeignFinalProof(null,frozenSeal)));
    }

    private static ScenarioCommands.ForeignProofAck ack(UUID operation,long request,String hash) {
        return new ScenarioCommands.ForeignProofAck(operation,request,28,1024,hash,"F/client+server/request28");
    }
    private record Held(Scope work,Runnable wrapper,Thread worker) { }
    private final class Fixture {
        final AtomicLong now=new AtomicLong(1);
        final ThreadLocal<Boolean> nativeThread=ThreadLocal.withInitial(() -> false);
        final LinkedBlockingQueue<Runnable> clientQueue=new LinkedBlockingQueue<>();
        final AtomicBoolean refuseQueue=new AtomicBoolean(),interruptedAtExit=new AtomicBoolean();
        final AtomicInteger serverCalls=new AtomicInteger();
        final List<FinalOutcome> outcomes=new CopyOnWriteArrayList<>();
        final InspectScenarioHost host=new InspectScenarioHost(now::get,nativeThread::get,() -> false,
                action -> { if(refuseQueue.get()) throw new RejectedExecutionException("replay queue refused"); clientQueue.add(action); },
                action -> { serverCalls.incrementAndGet(); throw new AssertionError("old server must not be dispatched"); },outcomes::add);
        final ArrayDeque<Runnable> workerQueue=new ArrayDeque<>();
        final ScenarioCommands commands=new ScenarioCommands(workerQueue::add,host);
        ScenarioRunner runner;
        ScenarioRunner.InspectCompletion run2;
        byte[] run2Bytes;
        Operation operation() { return new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RERUN,directory.resolve("synthetic")); }
        Held startCommand() throws Exception {
            var module=Files.createTempDirectory(directory,"module-"); var build=Files.createDirectories(module.resolve("build"));
            var game=Files.createDirectories(build.resolve("run/inspect")); var evidence=build.resolve("scenario-results");
            var watch=new ScenarioObserver.Watch();
            runner=new ScenarioRunner(host,watch,new ScenarioRunner.Roots(module,game,build,evidence,game.resolve("saves")));
            var run2Directory=Files.createDirectories(evidence.resolve("closed-run2"));
            var summary=Files.writeString(run2Directory.resolve("completion.json"),"{\"closedFiles\":[]}");
            run2Bytes=Files.readAllBytes(summary);
            run2=new ScenarioRunner.InspectCompletion(runner.inspectSessionId(),UUID.randomUUID(),2,Command.RUN,Optional.of(UUID.randomUUID()),
                    "PASS",null,run2Directory,true,"RELEASED",true,summary);
            set(runner,"inspectLast",run2); set(runner,"inspectLastSummaryHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(run2Bytes)));
            set(runner,"inspectOrdinal",2); set(commands,"runner",runner); set(commands,"ready",true); set(commands,"last",run2);
            assertEquals(1,commands.enqueue(Command.RERUN,s -> {}));
            var worker=new Thread(() -> { try { workerQueue.remove().run(); } finally { interruptedAtExit.set(Thread.currentThread().isInterrupted()); } });
            worker.start(); var wrapper=take(); var work=(Scope)get(host,"current");
            // Partial seam: arm issued WORK before its existing neutral-chat wrapper enters.
            // Runner's real RESET-stop arming additionally requires native preflight and asserted RUN2.
            host.inScope(work,() -> host.armForeignResetStop(work.operation()));
            set(runner,"inspectForeignOperation",work.operation()); set(runner,"inspectForeignPrior",new ScenarioRunner.InspectIdleObservation(watch.retained()));
            set(runner,"worldCreated",true); nativeRun(wrapper);
            assertEquals("HELD",commands.observeForeignAcceptance().stage()); return new Held(work,wrapper,worker);
        }
        Path artifacts() throws Exception { return (Path)get(runner,"artifacts"); }
        Runnable take() throws InterruptedException {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                host.onTick(); var action=clientQueue.poll(10,TimeUnit.MILLISECONDS); if(action!=null) return action;
            }
            throw new AssertionError("Native callback not queued");
        }
        void nativeRun(Runnable action) { nativeCall(() -> { action.run(); return true; }); }
        <T> T nativeCall(Supplier<T> action) { nativeThread.set(true); try { return action.get(); } finally { nativeThread.remove(); } }
        // Deterministic uncontended claims, without retries; contention refusal is exercised separately above.
        <T> T nativeClaim(Supplier<T> action) {
            try {
                var lock=(java.util.concurrent.locks.ReentrantLock)get(host,"lock"); lock.lock();
                try { return nativeCall(action); } finally { lock.unlock(); }
            } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
            catch(Exception error) { throw new AssertionError(error); }
        }
        ScenarioCommands.ForeignObservation pumpToFinal() throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                var observation=commands.observeForeignAcceptance();
                if(observation!=null && observation.stage().equals("FINAL_NATIVE_READ_REQUIRED")) return observation;
                var action=clientQueue.poll(10,TimeUnit.MILLISECONDS); if(action!=null) nativeRun(action);
            }
            throw new AssertionError("Final request not reached: "+commands.observeForeignAcceptance());
        }
        void pumpUntilDone(Thread worker) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(worker.isAlive() && System.nanoTime()<deadline) {
                var action=clientQueue.poll(10,TimeUnit.MILLISECONDS); if(action!=null) nativeRun(action);
            }
            join(worker);
        }
        void awaitCancelledHold() throws InterruptedException {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                if(commands.observeForeignAcceptance().stage().equals("CANCELLED_HOLD")) return;
                assertNull(clientQueue.poll(10,TimeUnit.MILLISECONDS));
            }
            throw new AssertionError("Cancelled worker hold not entered");
        }
    }
    private static void join(Thread worker) throws InterruptedException { worker.join(5000); assertFalse(worker.isAlive(),"Worker must finish within original policy bound"); }
    private static Object get(Object owner,String name) throws Exception { var field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void set(Object owner,String name,Object value) throws Exception { var field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value); }
}
