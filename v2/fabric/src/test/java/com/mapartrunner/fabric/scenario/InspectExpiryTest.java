package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.placement.PlacementObservation;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only injected facts test the used finite policy, never native World/effect authority. */
class InspectExpiryTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize(); }
    @TempDir Path directory;
    final String[] gateNames={"mapart.test.inspect","mapart.test.inspect.acceptance","mapart.test.inspect.acceptance.case","fabric.client.gametest"};
    String[] oldGates;
    @BeforeEach void gates() {
        oldGates=Arrays.stream(gateNames).map(System::getProperty).toArray(String[]::new);
        System.setProperty(gateNames[0],"true"); System.setProperty(gateNames[1],"true");
        System.setProperty(gateNames[2],"work-expiry"); System.clearProperty(gateNames[3]);
    }
    @AfterEach void restoreGates() {
        for(int k=0;k<gateNames.length;k++) { if(oldGates[k]==null) System.clearProperty(gateNames[k]); else System.setProperty(gateNames[k],oldGates[k]); }
    }

    @Test void expiryResetUsesOrdinaryResetSelector() {
        for(var command:ScenarioHost.Command.values())
            assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,"work-expiry",command,1,0,true));
    }

    /** These are real issued Host scopes/wrappers/futures with injected native queues, never native-world credit. */
    final class QueueFixture {
        final AtomicLong now=new AtomicLong(1);
        final ThreadLocal<Boolean> nativeThread=ThreadLocal.withInitial(() -> false);
        final LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
        final AtomicBoolean refuseQueue=new AtomicBoolean();
        final List<FinalOutcome> outcomes=new CopyOnWriteArrayList<>();
        final InspectScenarioHost host=new InspectScenarioHost(now::get,nativeThread::get,() -> false,
                action -> { if(refuseQueue.get()) throw new RejectedExecutionException("expiry queue refused"); queue.add(action); },queue::add,outcomes::add);
        final ScenarioRunner runner;
        QueueFixture() throws Exception {
            var module=Files.createTempDirectory(directory,"module-"); var build=Files.createDirectories(module.resolve("build"));
            var game=Files.createDirectories(build.resolve("run/inspect"));
            runner=new ScenarioRunner(host,new ScenarioObserver.Watch(),new ScenarioRunner.Roots(module,game,build,build.resolve("scenario-results"),game.resolve("saves")));
        }
        Operation operation() { return new Operation(runner.inspectSessionId(),2,UUID.randomUUID(),Command.RUN,directory.resolve("synthetic")); }
        Runnable take() throws Exception {
            long cutoff=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<cutoff) { nativeRun(host::onTick); var value=queue.poll(10,TimeUnit.MILLISECONDS); if(value!=null) return value; }
            throw new AssertionError("Native callback not queued");
        }
        void nativeRun(Runnable action) { nativeThread.set(true); try { action.run(); } finally { nativeThread.remove(); } }
        Held hold() throws Exception {
            var work=host.beginWork(operation()); var primary=new AtomicReference<Throwable>(); var effects=new AtomicInteger();
            var identity=new PredictionFaults.ExpiryIdentity(UUID.randomUUID(),run,work.deadlineNanos(),1);
            var worker=new Thread(() -> {
                try { host.inScope(work,() -> { host.armExpiryPause(work.operation(),identity,runner); host.runOnClient(c -> effects.incrementAndGet()); }); }
                catch(Throwable failure) { primary.set(failure); }
            }); worker.start(); var wrapper=take(); nativeRun(wrapper);
            assertEquals("HELD",host.observeExpiryAcceptance().stage()); assertEquals(0,effects.get());
            return new Held(work,wrapper,worker,primary,effects);
        }
        void expire(Held held) throws Exception { now.set(held.work.deadlineNanos()); nativeRun(host::onTick); join(held.worker); assertInstanceOf(ScopeTimeoutException.class,held.primary.get()); }
    }
    record Held(Scope work,Runnable wrapper,Thread worker,AtomicReference<Throwable> primary,AtomicInteger effects) { }
    static void join(Thread worker) throws Exception { worker.join(5000); assertFalse(worker.isAlive(),"Bounded worker must finish"); }

    @Test void timelyRealDispatchEntersActionButSameExpiredEnteredWrapperRejectsBeforeAction() throws Exception {
        var timely=new QueueFixture(); var work=timely.host.beginWork(timely.operation()); var actions=new AtomicInteger(); var failure=new AtomicReference<Throwable>();
        var worker=new Thread(() -> { try { timely.host.inScope(work,() -> timely.host.runOnClient(c -> actions.incrementAndGet())); } catch(Throwable error) { failure.set(error); } });
        worker.start(); timely.nativeRun(timely.take()); join(worker); assertNull(failure.get()); assertEquals(1,actions.get());
        var f=new QueueFixture(); var held=f.hold(); f.expire(held);
        var replayFailure=new AtomicReference<Throwable>();
        var replayWorker=new Thread(() -> { try { f.host.replayExpiryWrapper(held.work,held.primary.get()); } catch(Throwable error) { replayFailure.set(error); } });
        replayWorker.start(); var replay=f.take(); assertSame(held.wrapper,replay);
        assertFalse(f.host.observeExpiryAcceptance().replayRejected(),"Submission is not acknowledgement");
        f.nativeRun(replay); join(replayWorker); assertNull(replayFailure.get());
        var observation=f.host.observeExpiryAcceptance(); assertTrue(observation.replayRejected()); assertEquals(1,observation.replayCount());
        assertEquals(2,observation.wrapperEntries()); assertEquals(0,observation.actionEntries()); assertEquals(0,held.effects.get());
        assertTrue(observation.distinctReplayFailure()); assertEquals(ScopeTimeoutException.class.getName(),observation.primary().className());
        assertEquals(ScopeTimeoutException.class.getName(),observation.replay().className());
        f.nativeRun(replay); assertEquals(observation,f.host.observeExpiryAcceptance());
        f.host.retireExpiryReplay(held.work); f.nativeRun(held.wrapper); assertEquals(0,held.effects.get());
        assertThrows(IllegalStateException.class,() -> f.host.replayExpiryWrapper(held.work,held.primary.get()));
        var cleanup=f.host.beginCleanup(held.work,held.primary.get()); assertEquals(held.work.deadlineNanos()+10_000_000_000L,cleanup.deadlineNanos());
        f.host.inScope(cleanup,() -> f.host.settleCleanup(cleanup,new FinalOutcome(held.work.operation(),Verdict.TIMEOUT,CleanupStatus.SETTLED)));
        assertEquals(1,f.outcomes.size());
        assertThrows(IllegalStateException.class,() -> f.host.beginCleanup(held.work,held.primary.get()));
    }
    @Test void prestartSpendsOriginalDeadlineAndNeverArmsAQueueOrRenewsWork() throws Exception {
        var f=new QueueFixture(); var work=f.host.beginWork(f.operation()); var failure=new AtomicReference<Throwable>();
        var worker=new Thread(() -> { try { f.host.inScope(work,() -> f.host.awaitExpiryStart(work.operation())); } catch(Throwable error) { failure.set(error); } });
        worker.start();
        long cutoff=System.nanoTime()+5_000_000_000L;
        while(f.host.waitingCount()==0 && System.nanoTime()<cutoff) Thread.yield();
        assertTrue(f.host.waitingCount()>0);
        assertTrue(f.queue.isEmpty()); f.now.set(work.deadlineNanos()-30_000_000_001L); f.nativeRun(f.host::onTick);
        assertTrue(worker.isAlive()); f.now.incrementAndGet(); f.nativeRun(f.host::onTick); join(worker); assertNull(failure.get());
        f.host.inScope(work,() -> assertSame(work,f.host.requireScope(work.operation(),Kind.WORK)));
        assertEquals(600_000_000_001L,work.deadlineNanos()); assertNull(f.host.observeExpiryAcceptance());
    }
    @Test void failedExpiryWaitsStillRetireAndAttemptTheOriginalSingleCleanupWithoutPermissionRenewal() throws Exception {
        for(String mode:List.of("disabled","cancelled","outer-expired","interrupted","stop","queue-refused")) {
            var f=new QueueFixture(); var held=f.hold(); f.expire(held); var primary=held.primary.get();
            var secondary=new AtomicReference<Throwable>(); var cleanup=new AtomicReference<Scope>(); var transitions=new AtomicInteger();
            var worker=new Thread(() -> {
                try { f.host.awaitExpiryReplayBeforeCleanup(held.work,primary); }
                catch(Throwable error) { primary.addSuppressed(error); secondary.set(error); }
                finally {
                    try { f.host.retireExpiryReplay(held.work); }
                    catch(Throwable error) { primary.addSuppressed(error); }
                    cleanup.set(f.host.beginCleanup(held.work,primary)); transitions.incrementAndGet();
                }
            });
            if(mode.equals("disabled")) System.setProperty(gateNames[1],"false");
            if(mode.equals("cancelled")) f.host.cancelSession(new IllegalArgumentException("foreign cancellation"));
            if(mode.equals("outer-expired")) f.now.set(held.work.deadlineNanos()+10_000_000_000L);
            if(mode.equals("stop")) f.nativeRun(() -> f.host.onStopping(null));
            if(mode.equals("queue-refused")) f.refuseQueue.set(true);
            worker.start();
            if(mode.equals("interrupted")) worker.interrupt();
            if(mode.equals("queue-refused")) {
                long cutoff=System.nanoTime()+5_000_000_000L;
                while(!"BEFORE_REPLAY_READ".equals(f.host.observeExpiryAcceptance().stage()) && worker.isAlive() && System.nanoTime()<cutoff) Thread.yield();
                f.nativeRun(f.host::onTick);
            }
            join(worker); assertSame(primary,held.primary.get()); assertNotNull(secondary.get(),mode); assertEquals(1,transitions.get());
            assertSame(held.work.operation(),cleanup.get().operation()); assertEquals(held.work.deadlineNanos()+10_000_000_000L,cleanup.get().deadlineNanos());
            assertThrows(IllegalStateException.class,() -> f.host.beginCleanup(held.work,primary));
            f.nativeRun(held.wrapper); assertEquals(0,held.effects.get()); assertTrue(f.host.observeExpiryAcceptance().boundaryRetired());
            System.setProperty(gateNames[1],"true");
        }
    }
    @Test void cancelledAndStaleOrWrongThreadWrappersCannotBecomeExpiryRejectionEvidence() throws Exception {
        var f=new QueueFixture(); var held=f.hold();
        f.host.cancelSession(new IllegalArgumentException("cancelled before expiry"));
        join(held.worker); assertInstanceOf(IllegalArgumentException.class,held.primary.get());
        f.now.set(held.work.deadlineNanos()); assertThrows(IllegalStateException.class,() -> f.host.replayExpiryWrapper(held.work,held.primary.get()));
        f.host.retireExpiryReplay(held.work); f.host.beginCleanup(held.work,held.primary.get()); f.nativeRun(held.wrapper);
        assertFalse(f.host.observeExpiryAcceptance().replayRejected()); assertEquals(0,held.effects.get());
        var g=new QueueFixture(); var other=g.hold(); g.expire(other);
        var foreign=new Scope(other.work.operation(),Kind.WORK,other.work.deadlineNanos());
        assertThrows(IllegalStateException.class,() -> g.host.replayExpiryWrapper(foreign,other.primary.get()));
        var failed=new AtomicReference<Throwable>();
        var replayWorker=new Thread(() -> { try { g.host.replayExpiryWrapper(other.work,other.primary.get()); } catch(Throwable error) { failed.set(error); } });
        replayWorker.start(); var same=g.take(); same.run(); assertFalse(g.host.observeExpiryAcceptance().replayRejected());
        g.nativeRun(same); join(replayWorker); assertNull(failed.get()); g.host.retireExpiryReplay(other.work);
    }

    @Test void pendingReadIsRetiredBeforeCleanupAndLateCallbacksCannotReadAReplacement() throws Exception {
        var f=new QueueFixture(); var held=f.hold(); f.expire(held); var failure=new AtomicReference<Throwable>();
        var worker=new Thread(() -> { try { f.host.awaitExpiryReplayBeforeCleanup(held.work,held.primary.get()); } catch(Throwable error) { failure.set(error); } });
        worker.start(); long cutoff=System.nanoTime()+5_000_000_000L;
        while(!"BEFORE_REPLAY_READ".equals(f.host.observeExpiryAcceptance().stage()) && System.nanoTime()<cutoff) Thread.yield();
        f.nativeRun(f.host::onTick); var request=f.take();
        f.host.abandonExpiryAcceptance(); join(worker); assertNotNull(failure.get());
        f.host.retireExpiryReplay(held.work); f.host.beginCleanup(held.work,held.primary.get());
        var retired=f.host.observeExpiryAcceptance(); f.nativeRun(request); f.nativeRun(held.wrapper);
        assertEquals(retired,f.host.observeExpiryAcceptance()); assertEquals(0,held.effects.get());
        assertEquals(0,f.queue.size());
    }

    final class PairFixture {
        final SupportFixture support=new SupportFixture();
        final PredictionFaults.ExpiryHold hold=new PredictionFaults.ExpiryHold(support.frame(),100);
        final UUID session=UUID.randomUUID(),operation=UUID.randomUUID();
        final TraversalCapture.Physics physics=new TraversalCapture.Physics(true,false,false,false,false,false,false,false,false,false,false,false,false,false,false,0,.08);
        final ScenarioRunner.ExpiryPose pose=new ScenarioRunner.ExpiryPose(11.5,66,4.5,0,Double.longBitsToDouble(0xBFB41205C28F5C29L),0,0,0,0,0,physics,false,false);
        final ScenarioRunner.ExpiryPair baseline=pair(0,10,10,10,10,10);
        ScenarioRunner.ExpirySide side(int age,long time,int tick,long nanos) {
            return new ScenarioRunner.ExpirySide(nanos,age,time,tick,pose,0,"all43 native component strings","cursor+crafting+handler",Map.of(new GridPos(11,65,4),support.stone),new GridPos(11,65,4));
        }
        ScenarioRunner.ExpiryPair pair(int ordinal,int ca,long ct,int sa,long st,int tick) {
            if(ordinal>0) hold.defer(new PredictionFaults.ExpiryFrame(adapter,client,server,"save",support.frame().binding(),support.held.status().engine().lifecycle(),BuildEngine.ControlOwner.NATIVE,neutral,support.frame().pose(),false,101+ordinal),true);
            var local=new ScenarioRunner.ExpiryClient(hold.snapshot(),support.held.status(),0,7,List.of(),support.plan.targets(),support.plan.fingerprint(),side(ca,ct,-1,ordinal==0?50:100+ordinal));
            return new ScenarioRunner.ExpiryPair(session,operation,support.context.runId(),hold.identity(),100,10_000_000_100L,ordinal,local,side(sa,st,tick,ordinal==0?50:100+ordinal));
        }
        ScenarioRunner.ExpiryPair client(ScenarioRunner.ExpiryPair p,ScenarioRunner.ExpiryClient c) {
            return new ScenarioRunner.ExpiryPair(p.sessionId(),p.operationId(),p.runId(),p.identity(),p.deadline(),p.cutoff(),p.ordinal(),c,p.server());
        }
        ScenarioRunner.ExpiryClient local(ScenarioRunner.ExpiryClient c,FabricBuildAdapter.Status status,int keys,int sequence,List<GridPos> pending,List<BuildPlan.Target> targets,String fingerprint,ScenarioRunner.ExpirySide side) {
            return new ScenarioRunner.ExpiryClient(c.hold(),status,keys,sequence,pending,targets,fingerprint,side);
        }
    }
    @Test void nativePairNeedsEveryIndependentClockAndKeepsSameProtectedStateAcrossEAndR() {
        var f=new PairFixture(); var e=f.pair(1,11,11,11,11,11); var r=f.pair(2,12,12,12,12,12);
        assertTrue(ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,e,null));
        assertTrue(ScenarioRunner.validateExpiryPair(f.baseline,e,r,null));
        for(int clock=0;clock<5;clock++) {
            int k=clock; var early=f.pair(1,k==0?10:11,k==1?10:11,k==2?10:11,k==3?10:11,k==4?10:11);
            assertFalse(ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,early,null));
        }
        assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,f.pair(1,9,11,11,11,11),null));
        assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,e,new ScenarioRunner.ExpiryClocks(12,11,11,11,11)));
        assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,e,e,null));
        var foreign=new ScenarioRunner.ExpiryPair(UUID.randomUUID(),e.operationId(),e.runId(),e.identity(),e.deadline(),e.cutoff(),e.ordinal(),e.client(),e.server());
        assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,foreign,null));
    }
    @Test void productionProofEncoderRetainsCompletePairsAdmissionAndPresentAndEmptyOptionals() {
        var f=new PairFixture(); var e=f.pair(1,11,11,11,11,11); var r=f.pair(2,12,12,12,12,12);
        var ack=new ScenarioCommands.ExpiryBusyAck(f.operation,1,110);
        var failure=new ScenarioCommands.FailureObservation(ScopeTimeoutException.class.getName(),"original WORK timeout");
        var boundary=new ScenarioCommands.ExpiryObservation(f.session,f.operation,f.support.context.runId(),2,"SEALED_BEFORE_END",100,10_000_000_100L,
                2,0,1,true,true,110,1,1,ack,failure,failure,true);
        var text=ScenarioRunner.encodeExpiryProof(List.of(f.baseline,e,r),f.baseline,f.baseline,2,1,1,1,boundary);
        var proof=JsonParser.parseString(text).getAsJsonObject();
        assertEquals(Set.of("pairs","firstAdmission","latestAdmission","admissionRequests","captureAttempts","releaseAttempts","freezeAttempts","boundary"),proof.keySet());
        assertEquals(2,proof.get("admissionRequests").getAsInt());
        for(String count:List.of("captureAttempts","releaseAttempts","freezeAttempts")) assertEquals(1,proof.get(count).getAsInt());
        var pairs=proof.getAsJsonArray("pairs"); assertEquals(3,pairs.size());
        assertEquals(pairs.get(0),proof.get("firstAdmission")); assertEquals(pairs.get(0),proof.get("latestAdmission"));
        for(int ordinal=0;ordinal<3;ordinal++) {
            var pair=pairs.get(ordinal).getAsJsonObject();
            assertEquals(Set.of("sessionId","operationId","runId","identity","deadline","cutoff","ordinal","client","server"),pair.keySet());
            assertEquals(f.session.toString(),pair.get("sessionId").getAsString()); assertEquals(f.operation.toString(),pair.get("operationId").getAsString());
            assertEquals(f.support.context.runId().toString(),pair.get("runId").getAsString()); assertEquals(ordinal,pair.get("ordinal").getAsInt());
            assertEquals(100,pair.get("deadline").getAsLong()); assertEquals(10_000_000_100L,pair.get("cutoff").getAsLong());
            var identity=pair.getAsJsonObject("identity");
            assertEquals(Set.of("arm","context","workDeadlineNanos","adapterIdentity"),identity.keySet());
            assertEquals(f.hold.identity().arm().toString(),identity.get("arm").getAsString());
            var client=pair.getAsJsonObject("client");
            assertEquals(Set.of("hold","status","keyMask","nativeSequence","pending","expectedTargets","planFingerprint","side"),client.keySet());
            assertEquals(0,client.get("keyMask").getAsInt()); assertEquals(7,client.get("nativeSequence").getAsInt());
            assertEquals(0,client.getAsJsonArray("pending").size()); assertEquals(2,client.getAsJsonArray("expectedTargets").size());
            assertEquals(f.support.plan.fingerprint(),client.get("planFingerprint").getAsString());
            var target=client.getAsJsonArray("expectedTargets").get(0).getAsJsonObject();
            assertEquals(Set.of("id","relativePos","block"),target.keySet());
            var hold=client.getAsJsonObject("hold");
            assertEquals(Set.of("identity","disposed","invalidity","samples","first","latest"),hold.keySet());
            if(ordinal==0) assertTrue(hold.get("invalidity").isJsonNull());
            else assertEquals("Original WORK expired",hold.get("invalidity").getAsString());
            for(String observation:List.of("first","latest")) {
                var observed=hold.getAsJsonObject(observation);
                assertEquals(Set.of("nanos","lifecycle","owner","keys","pose","sameNative","suspended"),observed.keySet());
                assertEquals(9,observed.getAsJsonArray("keys").size()); assertTrue(observed.get("sameNative").getAsBoolean());
            }
            var status=client.getAsJsonObject("status"); assertEquals(Set.of("engine","controlOwner","failure"),status.keySet());
            assertTrue(status.get("failure").isJsonNull());
            var engine=status.getAsJsonObject("engine");
            assertEquals(Set.of("lifecycle","eligibleTicks","verifyTicks","traversal","unfinishedTargetIds","pendingTargetIds","diagnostic","effectResults"),engine.keySet());
            assertTrue(engine.get("diagnostic").isJsonNull()); assertTrue(engine.get("traversal").isJsonObject());
            var lifecycle=engine.getAsJsonObject("lifecycle"); assertEquals(Set.of("phase","context","activity","outcome"),lifecycle.keySet());
            assertEquals("RUNNING",lifecycle.get("phase").getAsString()); assertEquals("SHIFT",lifecycle.get("activity").getAsString());
            assertEquals(f.support.context.runId().toString(),lifecycle.getAsJsonObject("context").get("runId").getAsString());
            assertTrue(lifecycle.get("outcome").isJsonNull());
            var traversal=engine.getAsJsonObject("traversal");
            assertEquals(Set.of("laneIndex","substage","entry","transitionTicks","transitionRegion","pass","walkNoProgressTicks"),traversal.keySet());
            assertTrue(traversal.has("transitionRegion"));
            for(var side:List.of(client.getAsJsonObject("side"),pair.getAsJsonObject("server"))) {
                assertEquals(Set.of("nanos","age","time","serverTick","pose","selectedSlot","inventory","menu","blocks","support"),side.keySet());
                assertEquals(10+ordinal,side.get("age").getAsInt()); assertEquals(10+ordinal,side.get("time").getAsLong());
                assertEquals(f.baseline.client().side().inventory(),side.get("inventory").getAsString());
                assertEquals(f.baseline.client().side().menu(),side.get("menu").getAsString());
                assertEquals(1,side.getAsJsonObject("blocks").size()); assertEquals(65,side.getAsJsonObject("support").get("y").getAsInt());
                var pose=side.getAsJsonObject("pose");
                assertEquals(Set.of("x","y","z","vx","vy","vz","yaw","headYaw","bodyYaw","pitch","physics","flying","sprinting"),pose.keySet());
                assertEquals(0xBFB41205C28F5C29L,Double.doubleToRawLongBits(pose.get("vy").getAsDouble()));
                assertEquals(Set.of("onGround","noGravity","noDrag","jumping","vehicle","fluid","water","submerged","lava","climbing","gliding","swimming","sneaking","usingItem","hasEffects","fallDistance","gravity"),pose.getAsJsonObject("physics").keySet());
            }
        }
        var retained=proof.getAsJsonObject("boundary");
        assertEquals("SEALED_BEFORE_END",retained.get("stage").getAsString()); assertEquals(0,retained.get("actionEntries").getAsInt());
        assertEquals(1,retained.get("busyRefusals").getAsInt()); assertTrue(retained.get("distinctReplayFailure").getAsBoolean());
        assertEquals(f.operation.toString(),retained.getAsJsonObject("acknowledgement").get("operationId").getAsString());
        assertEquals(ScopeTimeoutException.class.getName(),retained.getAsJsonObject("primary").get("className").getAsString());
        assertEquals(retained.get("primary"),retained.get("replay"));
    }
    @Test void completeCoreExpectedTargetsPendingAndEachProtectedNativeValueMustStayUnchanged() {
        var f=new PairFixture(); var e=f.pair(1,11,11,11,11,11); var c=e.client(); var engine=c.status().engine();
        var changedEngine=new BuildEngine.Snapshot(engine.lifecycle(),engine.eligibleTicks()+1,engine.verifyTicks(),engine.traversal(),engine.unfinishedTargetIds(),engine.pendingTargetIds(),engine.diagnostic(),engine.effectResults());
        var changedStatus=new FabricBuildAdapter.Status(changedEngine,c.status().controlOwner(),c.status().failure());
        for(var invalid:List.of(
                f.local(c,changedStatus,0,7,List.of(),c.expectedTargets(),c.planFingerprint(),c.side()),
                f.local(c,c.status(),1,7,List.of(),c.expectedTargets(),c.planFingerprint(),c.side()),
                f.local(c,c.status(),0,8,List.of(),c.expectedTargets(),c.planFingerprint(),c.side()),
                f.local(c,c.status(),0,7,List.of(new GridPos(11,65,5)),c.expectedTargets(),c.planFingerprint(),c.side()),
                f.local(c,c.status(),0,7,List.of(),List.of(),c.planFingerprint(),c.side()),
                f.local(c,c.status(),0,7,List.of(),c.expectedTargets(),"other",c.side())))
            assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,f.client(e,invalid),null));
        for(String change:List.of("inventory","menu","selected","block","support")) for(boolean local:List.of(true,false)) {
            var s=local?e.client().side():e.server();
            var changed=new ScenarioRunner.ExpirySide(s.nanos(),s.age(),s.time(),s.serverTick(),s.pose(),change.equals("selected")?1:s.selectedSlot(),
                    change.equals("inventory")?s.inventory()+" slot42 components changed":s.inventory(),change.equals("menu")?"other cursor":s.menu(),
                    change.equals("block")?Map.of(new GridPos(11,65,4),f.support.air):s.blocks(),change.equals("support")?new GridPos(12,65,4):s.support());
            var candidate=local?f.client(e,f.local(c,c.status(),0,7,List.of(),c.expectedTargets(),c.planFingerprint(),changed)):
                    new ScenarioRunner.ExpiryPair(e.sessionId(),e.operationId(),e.runId(),e.identity(),e.deadline(),e.cutoff(),e.ordinal(),c,changed);
            assertThrows(AssertionError.class,() -> ScenarioRunner.validateExpiryPair(f.baseline,f.baseline,candidate,null),change+" local="+local);
        }
    }
    @Test void expectedFirstExpiryDoesNotHideLaterGateKeyOwnershipOrBindingLoss() {
        var f=new PairFixture(); var e=f.pair(1,11,11,11,11,11); var snapshot=e.client().hold();
        assertEquals("Original WORK expired",snapshot.invalidity().orElseThrow());
        ScenarioRunner.requireExpiryHold(snapshot,e.identity(),e.client().status(),0,true,true);
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireExpiryHold(snapshot,e.identity(),e.client().status(),0,false,true));
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireExpiryHold(snapshot,e.identity(),e.client().status(),1,true,true));
        for(String changed:List.of("binding","owner","suspended","keys","disposed")) {
            var prior=snapshot.latest(); var pressed=new ArrayList<>(neutral); pressed.set(8,true);
            var latest=new PredictionFaults.ExpiryObservation(prior.nanos(),prior.lifecycle(),changed.equals("owner")?BuildEngine.ControlOwner.NONE:prior.owner(),
                    changed.equals("keys")?pressed:prior.keys(),prior.pose(),!changed.equals("binding"),changed.equals("suspended"));
            var invalid=new PredictionFaults.ExpirySnapshot(snapshot.identity(),changed.equals("disposed"),snapshot.invalidity(),snapshot.samples(),snapshot.first(),latest);
            assertThrows(AssertionError.class,() -> ScenarioRunner.requireExpiryHold(invalid,e.identity(),e.client().status(),0,true,true),changed);
        }
    }
    @Test void actualHoldPhysicsUsesFootprintAndRawPostPhysicsYWithoutFreezingBodyYaw() {
        var f=new PairFixture(); ScenarioRunner.requireExpiryPose(f.pose,f.support.frame().pose()); assertTrue(ScenarioRunner.expiryStationary(f.pose));
        var p=f.pose;
        ScenarioRunner.requireExpiryPose(new ScenarioRunner.ExpiryPose(p.x()+.25,p.y(),p.z(),0,p.vy(),0,90,75,50,0,p.physics(),false,false),f.support.frame().pose());
        for(double vy:new double[]{0,Double.NaN,-.08}) assertThrows(AssertionError.class,() -> ScenarioRunner.requireExpiryPose(
                new ScenarioRunner.ExpiryPose(p.x(),p.y(),p.z(),0,vy,0,0,0,0,0,p.physics(),false,false),f.support.frame().pose()));
        assertThrows(AssertionError.class,() -> ScenarioRunner.requireExpiryPose(new ScenarioRunner.ExpiryPose(p.x()+.25001,p.y(),p.z(),0,p.vy(),0,0,0,0,0,p.physics(),false,false),f.support.frame().pose()));
        assertFalse(ScenarioRunner.expiryStationary(new ScenarioRunner.ExpiryPose(p.x(),p.y(),p.z(),1e-6,p.vy(),0,0,0,0,0,p.physics(),false,false)));
    }
    @Test void realCommandsBusyRefusesWithoutReservationAndPublicationStillPrecedesWorkerIdle() throws Exception {
        var f=new QueueFixture(); var workers=new ArrayDeque<Runnable>();
        var commands=new ScenarioCommands(workers::add,f.host,f.runner); var publicationBusy=new AtomicBoolean(); var publication=new AtomicReference<ScenarioCommands.PublishedCompletion>();
        assertEquals(0,commands.submit(Command.RESET,null,s -> {}),"Native world guard still refuses synthetic Runner");
        assertEquals(1,commands.enqueue(Command.RESET,s -> { publicationBusy.set(commands.observeInspect().busy()); publication.set(commands.observeInspect().last().orElse(null)); }));
        for(var command:List.of(Command.RESET,Command.RUN,Command.RERUN)) assertEquals(0,commands.enqueue(command,s -> assertTrue(s.contains("busy"))));
        assertEquals(1,workers.size());
        var worker=new Thread(workers.remove()); worker.start(); f.nativeRun(f.take()); join(worker);
        assertTrue(publicationBusy.get()); assertNotNull(publication.get()); assertEquals(1,publication.get().ordinal());
        assertEquals(Command.RESET,publication.get().command()); assertEquals("FAIL",publication.get().verdict()); assertTrue(publication.get().cleanupSettled());
        assertFalse(commands.observeInspect().busy()); assertEquals(publication.get(),commands.observeInspect().last().orElseThrow());
        assertEquals(1,commands.enqueue(Command.RESET,s -> {}));
        var other=new QueueFixture(); assertThrows(IllegalArgumentException.class,() -> new ScenarioCommands(workers::add,other.host,f.runner));
    }
    @Test void sharedCleanupRecordsActualReleaseAndFreezeAttemptsOnceEvenWhenTheyThrow() {
        var operation=new Operation(UUID.randomUUID(),2,UUID.randomUUID(),Command.RUN,directory.resolve("effects"));
        var cleanup=new Scope(operation,Kind.CLEANUP,100); var owner=new ScenarioRunner.InspectEffects();
        var releases=new AtomicInteger(); var freezes=new AtomicInteger(); var releaseError=new IllegalStateException("release error");
        var first=owner.attempt(cleanup,() -> "before",() -> { releases.incrementAndGet(); throw releaseError; },freezes::incrementAndGet,() -> "after");
        assertEquals(1,first.releaseAttempts()); assertEquals(1,first.freezeAttempts()); assertSame(releaseError,first.releaseFailure());
        assertSame(first,owner.attempt(cleanup,() -> { throw new AssertionError("duplicate capture"); },releases::incrementAndGet,freezes::incrementAndGet,() -> "duplicate"));
        assertSame(first,owner.seal(cleanup,null)); assertEquals(1,releases.get()); assertEquals(1,freezes.get());
        var unavailable=new ScenarioRunner.InspectEffects().seal(cleanup,new IllegalStateException("not entered"));
        assertEquals(0,unavailable.releaseAttempts()); assertEquals(0,unavailable.freezeAttempts());
    }
    @Test void commandsRetainsActualWorkTimeoutWhenCleanupAdmissionOrSettlementThrowsLater() {
        var primary=new ScopeTimeoutException(); var late=new ScopeTimeoutException(); var retirement=new IllegalStateException("retirement failed");
        assertSame(primary,ScenarioCommands.retain(primary,retirement));
        assertSame(primary,ScenarioCommands.retain(primary,late));
        assertArrayEquals(new Throwable[]{retirement,late},primary.getSuppressed());
        assertSame(primary,ScenarioCommands.retain(primary,primary)); assertEquals(2,primary.getSuppressed().length);
        assertSame(retirement,ScenarioCommands.retain(null,retirement));
    }
    @Test void newWaitsRefuseNativeThreadsAndForeignRunnerCannotArmOriginalWork() throws Exception {
        var f=new QueueFixture(); var g=new QueueFixture(); var work=f.host.beginWork(f.operation());
        var identity=new PredictionFaults.ExpiryIdentity(UUID.randomUUID(),run,work.deadlineNanos(),1);
        f.host.inScope(work,() -> assertThrows(IllegalStateException.class,() -> f.host.armExpiryPause(work.operation(),identity,g.runner)));
        f.nativeRun(() -> {
            assertThrows(IllegalStateException.class,() -> f.host.awaitExpiryStart(work.operation()));
            assertThrows(IllegalStateException.class,() -> f.host.armExpiryPause(work.operation(),identity,f.runner));
            assertThrows(IllegalStateException.class,() -> f.host.awaitExpiryReplayBeforeCleanup(work,new ScopeTimeoutException()));
            assertThrows(IllegalStateException.class,() -> f.host.replayExpiryWrapper(work,new ScopeTimeoutException()));
            assertThrows(IllegalStateException.class,() -> f.host.awaitExpiryBusyProof(work.operation(),work));
        });
        assertNull(f.host.observeExpiryAcceptance()); assertTrue(f.queue.isEmpty());
    }
    ScenarioCommands.ExpiryObservation busyRequest(UUID operation,String stage,int refusals,long ordinal,long cutoff,ScenarioCommands.ExpiryBusyAck acknowledged) {
        return new ScenarioCommands.ExpiryObservation(UUID.randomUUID(),operation,run.runId(),2,stage,100,cutoff,2,0,1,true,true,
                110,ordinal,refusals,acknowledged,null,null,true);
    }
    @Test void fixedBusyAcknowledgementValuesRequireRecordedRefusalExactRequestAndBothOriginalDeadlines() {
        var operation=UUID.randomUUID(); var ack=new ScenarioCommands.ExpiryBusyAck(operation,1,110);
        var request=busyRequest(operation,"BUSY_REQUIRED",1,1,110,null);
        assertTrue(InspectScenarioHost.expiryBusyAckMatches(request,ack,100));
        assertTrue(InspectScenarioHost.expiryBusyAckMatches(request,ack,109));
        assertFalse(InspectScenarioHost.expiryBusyAckMatches(request,ack,110));
        assertFalse(InspectScenarioHost.expiryBusyAckMatches(busyRequest(operation,"BUSY_REQUIRED",1,1,109,null),ack,109));
        for(var wrong:List.of(new ScenarioCommands.ExpiryBusyAck(UUID.randomUUID(),1,110),new ScenarioCommands.ExpiryBusyAck(operation,2,110),
                new ScenarioCommands.ExpiryBusyAck(operation,1,111))) assertFalse(InspectScenarioHost.expiryBusyAckMatches(request,wrong,100));
        for(var invalid:List.of(busyRequest(operation,"BUSY_REQUIRED",0,1,110,null),busyRequest(operation,"BUSY_REQUIRED",1,2,110,null),
                busyRequest(operation,"BUSY_REQUIRED",1,1,110,ack),busyRequest(operation,"SEALED_BEFORE_END",1,1,110,null),
                busyRequest(operation,"INCOMPLETE",1,1,110,null))) assertFalse(InspectScenarioHost.expiryBusyAckMatches(invalid,ack,100));
        assertFalse(InspectScenarioHost.expiryBusyAckMatches(null,ack,100));
        assertFalse(InspectScenarioHost.expiryBusyAckMatches(request,null,100));
        // Pure value success neither enters a native callback nor completes a real Host request.
    }

    final Object adapter=new Object(), client=new Object(), server=new Object();
    final Object world=new Object(), player=new Object(), handler=new Object(), connection=new Object();
    final UUID playerId=UUID.randomUUID();
    final RunLifecycle.Context run=new RunLifecycle.Context(UUID.randomUUID(),"connection","world","minecraft:overworld","plan",new GridPos(0,65,0),"settings");
    final List<Boolean> neutral=Collections.nCopies(9,false);
    PredictionFaults.ExpiryFrame frame() { return frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,10); }
    PredictionFaults.ExpiryFrame frame(Object a,Object w,RunLifecycle.Context r,RunLifecycle.Phase phase,BuildEngine.ControlOwner owner,List<Boolean> keys,boolean suspended,long now) {
        var lifecycle=new RunLifecycle.Snapshot(phase,Optional.of(r),phase==RunLifecycle.Phase.TERMINAL?Optional.empty():Optional.of(RunLifecycle.Activity.SHIFT),phase==RunLifecycle.Phase.TERMINAL?Optional.of(RunLifecycle.Outcome.CANCELLED):Optional.empty());
        return new PredictionFaults.ExpiryFrame(a,client,server,"save",new ScenarioObserver.ClientBinding(w,player,handler,connection,playerId,r.dimension(),true,true,true),
                lifecycle,owner,keys,null,suspended,now);
    }
    @Test void onlyExactNeutralNativeRunningBindingCanLatch() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        assertTrue(hold.defer(frame(),true));
        assertFalse(hold.defer(frame(new Object(),world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,11),true));
        assertThrows(IllegalStateException.class,() -> new PredictionFaults.ExpiryHold(frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NONE,neutral,false,10),100));
        for(int key=0;key<9;key++) {
            var pressed=new ArrayList<>(neutral); pressed.set(key,true);
            assertThrows(IllegalStateException.class,() -> new PredictionFaults.ExpiryHold(frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,pressed,false,10),100));
        }
    }
    @Test void clockAndGateInvalidityNeverResumeOldActiveEffects() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        assertTrue(hold.defer(frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,101),false));
        assertEquals("Acceptance gate disabled",hold.snapshot().invalidity().orElseThrow());
        assertTrue(hold.defer(frame(),true));
        assertEquals(3,hold.snapshot().samples());
        assertEquals(10,hold.snapshot().first().nanos());
        assertEquals(10,hold.snapshot().latest().nanos());
        assertTrue(hold.retainsNativeReferences());
    }
    @Test void suspensionAndBindingLossAllowExistingLifecycleThenRequireTerminalDisposal() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        assertFalse(hold.defer(frame(adapter,new Object(),run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,11),true));
        assertTrue(hold.retainsNativeReferences());
        assertFalse(hold.defer(frame(adapter,world,run,RunLifecycle.Phase.PAUSED,BuildEngine.ControlOwner.NONE,neutral,true,12),true));
        assertThrows(IllegalStateException.class,() -> hold.retire(hold.identity(),frame()));
        var terminal=frame(adapter,world,run,RunLifecycle.Phase.TERMINAL,BuildEngine.ControlOwner.NONE,neutral,false,13);
        assertTrue(hold.retire(hold.identity(),terminal));
        assertFalse(hold.retire(hold.identity(),terminal));
        assertFalse(hold.retainsNativeReferences());
        assertFalse(hold.defer(frame(),true));
    }
    @Test void foreignRunNeverDefersAndCannotRetireCurrentRun() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        var other=new RunLifecycle.Context(UUID.randomUUID(),"connection","world",run.dimension(),"plan",run.origin(),"settings");
        assertFalse(hold.defer(frame(adapter,world,other,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,11),true));
        assertFalse(hold.retainsNativeReferences());
        assertThrows(IllegalStateException.class,() -> new PredictionFaults.ExpiryHold(frame(),10));
    }
    @Test void allNativeBindingComponentsReachSafePathAndCannotAuthorizeForeignTicks() {
        var original=frame();
        var changes=List.of(
                new ScenarioObserver.ClientBinding(new Object(),player,handler,connection,playerId,run.dimension(),true,true,true),
                new ScenarioObserver.ClientBinding(world,new Object(),handler,connection,playerId,run.dimension(),true,true,true),
                new ScenarioObserver.ClientBinding(world,player,new Object(),connection,playerId,run.dimension(),true,true,true),
                new ScenarioObserver.ClientBinding(world,player,handler,new Object(),playerId,run.dimension(),true,true,true),
                new ScenarioObserver.ClientBinding(world,player,handler,connection,playerId,run.dimension(),true,false,true),
                new ScenarioObserver.ClientBinding(world,player,handler,connection,playerId,run.dimension(),true,true,false));
        for(var changed:changes) {
            var hold=new PredictionFaults.ExpiryHold(original,100);
            var frame=new PredictionFaults.ExpiryFrame(adapter,client,server,"save",changed,original.lifecycle(),original.owner(),neutral,null,false,11);
            assertFalse(hold.defer(frame,true));
            assertTrue(hold.retainsNativeReferences());
            assertEquals("Native binding or suspension changed",hold.snapshot().invalidity().orElseThrow());
        }
    }
    @Test void lostLeaseOrPressedKeysAreReportedWithoutResumingOldCapture() {
        for(var owner:List.of(BuildEngine.ControlOwner.NONE,BuildEngine.ControlOwner.TRAVEL)) {
            var hold=new PredictionFaults.ExpiryHold(frame(),100);
            assertTrue(hold.defer(frame(adapter,world,run,RunLifecycle.Phase.RUNNING,owner,neutral,false,11),true));
            assertEquals("Native ownership or neutral keys lost",hold.snapshot().invalidity().orElseThrow());
        }
        var keys=new ArrayList<>(neutral); keys.set(0,true);
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        assertTrue(hold.defer(frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,keys,false,11),true));
        assertTrue(hold.retainsNativeReferences());
    }
    @Test void neverArmedMixinHooksAndExistingPostDecisionHookRemainInert() {
        assertFalse(PredictionFaults.deferAdapterTick(null));
        PredictionFaults.afterAdapterTick(null);
        PredictionFaults.afterDecision(null);
    }
    @Test void retainedSouthSupportHandoffConsumesOldFaultWithoutChangingLease() {
        var f=new SupportFixture();
        PredictionFaults.verifySupportGuards(f.plan,f.held);
        var frame=f.frame();
        var hold=PredictionFaults.prepareExpiryHold(null,frame,100,f.gate,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral,true);
        var released=PredictionFaults.releaseSupportEvidence(f.gate,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral);
        assertEquals(PredictionFaults.SupportState.RELEASED,released.state());
        assertEquals(f.gate.identity(),released.identity());
        assertEquals(BuildEngine.ControlOwner.NATIVE,hold.snapshot().first().owner());
        assertEquals(neutral,hold.snapshot().first().keys());
        assertTrue(hold.defer(frame,true));
        assertThrows(IllegalStateException.class,() -> PredictionFaults.prepareExpiryHold(hold,frame,100,f.gate,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral,true));
        var copy=new ScenarioObserver.Sample(f.held.tick(),"END",f.held.worldIdentity(),true,f.held.pose(),neutral,false,f.held.status(),f.held.observations());
        assertThrows(IllegalStateException.class,() -> PredictionFaults.prepareExpiryHold(null,frame,100,f.gate,copy,List.of(f.held),copy.status(),copy.pose(),neutral,true));
        assertThrows(IllegalStateException.class,() -> PredictionFaults.prepareExpiryHold(null,frame,100,released,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral,true));
        assertThrows(IllegalStateException.class,() -> PredictionFaults.prepareExpiryHold(null,frame,100,f.gate,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral,false));
        var terminal=new PredictionFaults.ExpiryFrame(adapter,client,server,"save",frame.binding(),
                new RunLifecycle.Snapshot(RunLifecycle.Phase.TERMINAL,Optional.of(f.context),Optional.empty(),Optional.of(RunLifecycle.Outcome.CANCELLED)),BuildEngine.ControlOwner.NONE,neutral,frame.pose(),false,20);
        assertTrue(hold.retire(hold.identity(),terminal));
        assertThrows(IllegalStateException.class,() -> PredictionFaults.prepareExpiryHold(hold,frame,100,f.gate,f.held,List.of(f.held),f.held.status(),f.held.pose(),neutral,true));
    }
    @Test void callbackFactsAdvanceWhileOnlyExactAdapterWorkIsDeferred() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100);
        for(int tick=11;tick<=200;tick++) {
            var f=frame(adapter,world,run,RunLifecycle.Phase.RUNNING,BuildEngine.ControlOwner.NATIVE,neutral,false,tick);
            assertTrue(hold.defer(f,true));
        }
        assertEquals(200,hold.snapshot().latest().nanos());
        assertEquals(191,hold.snapshot().samples());
        assertEquals("Original WORK expired",hold.snapshot().invalidity().orElseThrow());
        assertTrue(hold.retainsNativeReferences());
    }
    @Test void copiedRetirementIdentityAndPressedTerminalCannotDispose() {
        var hold=new PredictionFaults.ExpiryHold(frame(),100); var id=hold.identity();
        var copy=new PredictionFaults.ExpiryIdentity(id.arm(),id.context(),id.workDeadlineNanos(),id.adapterIdentity());
        var terminal=frame(adapter,world,run,RunLifecycle.Phase.TERMINAL,BuildEngine.ControlOwner.NONE,neutral,false,11);
        assertThrows(IllegalStateException.class,() -> hold.retire(copy,terminal));
        var pressed=new ArrayList<>(neutral); pressed.set(0,true);
        assertThrows(IllegalStateException.class,() -> hold.retire(id,frame(adapter,world,run,RunLifecycle.Phase.TERMINAL,BuildEngine.ControlOwner.NONE,pressed,false,11)));
        assertTrue(hold.retainsNativeReferences());
        assertTrue(hold.retire(id,terminal));
    }
    final class SupportFixture {
        final BuildEngine engine=new BuildEngine();
        final BuildEngine.Settings settings=BuildEngine.Settings.defaults();
        final BuildPlan.BlockSpec stone=new BuildPlan.BlockSpec("minecraft:stone",Map.of()),air=new BuildPlan.BlockSpec("minecraft:air",Map.of());
        final BuildPlan plan=new BuildPlan("expiry support policy",new BuildPlan.GridSize(12,1,10),List.of(
                new BuildPlan.Target(7,new GridPos(11,0,3),stone),new BuildPlan.Target(9,new GridPos(11,0,5),stone)));
        final RunLifecycle.Context context=new RunLifecycle.Context(UUID.randomUUID(),"connection","world",run.dimension(),plan.fingerprint(),run.origin(),settings.fingerprint());
        MovementPolicy.PlayerObservation pose=new MovementPolicy.PlayerObservation(.5,1,2.5,-90);
        long tick;
        final ScenarioObserver.Sample held;
        final PredictionFaults.SupportAvailabilitySnapshot gate;
        SupportFixture() {
            engine.start(plan,context,settings);
            step();step();step();pose=new MovementPolicy.PlayerObservation(11.5,1,2.5,-90);step();step();step();step();
            pose=new MovementPolicy.PlayerObservation(11.5,1,2.5,0);step();step();step();step();
            pose=new MovementPolicy.PlayerObservation(11.5,1,4.5,0);step();
            var p=new ScenarioObserver.Pose(11.5,66,4.5,Vec3d.ZERO,Vec3d.ZERO,(int)tick,tick,0,0,0,false,false,0,8,64);
            var status=new FabricBuildAdapter.Status(engine.snapshot(),BuildEngine.ControlOwner.NATIVE,Optional.empty());
            var observed=new PlacementObservation(context.runId(),9,OptionalLong.empty(),tick,PlacementObservation.Evidence.OBSERVED,Optional.of(air));
            held=new ScenarioObserver.Sample(tick,"END",System.identityHashCode(world),true,p,neutral,false,status,List.of(observed));
            var identity=new PredictionFaults.SupportArmIdentity(UUID.randomUUID(),context,"save",System.identityHashCode(server),System.identityHashCode(world),System.identityHashCode(player),System.identityHashCode(handler),System.identityHashCode(connection),9,new GridPos(11,65,5),stone,0,0);
            var capture=new PredictionFaults.SupportCapture(context,9,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,PredictionFaults.SupportPose.of(p),engine.snapshot().eligibleTicks()-1);
            gate=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.ARMED,false,Optional.empty(),false,1,Optional.of(capture),Optional.of(capture),Optional.empty());
        }
        void step() {
            engine.tick(new BuildEngine.TickInput(context.runId(),tick++,Optional.of(context),Optional.of(pose),
                    new MovementPolicy.SupportObservation(Map.of(new GridPos(11,0,3),new MovementPolicy.SupportSample(PlacementObservation.Evidence.OBSERVED,Optional.of(stone)),new GridPos(11,0,5),new MovementPolicy.SupportSample(PlacementObservation.Evidence.OBSERVED,Optional.of(air)))),
                    List.of(),Map.of(7,BuildEngine.PlacementAvailability.NO_USABLE_ITEM,9,BuildEngine.PlacementAvailability.NO_VALID_HIT),List.of()));
        }
        PredictionFaults.ExpiryFrame frame() {
            return new PredictionFaults.ExpiryFrame(adapter,client,server,"save",new ScenarioObserver.ClientBinding(world,player,handler,connection,playerId,run.dimension(),true,true,true),held.status().engine().lifecycle(),held.status().controlOwner(),neutral,PredictionFaults.SupportPose.of(held.pose()),false,10);
        }
    }
    @Test void privateCaseRequiresAllGatesAndAutomaticAbsence() {
        assertTrue(PredictionFaults.expiryEnabled("true","true",null,"work-expiry"));
        for(String[] values:List.of(new String[]{"false","true",null,"work-expiry"},new String[]{"true","false",null,"work-expiry"},new String[]{"true","true","false","work-expiry"},new String[]{"true","true",null,"baseline"}))
            assertFalse(PredictionFaults.expiryEnabled(values[0],values[1],values[2],values[3]));
    }
}

