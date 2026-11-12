package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.placement.PlacementObservation;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only injected facts test the used finite policy, never native World/effect authority. */
class InspectExpiryTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize(); }

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

