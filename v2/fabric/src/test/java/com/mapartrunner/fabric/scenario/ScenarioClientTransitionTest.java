package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.BuildEngine.*;
import com.mapartrunner.core.run.ForwardTraversal;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Simulated queue scheduling around real engine decisions, not native gameplay acceptance. */
class ScenarioClientTransitionTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }

    @Test void splitResumeSamplingIncludesAnInterveningRunningDecision() {
        var h=new QueueFixture(); h.engine.pause(); var paused=h.sample();
        h.host.inScope(h.scope,() -> {
            h.host.runOnClient(c -> h.engine.resume(h.context));
            var late=h.host.computeOnClient(c -> h.sample());
            assertFalse(ScenarioRunner.retainedProgress(paused.sample().status().engine(),late.sample().status().engine()));
            assertEquals(paused.sample().status().engine().eligibleTicks()+1,late.sample().status().engine().eligibleTicks());
        });
    }

    @Test void resumeCapturesRetainedProgressBeforeTheNextQueuedDecision() {
        var h=new QueueFixture(); h.engine.pause(); var paused=h.sample();
        h.host.inScope(h.scope,() -> {
            var transition=ScenarioRunner.resumeOnClient(h.host,c -> h.sample(),c -> h.engine.resume(h.context),paused,h.context.runId());
            var resumed=transition.after();
            assertEquals(Phase.PAUSED,transition.before().sample().status().engine().lifecycle().phase());
            assertEquals(Phase.RUNNING,resumed.sample().status().engine().lifecycle().phase());
            assertTrue(ScenarioRunner.retainedProgress(paused.sample().status().engine(),resumed.sample().status().engine()));
            assertEquals(paused.sample().tick(),resumed.sample().tick());
            var later=h.host.computeOnClient(c -> h.sample());
            assertEquals(resumed.sample().status().engine().eligibleTicks()+1,later.sample().status().engine().eligibleTicks());
            assertEquals(resumed.sample().status().engine().traversal().orElseThrow().transitionTicks()+1,
                    later.sample().status().engine().traversal().orElseThrow().transitionTicks());
            assertEquals(resumed.sample().tick()+1,later.sample().tick());
        });
    }

    @Test void pauseUsesTheMovingClientStateImmediatelyBeforeItsAction() {
        var h=new QueueFixture(); var stale=h.sample(); h.queue.add(h::step);
        h.host.inScope(h.scope,() -> {
            var transition=ScenarioRunner.pauseOnClient(h.host,c -> h.sample(),c -> h.engine.pause(),h.context.runId());
            assertEquals(stale.sample().status().engine().eligibleTicks()+1,transition.before().sample().status().engine().eligibleTicks());
            assertTrue(ScenarioRunner.retainedProgress(transition.before().sample().status().engine(),transition.after().sample().status().engine()));
            assertEquals(Phase.PAUSED,transition.after().sample().status().engine().lifecycle().phase());
            assertEquals(transition.before().sample().tick(),transition.after().sample().tick());
        });
    }

    private static ScenarioRunner.ClientTransition armResume(QueueFixture h) {
        h.engine.pause(); var paused=h.sample();
        var transition=h.host.inScope(h.scope,() -> ScenarioRunner.resumeShift(h.host,c -> h.sample(),c -> h.engine.resume(h.context),
                paused,h.context.runId(),java.util.function.Function.identity(),(c,r) -> {
                    assertTrue(h.onClient); assertFalse(h.onServer);
                    h.watch.armResume(h.binding,r,h.binding.client());
                }));
        assertSame(transition.after().sample(),h.watch.retained().resume().transition().after().sample());
        return h.watch.retained().resume().transition();
    }

    @Test void freshMarkerOnlyClaimCapturesOnceAndCannotBeForgedReplayedOrRearmed() {
        var h=new QueueFixture(); assertNull(h.watch.beginServerRead());
        var transition=armResume(h); var marker=h.watch.retained().resume();
        assertSame(transition,marker.transition());
        var claim=h.watch.beginServerRead(); assertNotNull(claim); assertTrue(claim.publications().isEmpty()); assertSame(marker,claim.resume());
        assertNull(h.watch.beginServerRead());
        var forged=new ScenarioObserver.ServerRead(claim.binding(),claim.publications(),claim.callback(),claim.resume());
        var before=h.watch.retained(); h.commit(forged,6.1,710); h.watch.failRead(forged,new AssertionError("forged"));
        assertEquals(before,h.watch.retained());
        h.commit(claim,6.1,710); var retained=h.watch.retained();
        assertEquals(6.1,retained.lower().reading().z()); assertTrue(retained.samples().isEmpty()); assertTrue(retained.witnesses().isEmpty());
        assertSame(marker,retained.lower().marker()); assertEquals(claim.callback(),retained.lower().callback());
        h.commit(claim,7,711); h.watch.failRead(claim,new AssertionError("replay")); assertEquals(retained,h.watch.retained());
        assertNull(h.watch.beginServerRead());
        assertThrows(IllegalStateException.class,() -> h.watch.armResume(h.binding,transition,h.binding.client()));
        assertEquals(retained,h.watch.retained());
    }

    @Test void preArmInflightReadCannotAcquireResumeAndEndsDuringLowerReadWaitForNextClaim() {
        var h=new QueueFixture(); h.publishEnd(); var old=h.watch.beginServerRead();
        var transition=armResume(h); assertNull(old.resume()); h.commit(old,6.0,709);
        assertNull(h.watch.retained().lower());
        var lower=h.watch.beginServerRead(); assertSame(transition,h.watch.retained().resume().transition());
        assertNotNull(lower.resume()); assertTrue(lower.publications().isEmpty());
        h.step(); var end=h.publishEnd(); h.commit(lower,6.1,710);
        assertEquals(1,h.watch.retained().witnesses().size());
        var upper=h.watch.beginServerRead(); assertNull(upper.resume()); assertSame(end,upper.publications().getFirst().sample());
        h.commit(upper,6.2,713);
        assertNotSame(h.watch.retained().lower().reading(),h.watch.retained().witnesses().getLast().reading());
        assertNull(h.watch.retained().failure());
    }

    @Test void realPendingEndsShareLowerPhysicalReadWithoutBecomingAFreshUpper() {
        var h=new QueueFixture(); armResume(h); h.step(); var end=h.publishEnd();
        var lower=h.watch.beginServerRead(); assertSame(end,lower.publications().getFirst().sample());
        h.commit(lower,6.1,710); var retained=h.watch.retained();
        assertSame(retained.lower().reading(),retained.witnesses().getFirst().reading());
        assertEquals(retained.lower().callback(),retained.witnesses().getFirst().callback());
        assertNull(h.watch.beginServerRead());
    }

    @Test void resumeCaptureRejectsWrongGenerationNativeIdentityInvalidScopeAndRenewedProgress() {
        var seed=new QueueFixture(); var transition=armResume(seed);
        for(String change:List.of("unbound","generation","world","player","handler","connection","off-client","expiry","cancel","frozen","failure","context","clocks")) {
            var h=new QueueFixture(); var b=h.binding; var client=b.client(); var r=transition;
            // Use this run's valid atomic R before checking individual refusals on a fresh Watch.
            var own=armResume(h); h.watch.clear(); h.watch.bind(b); r=own;
            switch(change) {
                case "unbound" -> h.watch.clear();
                case "generation" -> b=new ScenarioObserver.Binding(UUID.randomUUID(),b.context(),b.client(),b.server(),b.inspect(),b.scope());
                case "world","player","handler","connection","off-client" -> client=new ScenarioObserver.ClientBinding(
                        change.equals("world")?new Object():client.world(),change.equals("player")?new Object():client.player(),
                        change.equals("handler")?new Object():client.handler(),change.equals("connection")?new Object():client.connection(),
                        client.playerId(),client.dimension(),!change.equals("off-client"),true,true);
                case "expiry" -> h.now=h.scope.deadlineNanos();
                case "cancel" -> h.host.cancelSession(new IllegalStateException("cancelled"));
                case "frozen" -> h.watch.freeze();
                case "failure" -> { h.publishEnd(); h.watch.failRead(h.watch.beginServerRead(),new AssertionError("first")); }
                case "context" -> r=transition;
                case "clocks" -> {
                    var after=own.after(); var sample=after.sample(); var e=sample.status().engine();
                    var changed=new BuildEngine.Snapshot(e.lifecycle(),e.eligibleTicks()+1,e.verifyTicks(),e.traversal(),e.unfinishedTargetIds(),e.pendingTargetIds(),e.diagnostic(),e.effectResults());
                    r=new ScenarioRunner.ClientTransition(own.before(),sample(after,new FabricBuildAdapter.Status(changed,after.sample().status().controlOwner(),Optional.empty()),sample.keys(),false,true,after.nativeSequence()));
                }
            }
            var expected=b; var facts=client; var changed=r; var before=h.watch.retained();
            assertThrows(IllegalStateException.class,() -> h.watch.armResume(expected,changed,facts),change);
            assertEquals(before,h.watch.retained(),change);
        }
    }

    @Test void resumeReadFailureAndInvalidScalarsLatchFirstCauseWithoutRetry() {
        for(String failure:List.of("throw","world","player","thread","nan","infinite","reach")) {
            var h=new QueueFixture(); armResume(h); var read=h.watch.beginServerRead();
            var b=h.binding.server();
            if(failure.equals("throw")) h.watch.failRead(read,new AssertionError("first read failure"));
            else {
                var facts=new ScenarioObserver.ServerBinding(b.server(),failure.equals("world")?new Object():b.world(),
                        failure.equals("player")?new Object():b.player(),b.playerId(),b.dimension(),!failure.equals("thread"),true);
                h.watch.commitServer(read,new ScenarioObserver.ServerReading(facts,11.5,66,failure.equals("nan")?Double.NaN:
                        failure.equals("infinite")?Double.POSITIVE_INFINITY:6.1,failure.equals("reach")?0:4.5,710,710,710));
            }
            var retained=h.watch.retained(); assertNotNull(retained.failure()); assertNull(retained.lower());
            h.commit(read,6.2,711); h.watch.failRead(read,new AssertionError("second cause"));
            assertNull(h.watch.beginServerRead()); assertEquals(retained,h.watch.retained());
        }
    }

    @Test void resumeCaptureDisarmsOnFreezeCloseClearExpiryAndCancellationBeforeOrDuringRead() {
        for(String ending:List.of("freeze","close","clear","expiry","cancel")) for(boolean claimed:List.of(false,true)) {
            var h=new QueueFixture(); armResume(h); var read=claimed?h.watch.beginServerRead():null;
            switch(ending) {
                case "freeze" -> h.watch.freeze();
                case "close" -> h.watch.closeWorld();
                case "clear" -> h.watch.clear();
                case "expiry" -> h.now=h.scope.deadlineNanos();
                case "cancel" -> h.host.cancelSession(new IllegalStateException("cancelled"));
            }
            var retained=h.watch.retained();
            if(read!=null) { h.commit(read,6.1,710); h.watch.failRead(read,new AssertionError("late")); }
            assertNull(h.watch.beginServerRead()); assertEquals(retained,h.watch.retained());
            if(ending.equals("clear")) {
                assertNull(retained.resume()); assertNull(retained.lower());
                var b=h.binding; var fresh=new ScenarioObserver.Binding(UUID.randomUUID(),b.context(),b.client(),b.server(),b.inspect(),b.scope());
                h.watch.bind(fresh); var current=h.watch.retained();
                if(read!=null) h.commit(read,7,711);
                assertEquals(current,h.watch.retained()); assertNull(h.watch.beginServerRead());
            }
        }
    }

    @Test void acceptedLowerAndFirstFailureSurviveArchiveFreezeAndCloseUntilClear() {
        var h=new QueueFixture(); armResume(h); h.commit(h.watch.beginServerRead(),6.1,710);
        var lower=h.watch.retained().lower(); h.step(); h.publishEnd(); var claim=h.watch.beginServerRead();
        var failure=new AssertionError("first after lower"); h.watch.failRead(claim,failure);
        h.watch.freeze(); h.watch.closeWorld(); var archived=h.watch.retained();
        assertSame(lower,archived.lower()); assertSame(failure,archived.failure()); assertNotNull(archived.resume());
        h.commit(claim,7,713); h.watch.failRead(claim,new AssertionError("late")); assertEquals(archived,h.watch.retained());
        h.watch.clear(); assertNull(h.watch.retained().lower()); assertNull(h.watch.retained().resume()); assertNull(h.watch.retained().failure());
    }

    @Test void staleWorkerShiftCannotSilentlySkipTheRequiredPause() {
        var h=new QueueFixture(); h.player=new PlayerObservation(11.5,1,7.5,0); h.queue.add(h::step);
        h.host.inScope(h.scope,() -> {
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.pauseOnClient(h.host,c -> h.sample(),
                    c -> fail("Pause action must not run after leaving moving SHIFT"),h.context.runId()));
            assertEquals("Pause checkpoint is not moving SHIFT",error.getMessage());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,h.engine.snapshot().traversal().orElseThrow().substage());
        });
    }

    @Test void committedEarlyPairPausesDespiteSimulatedLateAnchorProbe() {
        var h=new QueueFixture();
        var early=h.publishEnd(); h.commit(h.watch.beginServerRead(),2.7073187067667144,100);
        var paired=h.watch.retained().witnesses().getFirst();
        assertSame(early,paired.publication().sample());
        h.player=new PlayerObservation(11.5,1,2.879906705779667,0); h.step();
        var sparse=h.sample();
        var late=new ScenarioRunner.Live(sparse.sample(),sparse.client(),side(4.124400213160213,101,101,101),
                sparse.fault(),sparse.pending(),sparse.nativeSequence(),sparse.nativeIdentities());
        assertEquals(4.124400213160213,late.server().state().get("z").getAsDouble());
        h.player=new PlayerObservation(11.5,1,5.418212386981042,0); h.step();
        var events=new ArrayList<String>(); var captures=new AtomicInteger(); var pauses=new AtomicInteger();
        // Explicitly SIMULATED equality to the late anchor; RUN5's actual rejected probe is unknown.
        var probe=side(4.124400213160213,102,102,102);
        h.host.inScope(h.scope,() -> {
            var selected=ScenarioRunner.constructionCapture(h.host,c -> {
                events.add(captures.getAndIncrement()==0?"before":"after");
                var current=h.sample();
                if(captures.get()==1) h.queue.add(() -> { h.player=new PlayerObservation(11.5,1,6.713331235506347,0); h.step(); });
                return current;
            },c -> { events.add("pause"); pauses.incrementAndGet(); h.engine.pause(); },h.context.runId(),h.binding,h.watch::retained,null,true,20,
                    () -> h.host.computeOnServer(server -> { events.add("probe"); return probe; }));
            var saved=selected.toString();
            var checkpoint=h.host.computeOnServer(server -> side(6.065759008443429,103,103,103));
            assertEquals(6.065759008443429,checkpoint.state().get("z").getAsDouble());
            assertEquals(saved,selected.toString());
            if(selected.transition().isEmpty()) {
                var exited=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Already left SHIFT"),
                        h.context.runId(),h.binding,h.watch::retained,null,true,20,() -> side(6.713331235506347,104,104,104));
                assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,exited.before().sample().status().engine().traversal().orElseThrow().substage());
                assertTrue(exited.transition().isEmpty());
            }
            assertTrue(selected.transition().isPresent(),"First success never reached the required actual SHIFT pause");
            assertEquals(List.of("probe","before","pause","after"),events);
            var transition=selected.transition().orElseThrow();
            assertEquals(transition.before().sample().tick(),transition.after().sample().tick());
            assertTrue(ScenarioRunner.retainedProgress(transition.before().sample().status().engine(),transition.after().sample().status().engine()));
            assertEquals(transition.before().nativeSequence(),transition.after().nativeSequence());
            assertSame(paired,selected.anchor()); assertSame(probe,selected.admissionProbe()); assertEquals(1,pauses.get());
        });
    }

    @Test void onlyCommittedHistoryCanSupplyAnAnchorOnALaterChargedAttempt() {
        var h=new QueueFixture(); var captures=new AtomicInteger(); var waits=new ScenarioRunner.PhysicsAllowance(1200);
        h.host.inScope(h.scope,() -> {
            java.util.function.Supplier<ScenarioRunner.ConstructionCapture> unavailable=() -> {
                waits.waitTick(false,() -> { });
                var result=ScenarioRunner.constructionCapture(h.host,c -> { captures.incrementAndGet(); return h.sample(); },
                        c -> fail("Unpaired history"),h.context.runId(),h.binding,h.watch::retained,null,true,
                        waits.constructionLimit-waits.consumed(),() -> fail("No committed anchor means no probe"));
                assertTrue(result.transition().isEmpty()); assertNull(result.anchor()); assertNull(result.admissionProbe());
                return result;
            };
            unavailable.get(); h.publishEnd(); unavailable.get();
            var claim=h.watch.beginServerRead(); assertNotNull(claim); unavailable.get();
            h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var later=h.publishEnd(); // Published during the first outside-gate read: not part of that claim.
            h.commit(claim,2.5,100);
            assertEquals(1,h.watch.retained().witnesses().size());
            var laterClaim=h.watch.beginServerRead(); assertNotNull(laterClaim);
            assertSame(later,laterClaim.publications().getFirst().sample());
            waits.waitTick(false,() -> { });
            var result=ScenarioRunner.constructionCapture(h.host,c -> { captures.incrementAndGet(); return h.sample(); },
                    c -> h.engine.pause(),h.context.runId(),h.binding,h.watch::retained,null,true,
                    waits.constructionLimit-waits.consumed(),() -> side(3.5,101,101,101));
            assertTrue(result.transition().isPresent()); assertSame(claim.publications().getFirst().sample(),result.anchor().publication().sample());
            assertEquals(4,waits.consumed()); assertEquals(5,captures.get());
            assertEquals(1,h.watch.retained().witnesses().size());
            h.commit(laterClaim,3.5,102); assertEquals(2,h.watch.retained().witnesses().size());
            assertSame(claim.publications().getFirst().sample(),result.anchor().publication().sample());
        });
    }

    @Test void preProbeSnapshotIsReadOnceAndCommittedLaterPairsCannotReplaceAcceptedAnchor() {
        var h=new QueueFixture(); var first=h.anchor(); h.step(); h.publishEnd(); var pending=h.watch.beginServerRead();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step(); var reads=new AtomicInteger();
        h.host.inScope(h.scope,() -> {
            var refused=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Equal server position"),
                    h.context.runId(),h.binding,() -> { reads.incrementAndGet(); return h.watch.retained(); },null,true,20,
                    () -> { h.commit(pending,2.6,100); return side(2.5,101,101,101); });
            assertSame(first,refused.anchor()); assertTrue(refused.transition().isEmpty()); assertEquals(1,reads.get());
            var accepted=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),
                    h.context.runId(),h.binding,() -> { reads.incrementAndGet(); return h.watch.retained(); },refused.anchor(),true,20,
                    () -> side(3.5,101,101,101));
            assertSame(first,accepted.anchor()); assertTrue(accepted.transition().isPresent()); assertEquals(2,reads.get());
        });
        var exited=new QueueFixture(); exited.publishEnd(); var claim=exited.watch.beginServerRead();
        exited.player=new PlayerObservation(11.5,1,6.713331235506347,0); exited.step();
        exited.commit(claim,2.5,100);
        exited.host.inScope(exited.scope,() -> {
            var result=ScenarioRunner.constructionCapture(exited.host,c -> exited.sample(),c -> fail("Pair committed after SHIFT exit"),
                    exited.context.runId(),exited.binding,exited.watch::retained,null,true,20,() -> side(4,101,101,101));
            assertNotNull(result.anchor()); assertTrue(result.transition().isEmpty());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,result.before().sample().status().engine().traversal().orElseThrow().substage());
        });
    }

    @Test void aPairCommittedAfterTheSnapshotWaitsForTheNextChargedCapture() {
        var h=new QueueFixture(); h.publishEnd(); var claim=h.watch.beginServerRead();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step(); var reads=new AtomicInteger();
        h.host.inScope(h.scope,() -> {
            var unavailable=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Commit was after snapshot"),
                    h.context.runId(),h.binding,() -> {
                        reads.incrementAndGet(); var snapshot=h.watch.retained(); h.commit(claim,2.5,100); return snapshot;
                    },null,true,20,() -> fail("No probe for the frozen unpaired snapshot"));
            assertNull(unavailable.anchor()); assertNull(unavailable.admissionProbe()); assertTrue(unavailable.transition().isEmpty());
            var later=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),h.context.runId(),h.binding,
                    () -> { reads.incrementAndGet(); return h.watch.retained(); },unavailable.anchor(),true,20,() -> side(3.5,101,101,101));
            assertTrue(later.transition().isPresent()); assertEquals(2,reads.get());
        });
    }

    @Test void earliestShiftEndMayHaveReleasedControlsAndAliasedReadingIsStillOneAnchor() {
        var h=new QueueFixture(); var s=h.sample().sample();
        var idle=new ScenarioObserver.Sample(s.tick(),"END",s.worldIdentity(),true,s.pose(),Collections.nCopies(9,false),false,
                new FabricBuildAdapter.Status(s.status().engine(),ControlOwner.NONE,Optional.empty()),List.of());
        h.watch.append(h.binding,idle,h.binding.client(),4.5); h.step(); var second=h.publishEnd();
        h.commit(h.watch.beginServerRead(),2.5,100);
        var points=h.watch.retained().witnesses(); assertSame(points.getFirst().reading(),points.getLast().reading());
        assertSame(second,points.getLast().publication().sample());
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),h.context.runId(),h.binding,
                    h.watch::retained,null,true,20,() -> side(3.5,101,101,101));
            assertSame(points.getFirst(),result.anchor()); assertSame(idle,result.anchor().publication().sample());
            assertTrue(result.transition().isPresent());
        });
    }

    @Test void clientAndIndependentServerMustBothStrictlyExceedQuarterBlockFromPairedAnchor() {
        for(boolean clientBoundary:List.of(false,true)) {
            for(double z:new double[]{Math.nextDown(2.75),2.75,Math.nextUp(2.75),Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
                var h=new QueueFixture(); h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
                var current=h.sample();
                var positioned=new ScenarioRunner.Live(current.sample(),side(clientBoundary?z:3.5,101,101,0),current.server(),
                        current.fault(),current.pending(),current.nativeSequence(),current.nativeIdentities());
                h.host.inScope(h.scope,() -> {
                    var result=ScenarioRunner.constructionCapture(h.host,c -> h.engine.snapshot().lifecycle().phase()==Phase.PAUSED?h.sample():positioned,
                            c -> h.engine.pause(),h.context.runId(),h.binding,h.watch::retained,null,true,20,
                            () -> side(clientBoundary?3.5:z,101,101,101));
                    assertEquals(z==Math.nextUp(2.75),result.transition().isPresent(),"clientBoundary="+clientBoundary+" z="+z);
                });
            }
        }
    }

    @Test void historyCannotAuthorizeEqualFrameOrRegressedServerTickOrCurrentNonNativeState() {
        for(String bad:List.of("sameFrame","serverTick","owner","forward","back","jump","sneak","paused","reverse","activity","context")) {
            var h=new QueueFixture(); var anchor=h.anchor();
            if(!bad.equals("sameFrame")) { h.player=new PlayerObservation(11.5,1,3.5,0); h.step(); }
            var current=h.sample(); var snapshot=current.sample().status().engine();
            if(bad.equals("sameFrame")) current=new ScenarioRunner.Live(current.sample(),side(3.5,100,100,0),current.server(),
                    current.fault(),current.pending(),current.nativeSequence(),current.nativeIdentities());
            if(bad.equals("context")) {
                var run=h.context; var changed=new Context(run.runId(),run.connectionId(),run.worldId(),run.dimension(),run.planFingerprint(),
                        new GridPos(1,65,0),run.settingsFingerprint());
                current=state(current,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(changed),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null);
            } else if(bad.equals("activity")) {
                current=state(current,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(h.context),Optional.of(Activity.BUILD),Optional.empty()),null,null,null);
            } else if(bad.equals("paused")) {
                current=state(current,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.PAUSED,Optional.of(h.context),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null);
            } else if(bad.equals("reverse")) {
                var t=snapshot.traversal().orElseThrow();
                current=state(current,null,null,null,new ForwardTraversal.Snapshot(t.laneIndex(),t.substage(),t.entry(),t.transitionTicks(),t.transitionRegion(),ForwardTraversal.Pass.REVERSE,t.walkNoProgressTicks()));
            } else if(bad.equals("owner")) {
                current=sample(current,new FabricBuildAdapter.Status(snapshot,ControlOwner.NONE,Optional.empty()),current.sample().keys(),false,true,current.nativeSequence());
            } else if(List.of("forward","back","jump","sneak").contains(bad)) {
                var keys=new ArrayList<>(current.sample().keys()); keys.set(switch(bad) { case "back" -> 1; case "jump" -> 4; case "sneak" -> 6; default -> 0; },!bad.equals("forward"));
                current=sample(current,current.sample().status(),keys,false,true,current.nativeSequence());
            }
            var candidate=current;
            h.host.inScope(h.scope,() -> {
                java.util.function.Supplier<ScenarioRunner.ConstructionCapture> action=() -> ScenarioRunner.constructionCapture(h.host,c -> candidate,
                        c -> fail("Invalid current state: "+bad),h.context.runId(),h.binding,h.watch::retained,null,true,20,
                        () -> side(4,100,100,bad.equals("serverTick")?anchor.reading().serverTick()-1:100));
                if(bad.equals("context")) assertEquals("Changed current SHIFT context/WORK",assertThrows(AssertionError.class,action::get).getMessage());
                else assertTrue(action.get().transition().isEmpty(),bad);
            });
        }
    }

    @Test void malformedOrReconstructedHistoryFailsBeforeProbeWithoutSparseFallback() {
        var h=new QueueFixture(); var anchor=h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        var good=h.watch.retained(); var end=anchor.publication().sample(); var reading=anchor.reading();
        var copy=new ScenarioObserver.Sample(end.tick(),end.phase(),end.worldIdentity(),end.originalWorld(),end.pose(),end.keys(),end.paused(),end.status(),end.observations());
        var binding=h.binding;
        var copiedBinding=new ScenarioObserver.Binding(binding.generation(),binding.context(),binding.client(),binding.server(),binding.inspect(),binding.scope());
        var foreignServer=new ScenarioObserver.ServerBinding(new Object(),reading.binding().world(),reading.binding().player(),reading.binding().playerId(),reading.binding().dimension(),true,true);
        var invalid=new ArrayList<ScenarioObserver.Watch.Retained>();
        invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,null,good.witnesses(),null,null));
        invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,copiedBinding,good.witnesses(),null,null));
        invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),new IllegalStateException("latched"),binding,good.witnesses(),null,null));
        invalid.add(new ScenarioObserver.Watch.Retained(List.of(copy),null,binding,good.witnesses(),null,null));
        var wrongContext=new Context(h.context.runId(),h.context.connectionId(),h.context.worldId(),h.context.dimension(),
                h.context.planFingerprint(),new GridPos(1,65,0),h.context.settingsFingerprint());
        var foreign=state(h.sample(),new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(wrongContext),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null).sample();
        invalid.add(new ScenarioObserver.Watch.Retained(List.of(foreign),null,binding,good.witnesses(),null,null));
        var nonEnd=new ScenarioObserver.Sample(end.tick(),"START",end.worldIdentity(),true,end.pose(),end.keys(),false,end.status(),end.observations());
        invalid.add(new ScenarioObserver.Watch.Retained(List.of(nonEnd),null,binding,
                List.of(new ScenarioObserver.ServerWitness(new ScenarioObserver.EndPublication(binding,nonEnd,4.5),anchor.callback(),reading)),null,null));
        var publications=List.of(new ScenarioObserver.EndPublication(copiedBinding,end,4.5),new ScenarioObserver.EndPublication(binding,copy,4.5),
                new ScenarioObserver.EndPublication(binding,end,Double.NaN),new ScenarioObserver.EndPublication(binding,end,0));
        for(var publication:publications) invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,binding,
                List.of(new ScenarioObserver.ServerWitness(publication,anchor.callback(),reading)),null,null));
        for(var badReading:List.of(new ScenarioObserver.ServerReading(foreignServer,11.5,1,2.5,4.5,1,1,1),
                new ScenarioObserver.ServerReading(binding.server(),11.5,1,2.5,Double.POSITIVE_INFINITY,1,1,1)))
            invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,binding,List.of(new ScenarioObserver.ServerWitness(anchor.publication(),anchor.callback(),badReading)),null,null));
        invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,binding,List.of(anchor,anchor),null,null));
        invalid.add(new ScenarioObserver.Watch.Retained(good.samples(),null,binding,List.of(new ScenarioObserver.ServerWitness(anchor.publication(),0,reading)),null,null));
        h.host.inScope(h.scope,() -> {
            for(var retained:invalid) {
                var error=assertThrows(AssertionError.class,() -> ScenarioRunner.constructionCapture(h.host,c -> fail("Malformed history must fail before capture"),
                        c -> fail("No fallback"),h.context.runId(),binding,() -> retained,null,true,20,() -> fail("Malformed history must fail before probe")));
                assertTrue(List.of("Changed original SHIFT observation binding","Missing/failed paired native observation binding",
                        "Changed full native context","Foreign publication/generation/native binding","Invalid actual native reach",
                        "Duplicate/reordered native frame/server callback/tick").stream().anyMatch(error.getMessage()::startsWith),error.getMessage());
            }
            var reconstructed=new ScenarioObserver.ServerWitness(anchor.publication(),anchor.callback(),anchor.reading());
            assertEquals("Missing exact retained SHIFT anchor",assertThrows(AssertionError.class,() -> ScenarioRunner.constructionCapture(h.host,c -> fail("Reconstructed anchor"),c -> fail("No pause"),
                    h.context.runId(),binding,h.watch::retained,reconstructed,true,20,() -> fail("No probe"))).getMessage());
        });
        h.watch.clear(); h.watch.bind(copiedBinding); // Same Context/generation value, different exact arm.
        h.host.inScope(h.scope,() -> assertEquals("Changed original SHIFT observation binding",assertThrows(AssertionError.class,() -> ScenarioRunner.constructionCapture(h.host,c -> fail("Replaced arm"),c -> fail("No pause"),
                h.context.runId(),binding,h.watch::retained,null,true,20,() -> fail("No probe"))).getMessage()));
    }

    @Test void nonEndOrNonShiftAndNonfiniteHistoricalCoordinatesNeverSupplyAnAnchor() {
        for(String bad:List.of("START","nonShift","clientNaN","serverNaN")) {
            var h=new QueueFixture(); var s=h.sample().sample();
            if(bad.equals("START")) h.watch.append(h.binding,new ScenarioObserver.Sample(s.tick(),"START",s.worldIdentity(),true,s.pose(),s.keys(),false,s.status(),List.of()),h.binding.client(),4.5);
            else {
                if(bad.equals("nonShift")) {
                    var t=s.status().engine().traversal().orElseThrow();
                    s=state(h.sample(),null,null,null,new ForwardTraversal.Snapshot(t.laneIndex(),ForwardTraversal.Substage.ENDPOINT,t.entry(),t.transitionTicks(),t.transitionRegion(),t.pass(),t.walkNoProgressTicks())).sample();
                }
                var p=s.pose();
                if(bad.equals("clientNaN")) p=new ScenarioObserver.Pose(p.x(),p.y(),Double.NaN,p.velocity(),p.eyes(),p.age(),p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable());
                var end=new ScenarioObserver.Sample(s.tick(),"END",s.worldIdentity(),true,p,s.keys(),false,s.status(),List.of());
                h.watch.append(h.binding,end,h.binding.client(),4.5); h.commit(h.watch.beginServerRead(),bad.equals("serverNaN")?Double.NaN:2.5,100);
            }
            h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            h.host.inScope(h.scope,() -> {
                var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail(bad),h.context.runId(),h.binding,h.watch::retained,null,true,20,() -> fail("No finite SHIFT pair: "+bad));
                assertNull(result.anchor()); assertTrue(result.transition().isEmpty());
            });
        }
    }

    @Test void constructionSelectionPausesBeforeTheQueuedShiftExit() {
        var h=new QueueFixture(); var anchor=h.anchor();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        var events=new ArrayList<String>(); var pauses=new AtomicInteger(); var captures=new AtomicInteger();
        var probe=side(3.25,41,42,43);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> {
                events.add(captures.getAndIncrement()==0?"before":"after");
                var selected=h.sample();
                if(captures.get()==1) h.queue.add(() -> { h.player=new PlayerObservation(11.5,1,7.5,0); h.step(); });
                return selected;
            },c -> { events.add("pause"); pauses.incrementAndGet(); h.engine.pause(); },h.context.runId(),h.binding,h.watch::retained,anchor,true,20,
                    () -> h.host.computeOnServer(server -> { assertFalse(h.onClient); events.add("probe"); return probe; }));
            var transition=result.transition().orElseThrow();
            assertEquals(List.of("probe","before","pause","after"),events);
            assertSame(result.before(),transition.before()); assertSame(probe,result.admissionProbe());
            assertEquals(Phase.PAUSED,transition.after().sample().status().engine().lifecycle().phase());
            assertEquals(transition.before().sample().tick(),transition.after().sample().tick());
            assertTrue(ScenarioRunner.retainedProgress(transition.before().sample().status().engine(),transition.after().sample().status().engine()));
            assertEquals(transition.before().nativeSequence(),transition.after().nativeSequence());
            var retained=transition.toString(); var admission=probe.toString();
            var laterServer=h.host.computeOnServer(server -> { assertFalse(h.onClient); return side(9,90,90,90); });
            assertEquals(9,Double.parseDouble(laterServer.state().get("z").getAsString()));
            assertEquals(retained,transition.toString()); assertEquals(admission,result.admissionProbe().toString());
            h.engine.resume(h.context);
            var later=h.host.computeOnClient(c -> h.sample());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,later.sample().status().engine().traversal().orElseThrow().substage());
            assertEquals(retained,transition.toString()); assertEquals(1,pauses.get());
        });
    }

    @Test void constructionIneligibilityDoesNotAdmitPauseOrReplaceTheOriginalAnchor() {
        var h=new QueueFixture(); var anchor=h.anchor();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        h.host.inScope(h.scope,() -> {
            for(double z:new double[]{2.5,2.7,2.75,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
                var rejected=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Server displacement did not qualify"),
                        h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> side(z,20,20,20));
                assertTrue(rejected.transition().isEmpty());
            }
            // Failed samples are not anchors: +.5 from original qualifies, although latest client was already at3.5.
            var accepted=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),
                    h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> side(3,20,20,20));
            assertTrue(accepted.transition().isPresent());
        });
        for(double z:new double[]{2.5,2.7,2.75,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            var client=new QueueFixture(); var start=client.anchor(); client.step();
            // Simulate Side data (including malformed positions) without inventing a nonfinite core PlayerObservation.
            var candidate=client.sample();
            var positioned=new ScenarioRunner.Live(candidate.sample(),side(z,20,20,0),candidate.server(),candidate.fault(),
                    candidate.pending(),candidate.nativeSequence(),candidate.nativeIdentities());
            client.host.inScope(client.scope,() -> {
                var result=ScenarioRunner.constructionCapture(client.host,c -> positioned,c -> fail("Client displacement did not qualify"),
                        client.context.runId(),client.binding,client.watch::retained,start,true,20,() -> side(4,20,20,20));
                assertTrue(result.transition().isEmpty());
            });
        }
    }

    @Test void constructionSkipsProbeWithoutPendingAnchorAndRefusesAlreadyExitedShift() {
        for(boolean pending:List.of(false,true)) {
            var h=new QueueFixture(); var anchor=pending?null:h.anchor();
            h.host.inScope(h.scope,() -> {
                var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("No admission"),
                        h.context.runId(),h.binding,h.watch::retained,anchor,pending,20,() -> fail("No prior pending anchor"));
                assertTrue(result.transition().isEmpty()); assertNull(result.admissionProbe());
            });
        }
        var h=new QueueFixture(); var anchor=h.anchor();
        h.player=new PlayerObservation(11.5,1,7.5,0); h.queue.add(h::step);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Already NEXT_ENTRY"),
                    h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> side(4,20,20,20));
            assertTrue(result.transition().isEmpty());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,result.before().sample().status().engine().traversal().orElseThrow().substage());
        });
    }

    @Test void constructionProbeAndAuthorityFailuresPropagateEvenBeforeAdmission() {
        var probeFailure=new IllegalStateException("server authority sentinel");
        var h=new QueueFixture(); var anchor=h.anchor();
        h.host.inScope(h.scope,() -> assertSame(probeFailure,assertThrows(IllegalStateException.class,
                () -> ScenarioRunner.constructionCapture(h.host,c -> fail("Capture after failed probe"),c -> fail("No pause"),
                        h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> h.host.computeOnServer(server -> { throw probeFailure; })))));
        var foreign=new Context(UUID.randomUUID(),h.context.connectionId(),h.context.worldId(),h.context.dimension(),
                h.context.planFingerprint(),h.context.origin(),h.context.settingsFingerprint());
        var bad=new ArrayList<ScenarioRunner.Live>(); var original=h.sample();
        bad.add(state(original,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(foreign),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null));
        bad.add(sample(original,original.sample().status(),original.sample().keys(),false,false,original.nativeSequence()));
        bad.add(sample(original,new FabricBuildAdapter.Status(original.sample().status().engine(),ControlOwner.NATIVE,
                Optional.of(new FabricBuildAdapter.Failure(h.context.runId(),101,"sentinel-adapter","failure",Optional.empty()))),
                original.sample().keys(),false,true,original.nativeSequence()));
        for(var invalid:bad) h.host.inScope(h.scope,() -> {
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.constructionCapture(h.host,c -> invalid,c -> fail("Invalid authority"),
                    h.context.runId(),h.binding,h.watch::retained,null,false,20,() -> fail("Not pending")));
            assertTrue(error.getMessage().startsWith("Foreign run/world or adapter failure"));
        });
        var captureFailure=new IllegalStateException("client binding/save sentinel");
        h.host.inScope(h.scope,() -> assertSame(captureFailure,assertThrows(IllegalStateException.class,
                () -> ScenarioRunner.constructionCapture(h.host,c -> { throw captureFailure; },c -> fail("No pause"),
                        h.context.runId(),h.binding,h.watch::retained,null,false,20,() -> fail("Not pending")))));
    }

    @Test void constructionUsesOriginalRemainingRequestsAndNeverRetriesAdmittedFailures() {
        for(int remaining:List.of(19,20)) {
            var h=new QueueFixture(); var anchor=h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var waits=new ScenarioRunner.PhysicsAllowance(remaining+1); var requests=new AtomicInteger(); var pauses=new AtomicInteger();
            waits.waitTick(false,requests::incrementAndGet);
            h.host.inScope(h.scope,() -> {
                java.util.function.Supplier<ScenarioRunner.ConstructionCapture> attempt=() -> ScenarioRunner.constructionCapture(h.host,c -> h.sample(),
                        c -> { pauses.incrementAndGet(); h.engine.pause(); },h.context.runId(),h.binding,h.watch::retained,anchor,true,
                        waits.constructionLimit-waits.consumed(),() -> side(4,20,20,20));
                if(remaining==19) assertEquals("SHIFT pause would exceed the shared run physics budget",assertThrows(AssertionError.class,attempt::get).getMessage());
                else assertTrue(attempt.get().transition().isPresent());
                assertEquals(remaining==20?1:0,pauses.get()); assertEquals(1,requests.get()); assertEquals(1,waits.consumed());
            });
        }
        for(String failure:List.of("action","afterCapture","afterState","postServer","postEvidence")) {
            var h=new QueueFixture(); var anchor=h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var sentinel=new IllegalStateException(failure); var pauses=new AtomicInteger(); var captures=new AtomicInteger();
            h.host.inScope(h.scope,() -> {
                Class<? extends Throwable> expected=failure.equals("afterState")?AssertionError.class:IllegalStateException.class;
                var error=assertThrows(expected,() -> {
                    var result=ScenarioRunner.constructionCapture(h.host,c -> {
                        if(captures.incrementAndGet()==2 && failure.equals("afterCapture")) throw sentinel;
                        return h.sample();
                    },c -> { pauses.incrementAndGet(); if(failure.equals("action")) throw sentinel;
                        if(!failure.equals("afterState")) h.engine.pause(); },h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> side(4,20,20,20));
                    assertTrue(result.transition().isPresent());
                    // Attachment/evidence are worker work after the exact returned pair; failure must leave cleanup in charge.
                    assertFalse(h.onClient);
                    if(failure.equals("postServer")) h.host.computeOnServer(server -> { throw sentinel; });
                    if(failure.equals("postEvidence")) throw sentinel;
                });
                if(!failure.equals("afterState")) assertSame(sentinel,error);
                else assertTrue(error.getMessage().startsWith("Paused controls/progress/issuance changed"));
                assertEquals(1,pauses.get());
                if(failure.startsWith("post")) assertEquals(Phase.PAUSED,h.engine.snapshot().lifecycle().phase());
            });
        }
    }

    @Test void constructionPreservesHostCancellationAndScopeExpiryBeforeAndAfterAdmission() {
        for(boolean expire:List.of(false,true)) for(boolean after:List.of(false,true)) {
            var h=new QueueFixture(); var anchor=h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var sentinel=new IllegalStateException("cancel sentinel"); var pauses=new AtomicInteger();
            Runnable invalidate=() -> { if(expire) h.now=h.scope.deadlineNanos(); else h.host.cancelSession(sentinel); };
            h.host.inScope(h.scope,() -> {
                if(!after) h.queue.add(invalidate);
                Class<? extends Throwable> expected=expire?ScenarioHost.ScopeTimeoutException.class:IllegalStateException.class;
                var error=assertThrows(expected,
                        () -> ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> {
                            pauses.incrementAndGet(); h.engine.pause(); invalidate.run();
                        },h.context.runId(),h.binding,h.watch::retained,anchor,true,20,() -> side(4,20,20,20)));
                if(!expire) assertSame(sentinel,error);
                assertEquals(after?1:0,pauses.get());
            });
        }
    }

    @Test void admittedAtomicPairAndProbeEarnNoCreditBeforeTwentyAdvancingHolds() {
        var h=new QueueFixture(); h.anchor(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        var waits=new ScenarioRunner.PhysicsAllowance(22); var requests=new AtomicInteger();
        waits.waitTick(false,requests::incrementAndGet);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),
                    h.context.runId(),h.binding,h.watch::retained,null,true,21,() -> side(4,100,100,100));
            var paused=result.transition().orElseThrow().after();
            var held=clocks(paused,100,100,100,100,100,100);
            var queue=new ArrayDeque<ScenarioRunner.Live>(); queue.add(held);
            for(int n=1;n<=20;n++) queue.add(clocks(held,100+n,100+n,100+n,100+n,100+n,100+n));
            var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>(); var accepted=held;
            assertEquals(1,requests.get()); assertEquals(1,waits.consumed());
            for(int n=0;n<20;n++) {
                accepted=ScenarioRunner.awaitPhysics(accepted,held,h.context.runId(),waits,false,n,
                        requests::incrementAndGet,queue::remove,() -> { },rows::add);
                if(n==18) { assertEquals(19,rows.stream().filter(ScenarioRunner.PhysicsAttempt::accepted).count()); assertEquals(1,queue.size()); }
            }
            assertEquals(21,rows.size()); assertFalse(rows.getFirst().accepted()); assertEquals(0,rows.getFirst().predicates());
            assertEquals("PASS",rows.getFirst().invariant()); assertEquals(20,rows.stream().filter(ScenarioRunner.PhysicsAttempt::accepted).count());
            assertEquals(22,requests.get()); assertEquals(22,waits.consumed()); assertEquals(120,accepted.server().serverTick());
        });
    }

    private static ScenarioRunner.Side side(double z,int age,long time,int serverTick) {
        var json=new com.google.gson.JsonObject(); json.addProperty("x","11.5"); json.addProperty("y","1.0"); json.addProperty("z",Double.toString(z));
        return new ScenarioRunner.Side(json,age,time,serverTick,List.of());
    }

    @Test void aReplacementEngineDuringResumeStillRejectsRenewedClocksAndTraversal() {
        var h=new QueueFixture(); h.engine.pause(); var paused=h.sample();
        h.host.inScope(h.scope,() -> {
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.resumeShift(h.host,c -> h.sample(),c -> {
                h.engine=new BuildEngine(); h.engine.start(h.plan,h.context,h.settings);
            },paused,h.context.runId(),java.util.function.Function.identity(),(c,r) -> fail("Invalid R must not arm capture")));
            assertEquals("Manual resume renewed traversal or clocks",error.getMessage());
            assertEquals(0,h.engine.snapshot().eligibleTicks());
            assertEquals(0,h.engine.snapshot().traversal().orElseThrow().transitionTicks());
            assertNull(h.watch.retained().resume());
        });
    }

    @Test void ordinaryPhysicsWaitRejectsClientOnlySampleAndKeepsAcceptedAnchor() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var clientOnly=clocks(held,101,101,101,100,100,100);
        var independent=clocks(held,102,102,102,101,101,101);
        var candidates=new ArrayDeque<>(List.of(clientOnly,independent));
        var waits=new ScenarioRunner.PhysicsAllowance(40); var requests=new AtomicInteger(); var guards=new AtomicInteger();
        var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>();
        var accepted=ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,0,
                requests::incrementAndGet,candidates::remove,guards::incrementAndGet,rows::add);
        assertSame(independent,accepted); assertEquals(2,requests.get()); assertEquals(2,waits.consumed());
        assertEquals(2,guards.get()); assertEquals(3,rows.getFirst().predicates());
        assertFalse(rows.getFirst().accepted()); assertTrue(rows.getLast().accepted());
        assertEquals(100,rows.getLast().anchor().clientTime());
        assertEquals(101,rows.getFirst().candidate().poseAge());
        assertEquals(Phase.PAUSED,rows.getFirst().state().phase()); assertEquals(0,rows.getFirst().state().keys());
        assertEquals(held.nativeSequence(),rows.getFirst().state().nativeSequence());
        assertTrue(rows.getFirst().state().runValid()); assertTrue(rows.getFirst().state().retainedProgress());
    }

    @Test void rollbackMustExceedTheAcceptedAnchorRatherThanTheLastRejectedTime() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var schedule=new ArrayDeque<>(List.of(clocks(held,101,101,97,101,101,101),clocks(held,102,102,99,102,102,102),
                clocks(held,103,103,100,103,103,103),clocks(held,104,104,101,104,104,104)));
        var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>(); var waits=new ScenarioRunner.PhysicsAllowance(40);
        var accepted=ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,3,() -> { },schedule::remove,() -> { },rows::add);
        assertEquals(101,accepted.client().time()); assertEquals(4,waits.consumed());
        assertEquals(List.of(29,29,29,31),rows.stream().map(ScenarioRunner.PhysicsAttempt::predicates).toList());
        assertEquals(List.of(100L,100L,100L,100L),rows.stream().map(r -> r.anchor().clientTime()).toList());
        assertEquals(List.of(97L,99L,100L,101L),rows.stream().map(r -> r.candidate().clientTime()).toList());
    }

    @Test void eachStrictClockPredicateRefusesBothEqualityAndRollback() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        for(int bit=0;bit<5;bit++) for(int value:List.of(99,100)) {
            var waits=new ScenarioRunner.PhysicsAllowance(1); var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>();
            var candidate=clocks(held,101,bit==0?value:101,bit==1?value:101,bit==2?value:101,
                    bit==3?value:101,bit==4?value:101);
            var requests=new AtomicInteger();
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,0,
                    requests::incrementAndGet,() -> candidate,() -> { },rows::add));
            assertTrue(error.getMessage().startsWith("Physics request allowance exhausted"),error.toString());
            assertEquals(31-(1<<bit),rows.getFirst().predicates()); assertFalse(rows.getFirst().accepted());
            assertEquals(1,requests.get()); assertEquals(1,rows.size()); assertEquals(1,waits.consumed());
        }
    }

    @Test void rejectedClockCandidatesCannotHideAnyPausedInvariant() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var corruptions=new ArrayList<UnaryOperator<ScenarioRunner.Live>>();
        corruptions.add(s -> state(s,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,
                s.sample().status().engine().lifecycle().context(),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null));
        corruptions.add(s -> sample(s,s.sample().status(),s.sample().keys(),true,true,s.nativeSequence()));
        for(int key=0;key<9;key++) { int at=key; corruptions.add(s -> {
            var keys=new ArrayList<>(s.sample().keys()); keys.set(at,true);
            return sample(s,s.sample().status(),keys,false,true,s.nativeSequence());
        }); }
        for(var owner:List.of(ControlOwner.NATIVE,ControlOwner.TRAVEL)) corruptions.add(s -> sample(s,
                new FabricBuildAdapter.Status(s.sample().status().engine(),owner,Optional.empty()),s.sample().keys(),false,true,s.nativeSequence()));
        corruptions.add(s -> sample(s,s.sample().status(),s.sample().keys(),false,true,s.nativeSequence()+1));
        corruptions.add(s -> state(s,null,s.sample().status().engine().eligibleTicks()+1,null,null));
        corruptions.add(s -> state(s,null,null,s.sample().status().engine().verifyTicks()+1,null));
        var traversal=held.sample().status().engine().traversal().orElseThrow();
        corruptions.add(s -> state(s,null,null,null,new ForwardTraversal.Snapshot(traversal.laneIndex(),traversal.substage(),
                traversal.entry(),traversal.transitionTicks()+1,traversal.transitionRegion(),traversal.pass(),traversal.walkNoProgressTicks())));
        var foreign=new Context(UUID.randomUUID(),h.context.connectionId(),h.context.worldId(),h.context.dimension(),
                h.context.planFingerprint(),h.context.origin(),h.context.settingsFingerprint());
        corruptions.add(s -> state(s,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.PAUSED,Optional.of(foreign),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null));
        var replacedWorld=new Context(h.context.runId(),h.context.connectionId(),"replaced-world",h.context.dimension(),
                h.context.planFingerprint(),h.context.origin(),h.context.settingsFingerprint());
        corruptions.add(s -> state(s,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.PAUSED,Optional.of(replacedWorld),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null));
        corruptions.add(s -> state(s,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.PAUSED,Optional.of(h.context),Optional.of(Activity.BUILD),Optional.empty()),null,null,null));
        corruptions.add(s -> sample(s,s.sample().status(),s.sample().keys(),false,false,s.nativeSequence()));
        corruptions.add(s -> sample(s,new FabricBuildAdapter.Status(s.sample().status().engine(),ControlOwner.NONE,
                Optional.of(new FabricBuildAdapter.Failure(h.context.runId(),101,"sentinel-adapter","failure",Optional.empty()))),s.sample().keys(),false,true,s.nativeSequence()));
        for(var corruption:corruptions) {
            var bad=corruption.apply(clocks(held,101,101,101,100,100,100));
            var waits=new ScenarioRunner.PhysicsAllowance(40); var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>();
            var captures=new AtomicInteger();
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,0,
                    () -> { },() -> { assertEquals(0,captures.getAndIncrement(),"Bad candidate retried"); return bad; },() -> { },rows::add));
            assertTrue(error.getMessage().startsWith("Paused controls/progress/issuance changed")
                    || error.getMessage().startsWith("Foreign run/world or adapter failure"),error.toString());
            assertEquals(1,captures.get()); assertEquals(1,waits.consumed()); assertEquals(3,rows.getFirst().predicates());
            assertFalse(rows.getFirst().accepted()); assertTrue(rows.getFirst().invariant().startsWith("FAIL:"));
            assertEquals(Phase.PAUSED,h.engine.snapshot().lifecycle().phase());
        }
    }

    @Test void twentyAcceptedIntervalsShareEveryRequestWithConstructionAndKeepHoldCreditExact() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var waits=new ScenarioRunner.PhysicsAllowance(30); var requests=new AtomicInteger(); var guards=new AtomicInteger();
        waits.waitTick(false,requests::incrementAndGet);
        var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>(); var accepted=new ArrayList<ScenarioRunner.Live>();
        var queue=new ArrayDeque<ScenarioRunner.Live>();
        for(int n=1;n<=20;n++) {
            if(n%5==0) queue.add(clocks(held,100+n,100+n,100+n,99+n,99+n,99+n));
            queue.add(clocks(held,100+n,100+n,100+n,100+n,100+n,100+n));
        }
        var anchor=held;
        for(int n=0;n<20;n++) { anchor=ScenarioRunner.awaitPhysics(anchor,held,h.context.runId(),waits,false,n,
                requests::incrementAndGet,queue::remove,guards::incrementAndGet,rows::add); accepted.add(anchor); }
        assertEquals(20,accepted.size()); assertEquals(24,rows.size()); assertEquals(24,guards.get());
        assertEquals(25,requests.get()); assertEquals(25,waits.consumed());
        assertEquals(20,rows.stream().filter(ScenarioRunner.PhysicsAttempt::accepted).count());
        for(int n=0;n<20;n++) assertEquals(101+n,accepted.get(n).server().serverTick());
        waits.waitTick(false,requests::incrementAndGet); assertEquals(26,requests.get()); assertEquals(26,waits.consumed());
        var twenty=new ScenarioRunner.PhysicsAllowance(20); twenty.requirePauseRoom();
        twenty.waitTick(false,() -> { });
        var refusal=assertThrows(AssertionError.class,twenty::requirePauseRoom);
        assertEquals("SHIFT pause would exceed the shared run physics budget",refusal.getMessage());
        assertEquals(1,twenty.consumed());
    }

    @Test void stalledServerSpendsEveryRequestAndRefusesWithoutAFifthHostCall() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var waits=new ScenarioRunner.PhysicsAllowance(4); var requests=new AtomicInteger();
        var guards=new AtomicInteger(); var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>();
        var error=assertThrows(AssertionError.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,0,
                requests::incrementAndGet,() -> clocks(held,100+requests.get(),100+requests.get(),100+requests.get(),100,100,100),
                guards::incrementAndGet,rows::add));
        assertTrue(error.getMessage().startsWith("Physics request allowance exhausted"),error.toString());
        assertTrue(error.getMessage().contains("charged=false")); assertTrue(error.getMessage().contains("candidate=null"));
        assertEquals(4,requests.get()); assertEquals(4,guards.get()); assertEquals(4,waits.consumed()); assertEquals(4,rows.size());
        assertTrue(rows.stream().allMatch(row -> !row.accepted() && row.predicates()==3 && row.anchor().clientTime()==100));
    }

    @Test void evidenceFailureKeepsOriginalGuardAndAbsentCandidateWithoutLaterRecapture() {
        var h=new QueueFixture(); h.engine.pause(); var held=clocks(h.sample(),100,100,100,100,100,100);
        var next=clocks(held,101,101,101,100,100,100); var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>();
        var original=new IllegalStateException("fresh binding sentinel"); var sink=new IllegalStateException("sink sentinel");
        var waits=new ScenarioRunner.PhysicsAllowance(5);
        var actual=assertThrows(IllegalStateException.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),waits,false,0,
                () -> { },() -> next,() -> { throw original; },row -> { rows.add(row); throw sink; }));
        assertSame(original,actual); assertTrue(List.of(actual.getSuppressed()).contains(sink));
        assertEquals(101,rows.getFirst().candidate().observerTick()); assertFalse(rows.getFirst().accepted());
        assertEquals(1,waits.consumed());
        var timeout=new ScenarioHost.ScopeTimeoutException(); var absent=new ArrayList<ScenarioRunner.PhysicsAttempt>();
        var charged=new ScenarioRunner.PhysicsAllowance(5);
        assertSame(timeout,assertThrows(ScenarioHost.ScopeTimeoutException.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),charged,false,0,
                () -> { throw timeout; },() -> fail("Capture after failed wait"),() -> fail("Guard without sample"),absent::add)));
        assertEquals(1,charged.consumed()); assertNull(absent.getFirst().candidate()); assertEquals(-1,absent.getFirst().predicates());
        assertEquals(100,absent.getFirst().anchor().clientTime());
        assertTrue(List.of(timeout.getSuppressed()).stream().anyMatch(t -> t.getMessage().contains("candidate=null")));
        var evidenceOnly=new IllegalStateException("evidence only sentinel");
        assertSame(evidenceOnly,assertThrows(IllegalStateException.class,() -> ScenarioRunner.awaitPhysics(held,held,h.context.runId(),
                new ScenarioRunner.PhysicsAllowance(2),false,0,() -> { },() -> clocks(held,101,101,101,101,101,101),() -> { },row -> { throw evidenceOnly; })));
    }

    @Test void terminalReleaseUsesOnlyUnusedConstructionPlusTwentyAndConstructionCannotBorrow() {
        var h=new QueueFixture(); var terminal=h.successfulTerminal();
        for(int[] allocation:List.of(new int[]{9,1,21},new int[]{10,1,20},new int[]{8,2,22},new int[]{10,0,20})) {
            var waits=new ScenarioRunner.PhysicsAllowance(10); var requests=new AtomicInteger();
            for(int n=0;n<allocation[0];n++) waits.waitTick(false,requests::incrementAndGet);
            var queue=new ArrayDeque<ScenarioRunner.Live>();
            for(int n=0;n<allocation[1];n++) queue.add(clocks(terminal,101+n,101+n,101+n,100,100,100));
            for(int n=1;n<=20;n++) queue.add(clocks(terminal,110+n,110+n,110+n,100+n,100+n,100+n));
            var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>(); var anchor=terminal; int accepted=0;
            try {
                for(;accepted<20;accepted++) anchor=ScenarioRunner.awaitPhysics(anchor,terminal,h.context.runId(),waits,true,accepted,
                        requests::incrementAndGet,queue::remove,() -> { },rows::add);
                assertFalse(allocation[0]==10 && allocation[1]==1,"An extra release request was granted");
            } catch(AssertionError error) {
                assertEquals(10,allocation[0]); assertEquals(1,allocation[1]);
                assertTrue(error.getMessage().startsWith("Physics request allowance exhausted"),error.toString()); assertEquals(19,accepted);
            }
            assertEquals(allocation[0]+allocation[2],requests.get()); assertEquals(requests.get(),waits.consumed());
            assertEquals(allocation[2],rows.size()); assertTrue(waits.consumed()<=30);
        }
        var full=new ScenarioRunner.PhysicsAllowance(1); full.waitTick(false,() -> { });
        var refusal=assertThrows(AssertionError.class,() -> ScenarioRunner.awaitPhysics(terminal,terminal,h.context.runId(),full,false,0,
                () -> fail("Construction borrowed release20"),() -> fail("No candidate after exhaustion"),() -> { },row -> fail("No charged row for refusal")));
        assertTrue(refusal.getMessage().startsWith("Physics request allowance exhausted")); assertEquals(1,full.consumed());
        assertTrue(ScenarioRunner.ordinaryPhysics(true,true));
        assertFalse(ScenarioRunner.ordinaryPhysics(false,true)); assertFalse(ScenarioRunner.ordinaryPhysics(true,false));
        assertFalse(ScenarioRunner.ordinaryPhysics(false,false));
    }

    @Test void terminalRejectedCandidateStillChecksExactEngineSequenceKeysOwnerAndNativePause() {
        var h=new QueueFixture(); var terminal=h.successfulTerminal();
        var next=clocks(terminal,101,101,101,100,100,100);
        var mutations=new ArrayList<ScenarioRunner.Live>();
        mutations.add(state(next,null,next.sample().status().engine().eligibleTicks()+1,null,null));
        mutations.add(sample(next,next.sample().status(),next.sample().keys(),false,true,next.nativeSequence()+1));
        mutations.add(sample(next,next.sample().status(),next.sample().keys(),true,true,next.nativeSequence()));
        for(int k=0;k<9;k++) { var keys=new ArrayList<>(next.sample().keys()); keys.set(k,true); mutations.add(sample(next,next.sample().status(),keys,false,true,next.nativeSequence())); }
        for(var owner:List.of(ControlOwner.NATIVE,ControlOwner.TRAVEL)) mutations.add(sample(next,
                new FabricBuildAdapter.Status(next.sample().status().engine(),owner,Optional.empty()),next.sample().keys(),false,true,next.nativeSequence()));
        for(var bad:mutations) {
            var rows=new ArrayList<ScenarioRunner.PhysicsAttempt>(); var requests=new AtomicInteger();
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.awaitPhysics(terminal,terminal,h.context.runId(),new ScenarioRunner.PhysicsAllowance(5),true,0,
                    requests::incrementAndGet,() -> bad,() -> { },rows::add));
            assertEquals("Terminal release20 changed engine/controls/native issuance",error.getMessage());
            assertEquals(1,requests.get()); assertFalse(rows.getFirst().accepted()); assertEquals(3,rows.getFirst().predicates());
        }
    }

    private static ScenarioRunner.Live state(ScenarioRunner.Live source,com.mapartrunner.core.run.RunLifecycle.Snapshot lifecycle,
                                              Long eligible,Long verify,ForwardTraversal.Snapshot traversal) {
        var before=source.sample().status().engine();
        var engine=new BuildEngine.Snapshot(lifecycle==null?before.lifecycle():lifecycle,eligible==null?before.eligibleTicks():eligible,
                verify==null?before.verifyTicks():verify,traversal==null?before.traversal():Optional.of(traversal),
                before.unfinishedTargetIds(),before.pendingTargetIds(),before.diagnostic(),before.effectResults());
        return sample(source,new FabricBuildAdapter.Status(engine,source.sample().status().controlOwner(),source.sample().status().failure()),
                source.sample().keys(),source.sample().paused(),source.sample().originalWorld(),source.nativeSequence());
    }

    private static ScenarioRunner.Live sample(ScenarioRunner.Live source,FabricBuildAdapter.Status status,List<Boolean> keys,
                                               boolean paused,boolean originalWorld,int sequence) {
        var s=source.sample();
        return new ScenarioRunner.Live(new ScenarioObserver.Sample(s.tick(),s.phase(),s.worldIdentity(),originalWorld,s.pose(),keys,paused,status,s.observations()),
                source.client(),source.server(),source.fault(),source.pending(),sequence,source.nativeIdentities());
    }

    static ScenarioRunner.Live clocks(ScenarioRunner.Live state,long observer,int clientAge,long clientTime,
                                               int serverAge,long serverTime,int serverTick) {
        var sample=state.sample(); var p=sample.pose();
        var pose=new ScenarioObserver.Pose(p.x(),p.y(),p.z(),p.velocity(),p.eyes(),clientAge,clientTime,
                p.yaw(),p.headYaw(),p.bodyYaw(),p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable());
        return new ScenarioRunner.Live(new ScenarioObserver.Sample(observer,sample.phase(),sample.worldIdentity(),sample.originalWorld(),pose,
                sample.keys(),sample.paused(),sample.status(),sample.observations()),
                new ScenarioRunner.Side(new com.google.gson.JsonObject(),clientAge,clientTime,0,List.of()),
                new ScenarioRunner.Side(new com.google.gson.JsonObject(),serverAge,serverTime,serverTick,List.of()),
                state.fault(),state.pending(),state.nativeSequence(),state.nativeIdentities());
    }

    static final class QueueFixture {
        BuildEngine engine=new BuildEngine();
        final Settings settings=Settings.defaults();
        final BlockSpec stone=new BlockSpec("minecraft:stone",Map.of());
        final BuildPlan plan=new BuildPlan("queue boundary",new GridSize(12,1,10),
                List.of(new Target(7,new GridPos(11,0,3),stone)));
        final Context context=new Context(UUID.randomUUID(),"simulated-connection","simulated-world","minecraft:overworld",
                plan.fingerprint(),new GridPos(0,65,0),settings.fingerprint());
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final InspectScenarioHost host;
        final ScenarioHost.Scope scope;
        final ScenarioObserver.Watch watch=new ScenarioObserver.Watch();
        final ScenarioObserver.Binding binding;
        PlayerObservation player=new PlayerObservation(.5,1,2.5,-90);
        TickDecision decision;
        long tick;
        boolean onClient, onServer;
        long now=1;

        QueueFixture() {
            engine.start(plan,context,settings);
            // Reuse the existing core shiftHarness pose sequence; production traversal creates every counter.
            step(); step(); step(); player=new PlayerObservation(11.5,1,2.5,-90); step();
            step(); step(); step(); player=new PlayerObservation(11.5,1,2.5,0); step(); step(); step(); step();
            assertEquals(ForwardTraversal.Substage.SHIFT,engine.snapshot().traversal().orElseThrow().substage());
            assertTrue(decision.movement().orElseThrow().forward());
            host=new InspectScenarioHost(() -> now,() -> onClient,() -> onServer,action -> {
                // A real decision is queued between dispatches; nothing can run between statements of one callback.
                if(!queue.isEmpty()) queue.remove().run();
                onClient=true;
                try { action.run(); } finally { onClient=false; queue.add(this::step); }
            },action -> {
                assertFalse(onClient,"No server wait belongs inside a client transition");
                onServer=true; try { action.run(); } finally { onServer=false; }
            },null);
            scope=host.beginWork(new ScenarioHost.Operation(UUID.randomUUID(),1,UUID.randomUUID(),ScenarioHost.Command.RUN,Path.of("simulated")));
            var playerId=UUID.randomUUID();
            binding=new ScenarioObserver.Binding(UUID.randomUUID(),context,
                    new ScenarioObserver.ClientBinding(new Object(),new Object(),new Object(),new Object(),playerId,context.dimension(),true,true,true),
                    new ScenarioObserver.ServerBinding(new Object(),new Object(),new Object(),playerId,context.dimension(),true,true),host,scope);
            watch.bind(binding);
        }

        ScenarioObserver.ServerWitness anchor() {
            publishEnd(); commit(watch.beginServerRead(),player.z(),(int)tick);
            return watch.retained().witnesses().getLast();
        }

        ScenarioObserver.Sample publishEnd() {
            var s=sample().sample();
            var end=new ScenarioObserver.Sample(s.tick(),"END",s.worldIdentity(),s.originalWorld(),s.pose(),s.keys(),s.paused(),s.status(),s.observations());
            watch.append(binding,end,binding.client(),4.5);
            return end;
        }

        void commit(ScenarioObserver.ServerRead claim,double z,int serverTick) {
            watch.commitServer(claim,new ScenarioObserver.ServerReading(binding.server(),player.x(),player.y(),z,4.5,serverTick,serverTick,serverTick));
        }

        void step() {
            decision=engine.tick(new TickInput(context.runId(),tick++,Optional.of(context),Optional.of(player),
                    new SupportObservation(Map.of(new GridPos(11,0,3),new SupportSample(Evidence.OBSERVED,Optional.of(stone)))),
                    List.of(),Map.of(7,PlacementAvailability.NO_USABLE_ITEM),List.of()));
        }

        ScenarioRunner.Live sample() {
            var snapshot=engine.snapshot();
            boolean moving=snapshot.lifecycle().phase()==Phase.RUNNING && decision.movement().map(MovementIntent::forward).orElse(false);
            var status=new FabricBuildAdapter.Status(snapshot,moving?ControlOwner.NATIVE:ControlOwner.NONE,Optional.empty());
            var pose=new ScenarioObserver.Pose(player.x(),player.y(),player.z(),Vec3d.ZERO,Vec3d.ZERO,(int)tick,tick,
                    (float)player.yawDegrees(),(float)player.yawDegrees(),(float)player.yawDegrees(),false,false,0,8,64);
            return new ScenarioRunner.Live(new ScenarioObserver.Sample(tick,"SIMULATED",1,true,pose,
                    List.of(moving,false,false,false,false,false,false,false,false),false,status,List.of()),
                    side(player.z(),(int)tick,tick,0),side(player.z(),(int)tick,tick,(int)tick),null,List.of(),7,"simulated binding");
        }

        ScenarioRunner.Live successfulTerminal() {
            engine=new BuildEngine(); engine.start(plan,context,settings);
            for(int n=0;n<12 && engine.snapshot().lifecycle().phase()!=Phase.TERMINAL;n++) {
                var observed=new com.mapartrunner.core.placement.PlacementObservation(context.runId(),7,OptionalLong.empty(),tick,
                        Evidence.OBSERVED,Optional.of(stone));
                decision=engine.tick(new TickInput(context.runId(),tick++,Optional.of(context),Optional.of(player),
                        new SupportObservation(Map.of(new GridPos(11,0,3),new SupportSample(Evidence.OBSERVED,Optional.of(stone)))),
                        List.of(observed),Map.of(),List.of()));
            }
            assertEquals(Outcome.SUCCESS,engine.snapshot().lifecycle().outcome().orElseThrow());
            assertEquals(Reason.VERIFIED,engine.snapshot().diagnostic().orElseThrow().reason());
            return clocks(sample(),100,100,100,100,100,100);
        }
    }
}
