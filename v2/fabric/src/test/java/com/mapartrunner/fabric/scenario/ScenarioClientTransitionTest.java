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

    private static final class QueueFixture {
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
        boolean onClient;

        QueueFixture() {
            engine.start(plan,context,settings);
            // Reuse the existing core shiftHarness pose sequence; production traversal creates every counter.
            step(); step(); step(); player=new PlayerObservation(11.5,1,2.5,-90); step();
            step(); step(); step(); player=new PlayerObservation(11.5,1,2.5,0); step(); step(); step(); step();
            assertEquals(ForwardTraversal.Substage.SHIFT,engine.snapshot().traversal().orElseThrow().substage());
            assertTrue(decision.movement().orElseThrow().forward());
            host=new InspectScenarioHost(() -> 1L,() -> onClient,() -> false,action -> {
                // A real decision is queued between dispatches; nothing can run between statements of one callback.
                if(!queue.isEmpty()) queue.remove().run();
                onClient=true;
                try { action.run(); } finally { onClient=false; queue.add(this::step); }
            },action -> fail("No server wait belongs inside a client transition"),null);
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
                    null,null,null,List.of(),7,"simulated binding");
        }
    }
}
