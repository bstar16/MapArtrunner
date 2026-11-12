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

    private static RunLifecycle.Context witnessContext() {
        return new RunLifecycle.Context(UUID.randomUUID(),"paired-connection","paired-world","minecraft:overworld",
                "paired-plan",new BuildPlan.GridPos(0,65,0),"paired-settings");
    }

    private static List<ScenarioObserver.Sample> witnessSamples(RunLifecycle.Context context) {
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
        ends.add(witnessSample(context,7,entry,1,"WEST",11.5,7.5,false,0,4));
        ends.add(witnessSample(context,8,entry,1,"WEST",11.5,7.5,false,1,5));
        ends.add(witnessSample(context,9,lane,1,"WEST",11.5,7.5,false,0,0));
        ends.add(witnessSample(context,10,lane,1,"WEST",11.5,7.5,true,0,0));
        ends.add(witnessSample(context,11,lane,1,"WEST",5,7.5,true,0,0));
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
                new ScenarioObserver.ClientBinding(new Object(),new Object(),new Object(),new Object(),player,context.dimension(),true),
                new ScenarioObserver.ServerBinding(new Object(),new Object(),new Object(),player,context.dimension(),true,true),host,scope);
    }

    private static ScenarioObserver.Watch pairedWatch(RunLifecycle.Context context,List<ScenarioObserver.Sample> samples,Inspect host,Scope scope) {
        var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,host,scope); watch.bind(binding);
        for(var sample:samples) {
            watch.append(binding,sample,binding.client(),4.5);
            if(sample.phase().equals("END")) {
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
        var changed=new ScenarioObserver.Watch.Retained(samples,original.failure(),original.binding(),witnesses);
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
        var resumed=witnessLive(r.samples().get(9));
        assertDoesNotThrow(() -> ScenarioRunner.evaluateTraversal(context.runId(),r,resumed));
        var late=witnessLive(r.samples().get(11));
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(context.runId(),r,late));
        var serverAhead=witnessLive(r.samples().get(11));
        var clientOnlyProgress=new ScenarioRunner.Live(resumed.sample(),resumed.client(),serverAhead.server(),null,List.of(),0,"");
        assertThrows(AssertionError.class,() -> ScenarioRunner.evaluateTraversal(context.runId(),r,clientOnlyProgress));
    }

    @Test void watchFreezeLinearizesAgainstInflightReadsAndMoreThan6000LateCallbacks() throws Exception {
        var context=witnessContext(); var watch=pairedWatch(context,witnessSamples(context),null,null);
        var started=new java.util.concurrent.CountDownLatch(1); var finish=new java.util.concurrent.CountDownLatch(1);
        var inFlight=new java.util.concurrent.atomic.AtomicReference<ScenarioObserver.ServerRead>();
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var server=new Thread(() -> {
            try {
                var read=watch.beginServerRead(); inFlight.set(read); started.countDown();
                if(!finish.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Freeze test did not release server read");
                watch.commitServer(read,reading(read.publication().binding().server(),read.publication().sample(),4.5,999));
                watch.failRead(read,new AssertionError("late read must not mutate failure"));
            } catch(Throwable failure) { error.set(failure); }
        },"simulated-server-read");
        server.start();
        ScenarioObserver.Watch.Retained archived;
        try {
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(inFlight.get());
            watch.freeze(); archived=watch.retained();
        } finally { finish.countDown(); server.join(2000); }
        assertFalse(server.isAlive()); assertNull(error.get());
        var read=inFlight.get();
        for(int n=0;n<6002;n++) {
            assertNull(watch.beginServerRead()); watch.recordServer(null);
            watch.append(archived.binding(),read.publication().sample(),archived.binding().client(),4.5);
        }
        assertEquals(archived,watch.retained());
        watch.clear();
        var fresh=witnessBinding(witnessContext(),null,null); watch.bind(fresh);
        var freshEnd=witnessSamples(fresh.context()).get(7); watch.append(fresh,freshEnd,fresh.client(),4.5);
        watch.commitServer(read,reading(archived.binding().server(),read.publication().sample(),4.5,1000));
        assertTrue(watch.retained().witnesses().isEmpty()); assertSame(fresh,watch.retained().binding());
        var freshRead=watch.beginServerRead(); assertEquals(1,freshRead.callback());
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
        var read=watch.beginServerRead(); watch.closeWorld(); var archived=watch.retained();
        assertNotNull(archived.binding());
        watch.commitServer(read,reading(archived.binding().server(),read.publication().sample(),4.5,999));
        assertNull(watch.beginServerRead()); assertEquals(archived,watch.retained());
    }

    @Test void changedPublicationDiscardsInflightReadAndServerCapLatchesFirstCause() {
        var context=witnessContext(); var watch=new ScenarioObserver.Watch(); var binding=witnessBinding(context,null,null); watch.bind(binding);
        var samples=witnessSamples(context); var endpoint=samples.get(7); var shift=samples.get(9);
        watch.append(binding,endpoint,binding.client(),4.5); var old=watch.beginServerRead();
        watch.append(binding,shift,binding.client(),4.5);
        watch.commitServer(old,reading(binding.server(),endpoint,4.5,101));
        watch.failRead(old,new AssertionError("discard replaced publication error"));
        assertTrue(watch.retained().witnesses().isEmpty()); assertNull(watch.retained().failure());
        for(int n=0;n<6000;n++) {
            var read=watch.beginServerRead();
            assertSame(shift,read.publication().sample());
            watch.commitServer(read,reading(binding.server(),shift,4.5,n+200));
        }
        var extra=watch.beginServerRead(); watch.commitServer(extra,reading(binding.server(),shift,4.5,6200));
        assertEquals(6000,watch.retained().witnesses().size());
        var failure=watch.retained().failure(); assertInstanceOf(AssertionError.class,failure);
        watch.failRead(extra,new IllegalStateException("second cause")); assertSame(failure,watch.retained().failure());
        watch.freeze(); var retained=watch.retained();
        for(int n=0;n<6002;n++) watch.recordServer(null);
        assertEquals(retained,watch.retained());
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
                    side.equals("client-uuid")?UUID.randomUUID():c.playerId(),side.equals("client-dimension")?"foreign":c.dimension(),!side.equals("client-thread"));
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
            var read=watch.beginServerRead();
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
            watch.commitServer(read,reading(retained.binding().server(),read.publication().sample(),4.5,999));
            watch.append(retained.binding(),read.publication().sample(),retained.binding().client(),4.5);
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
