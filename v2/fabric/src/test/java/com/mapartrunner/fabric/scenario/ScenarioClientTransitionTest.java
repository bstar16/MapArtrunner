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

    @Test void staleWorkerShiftCannotSilentlySkipTheRequiredPause() {
        var h=new QueueFixture(); h.player=new PlayerObservation(11.5,1,7.5,0); h.queue.add(h::step);
        h.host.inScope(h.scope,() -> {
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.pauseOnClient(h.host,c -> h.sample(),
                    c -> fail("Pause action must not run after leaving moving SHIFT"),h.context.runId()));
            assertEquals("Pause checkpoint is not moving SHIFT",error.getMessage());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,h.engine.snapshot().traversal().orElseThrow().substage());
        });
    }

    @Test void constructionSelectionPausesBeforeTheQueuedShiftExit() {
        var h=new QueueFixture(); var anchor=h.sample();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        var events=new ArrayList<String>(); var pauses=new AtomicInteger(); var captures=new AtomicInteger();
        var probe=side(3.25,41,42,43);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> {
                events.add(captures.getAndIncrement()==0?"before":"after");
                var selected=h.sample();
                if(captures.get()==1) h.queue.add(() -> { h.player=new PlayerObservation(11.5,1,7.5,0); h.step(); });
                return selected;
            },c -> { events.add("pause"); pauses.incrementAndGet(); h.engine.pause(); },h.context.runId(),anchor,true,20,
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
        var h=new QueueFixture(); var anchor=h.sample();
        h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        h.host.inScope(h.scope,() -> {
            for(double z:new double[]{2.5,2.7,2.75,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
                var rejected=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Server displacement did not qualify"),
                        h.context.runId(),anchor,true,20,() -> side(z,20,20,20));
                assertTrue(rejected.transition().isEmpty());
            }
            // Failed samples are not anchors: +.5 from original qualifies, although latest client was already at3.5.
            var accepted=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),
                    h.context.runId(),anchor,true,20,() -> side(3,20,20,20));
            assertTrue(accepted.transition().isPresent());
        });
        for(double z:new double[]{2.5,2.7,2.75,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            var client=new QueueFixture(); var start=client.sample();
            // Simulate Side data (including malformed positions) without inventing a nonfinite core PlayerObservation.
            var candidate=client.sample();
            var positioned=new ScenarioRunner.Live(candidate.sample(),side(z,20,20,0),candidate.server(),candidate.fault(),
                    candidate.pending(),candidate.nativeSequence(),candidate.nativeIdentities());
            client.host.inScope(client.scope,() -> {
                var result=ScenarioRunner.constructionCapture(client.host,c -> positioned,c -> fail("Client displacement did not qualify"),
                        client.context.runId(),start,true,20,() -> side(4,20,20,20));
                assertTrue(result.transition().isEmpty());
            });
        }
    }

    @Test void constructionSkipsProbeWithoutPendingAnchorAndRefusesAlreadyExitedShift() {
        for(boolean pending:List.of(false,true)) {
            var h=new QueueFixture(); var anchor=h.sample();
            h.host.inScope(h.scope,() -> {
                var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("No admission"),
                        h.context.runId(),pending?null:anchor,pending,20,() -> fail("No prior pending anchor"));
                assertTrue(result.transition().isEmpty()); assertNull(result.admissionProbe());
            });
        }
        var h=new QueueFixture(); var anchor=h.sample();
        h.player=new PlayerObservation(11.5,1,7.5,0); h.queue.add(h::step);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> fail("Already NEXT_ENTRY"),
                    h.context.runId(),anchor,true,20,() -> side(4,20,20,20));
            assertTrue(result.transition().isEmpty());
            assertEquals(ForwardTraversal.Substage.NEXT_ENTRY,result.before().sample().status().engine().traversal().orElseThrow().substage());
        });
    }

    @Test void constructionProbeAndAuthorityFailuresPropagateEvenBeforeAdmission() {
        var probeFailure=new IllegalStateException("server authority sentinel");
        var h=new QueueFixture(); var anchor=h.sample();
        h.host.inScope(h.scope,() -> assertSame(probeFailure,assertThrows(IllegalStateException.class,
                () -> ScenarioRunner.constructionCapture(h.host,c -> fail("Capture after failed probe"),c -> fail("No pause"),
                        h.context.runId(),anchor,true,20,() -> h.host.computeOnServer(server -> { throw probeFailure; })))));
        var foreign=new Context(UUID.randomUUID(),h.context.connectionId(),h.context.worldId(),h.context.dimension(),
                h.context.planFingerprint(),h.context.origin(),h.context.settingsFingerprint());
        var bad=new ArrayList<ScenarioRunner.Live>();
        bad.add(state(anchor,new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(foreign),Optional.of(Activity.SHIFT),Optional.empty()),null,null,null));
        bad.add(sample(anchor,anchor.sample().status(),anchor.sample().keys(),false,false,anchor.nativeSequence()));
        bad.add(sample(anchor,new FabricBuildAdapter.Status(anchor.sample().status().engine(),ControlOwner.NATIVE,
                Optional.of(new FabricBuildAdapter.Failure(h.context.runId(),101,"sentinel-adapter","failure",Optional.empty()))),
                anchor.sample().keys(),false,true,anchor.nativeSequence()));
        for(var invalid:bad) h.host.inScope(h.scope,() -> {
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.constructionCapture(h.host,c -> invalid,c -> fail("Invalid authority"),
                    h.context.runId(),null,false,20,() -> fail("Not pending")));
            assertTrue(error.getMessage().startsWith("Foreign run/world or adapter failure"));
        });
        var captureFailure=new IllegalStateException("client binding/save sentinel");
        h.host.inScope(h.scope,() -> assertSame(captureFailure,assertThrows(IllegalStateException.class,
                () -> ScenarioRunner.constructionCapture(h.host,c -> { throw captureFailure; },c -> fail("No pause"),
                        h.context.runId(),null,false,20,() -> fail("Not pending")))));
    }

    @Test void constructionUsesOriginalRemainingRequestsAndNeverRetriesAdmittedFailures() {
        for(int remaining:List.of(19,20)) {
            var h=new QueueFixture(); var anchor=h.sample(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var waits=new ScenarioRunner.PhysicsAllowance(remaining+1); var requests=new AtomicInteger(); var pauses=new AtomicInteger();
            waits.waitTick(false,requests::incrementAndGet);
            h.host.inScope(h.scope,() -> {
                java.util.function.Supplier<ScenarioRunner.ConstructionCapture> attempt=() -> ScenarioRunner.constructionCapture(h.host,c -> h.sample(),
                        c -> { pauses.incrementAndGet(); h.engine.pause(); },h.context.runId(),anchor,true,
                        waits.constructionLimit-waits.consumed(),() -> side(4,20,20,20));
                if(remaining==19) assertEquals("SHIFT pause would exceed the shared run physics budget",assertThrows(AssertionError.class,attempt::get).getMessage());
                else assertTrue(attempt.get().transition().isPresent());
                assertEquals(remaining==20?1:0,pauses.get()); assertEquals(1,requests.get()); assertEquals(1,waits.consumed());
            });
        }
        for(String failure:List.of("action","afterCapture","afterState","postServer","postEvidence")) {
            var h=new QueueFixture(); var anchor=h.sample(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var sentinel=new IllegalStateException(failure); var pauses=new AtomicInteger(); var captures=new AtomicInteger();
            h.host.inScope(h.scope,() -> {
                Class<? extends Throwable> expected=failure.equals("afterState")?AssertionError.class:IllegalStateException.class;
                var error=assertThrows(expected,() -> {
                    var result=ScenarioRunner.constructionCapture(h.host,c -> {
                        if(captures.incrementAndGet()==2 && failure.equals("afterCapture")) throw sentinel;
                        return h.sample();
                    },c -> { pauses.incrementAndGet(); if(failure.equals("action")) throw sentinel;
                        if(!failure.equals("afterState")) h.engine.pause(); },h.context.runId(),anchor,true,20,() -> side(4,20,20,20));
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
            var h=new QueueFixture(); var anchor=h.sample(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
            var sentinel=new IllegalStateException("cancel sentinel"); var pauses=new AtomicInteger();
            Runnable invalidate=() -> { if(expire) h.now=h.scope.deadlineNanos(); else h.host.cancelSession(sentinel); };
            h.host.inScope(h.scope,() -> {
                if(!after) h.queue.add(invalidate);
                Class<? extends Throwable> expected=expire?ScenarioHost.ScopeTimeoutException.class:IllegalStateException.class;
                var error=assertThrows(expected,
                        () -> ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> {
                            pauses.incrementAndGet(); h.engine.pause(); invalidate.run();
                        },h.context.runId(),anchor,true,20,() -> side(4,20,20,20)));
                if(!expire) assertSame(sentinel,error);
                assertEquals(after?1:0,pauses.get());
            });
        }
    }

    @Test void admittedAtomicPairAndProbeEarnNoCreditBeforeTwentyAdvancingHolds() {
        var h=new QueueFixture(); var anchor=h.sample(); h.player=new PlayerObservation(11.5,1,3.5,0); h.step();
        var waits=new ScenarioRunner.PhysicsAllowance(22); var requests=new AtomicInteger();
        waits.waitTick(false,requests::incrementAndGet);
        h.host.inScope(h.scope,() -> {
            var result=ScenarioRunner.constructionCapture(h.host,c -> h.sample(),c -> h.engine.pause(),
                    h.context.runId(),anchor,true,21,() -> side(4,100,100,100));
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
            var error=assertThrows(AssertionError.class,() -> ScenarioRunner.resumeOnClient(h.host,c -> h.sample(),c -> {
                h.engine=new BuildEngine(); h.engine.start(h.plan,h.context,h.settings);
            },paused,h.context.runId()));
            assertEquals("Manual resume renewed traversal or clocks",error.getMessage());
            assertEquals(0,h.engine.snapshot().eligibleTicks());
            assertEquals(0,h.engine.snapshot().traversal().orElseThrow().transitionTicks());
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
