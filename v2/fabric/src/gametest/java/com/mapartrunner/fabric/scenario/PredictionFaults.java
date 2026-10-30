package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.run.RunLifecycle.Phase;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import net.minecraft.util.math.BlockPos;
import com.mapartrunner.fabric.scenario.ScenarioObserver.Pose;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.movement.MovementPolicy.SupportSample;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader.CapturedEvidence;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Client-thread-only simulated faults. Scope dies before world teardown; diagnostics retain no native objects. */
public final class PredictionFaults {
    private static final int WINDOW = 200;
    private static Scope scope;
    private static String failure;
    private static final List<Response> responses = new ArrayList<>();
    private static final List<Omission> omissions = new ArrayList<>();
    private static boolean omissionLatched;
    private enum Mode { RESPONSE, OBSERVATION, COMPUTED_STOP, COMPUTED_PAUSE_RESUME, STATE, REPEATED_STATE, SUPPORT_AVAILABILITY }
    private static SupportAvailabilitySnapshot support;
    private static Barrier barrier;
    private static boolean transitioning;
    private static final List<BuildEngine.TickDecision> fresh = new ArrayList<>();
    private enum StateStage { MAIN, FIRST_PREDICTION, FIRST_RESPONSE, REPEAT_CONSUMED, OLDER_POST, COVERING_POST, COMPLETE, INACTIVE }
    public enum StateRoute { SINGLE, DELTA }
    private static StateStage stateStage=StateStage.INACTIVE;
    private static StateIdentity stateIdentity;
    private static int n1=-1,n2=-1;
    private static long ordinal;
    private static int stateAge;
    private static long stateEligible;
    private static final List<StateEvent> stateEvents=new ArrayList<>();
    private static final List<IncomingState> incomingStates=new ArrayList<>();
    private static ImportEvent importEvent;

    private PredictionFaults() { }

    record Response(UUID runId, String save, int serverIdentity, int worldIdentity, int handlerIdentity,
                    int connectionIdentity, int sequence, int playerAge, long eligibleTick) { }
    record Omission(int conversion, int playerAge, long eligibleTick, String phase, String activity,
                    PlacementObservation removed, SupportSample originalSupport, List<Integer> returnedIds) { }
    record Snapshot(boolean active, Optional<String> failure, List<Response> responses,
                    boolean omissionLatched, List<Omission> omissions, Optional<Barrier> barrier, List<BuildEngine.TickDecision> fresh,
                    Optional<SupportAvailabilitySnapshot> support) { }
    record Yaw(float yaw, float head, float body, float last, float lastHead, float lastBody) { }
    record BarrierState(Pose pose, Yaw yaw, List<Boolean> keys, FabricBuildAdapter.Status status, boolean air, boolean pending) { }
    record Barrier(BuildEngine.TickDecision decision, int decisionIdentity, BarrierState before,
                   Optional<BarrierState> paused, BarrierState after) { }
    private record Scope(MinecraftClient client, MinecraftServer server, ClientWorld world, ClientPlayerEntity player,
                         String dimension, ClientPlayNetworkHandler handler,
                         ClientConnection connection, UUID runId, Path save, int age, Mode mode,
                         String planFingerprint, int targetId, SupportLive support) { }
    private record SupportLive(BuildPlan plan, BuildEngine.Settings settings, ScenarioObserver.Watch watch) { }
    enum SupportState { ARMED, RELEASED, CANCELLED, FAILED }
    record SupportArmIdentity(UUID arm, RunLifecycle.Context context, String save, int server, int world, int player,
                              int handler, int connection, int targetId, GridPos target, BuildPlan.BlockSpec expected,
                              int armedAge, long armedEligible) { }
    record SupportPose(double x,double y,double z,double velocityX,double velocityY,double velocityZ,
                       double eyeX,double eyeY,double eyeZ,int age,long worldTime,float yaw,float headYaw,float bodyYaw,
                       boolean flying,boolean sprinting,int selectedSlot,int reserved,int usable) {
        static SupportPose of(Pose p) {
            return new SupportPose(p.x(),p.y(),p.z(),p.velocity().x,p.velocity().y,p.velocity().z,p.eyes().x,p.eyes().y,p.eyes().z,
                    p.age(),p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable());
        }
    }
    record SupportCapture(RunLifecycle.Context context, int targetId, BuildEngine.PlacementAvailability original,
                          BuildEngine.PlacementAvailability returned, SupportPose pose, long eligibleBefore) { }
    record SupportAvailabilitySnapshot(SupportArmIdentity identity, SupportState state, boolean cleanupAcknowledged,
                                       Optional<String> firstCause, boolean ownsFailure, int captures,
                                       Optional<SupportCapture> firstReady, Optional<SupportCapture> latest,
                                       Optional<String> heldEnd) { }
    record StateIdentity(RunLifecycle.Context context, int targetId, GridPos target, String save,
                         int server, int world, int handler, int connection, int armedAge) { }
    record NativeState(String state, boolean pending, int managerSequence, int age, long eligible, int selected, int usable) { }
    record StateEvent(long ordinal, String boundary, int sequence, NativeState sample) { }
    record IncomingState(long ordinal, StateRoute route, GridPos position, String state, int flags, int age, long eligible) { }
    record ImportEvent(long ordinal, int chunkX, int chunkZ, boolean loaded, StateIdentity identity, int age, long eligible) { }
    record StateSnapshot(boolean active, Optional<String> failure, StateIdentity identity, String stage, int n1, int n2,
                         List<StateEvent> events, List<IncomingState> incoming, Optional<ImportEvent> imported) { }

    static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId) {
        arm(client,ownedServer,ownedSave,runId,Mode.RESPONSE,null);
    }

    static void armObservation(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, BuildPlan plan) {
        arm(client,ownedServer,ownedSave,runId,Mode.OBSERVATION,plan);
    }

    static void armComputed(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, BuildPlan plan, boolean resume) {
        arm(client,ownedServer,ownedSave,runId,resume?Mode.COMPUTED_PAUSE_RESUME:Mode.COMPUTED_STOP,plan);
    }

    private static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, Mode mode, BuildPlan plan) {
        arm(client,ownedServer,ownedSave,runId,mode,plan,null,null);
    }

    private static void requireArmable(boolean active,String shared,SupportAvailabilitySnapshot retained,UUID runId) {
        if(active || shared!=null || retained!=null && (!retained.cleanupAcknowledged() && retained.state()==SupportState.FAILED
                || retained.identity().context().runId().equals(runId)))
            throw new IllegalStateException("Fault already active, failed or consumed for this run");
    }

    private static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, Mode mode,
                            BuildPlan plan, SupportLive live, SupportAvailabilitySnapshot armed) {
        requireThread(client);
        requireArmable(scope!=null,failure,support,runId); // Never reset evidence before the unresolved-failure barrier.
        var game=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        var save=ownedSave.toAbsolutePath().normalize();
        var state=MapArtClient.status().engine().lifecycle();
        var handler=client.getNetworkHandler();
        if(!game.getParent().getFileName().toString().equals("run")
                || !game.getParent().getParent().getFileName().toString().equals("build")
                || !save.startsWith(game.resolve("saves")) || save.equals(game.resolve("saves"))
                || ownedServer==null || client.getServer()!=ownedServer
                || !ownedServer.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(save)
                || client.world==null || client.player==null || client.player.getEntityWorld()!=client.world
                || handler==null || !handler.getConnection().isOpen() || client.isPaused() || client.currentScreen!=null
                || state.phase()!=Phase.RUNNING || state.context().isEmpty()
                || !state.context().orElseThrow().runId().equals(runId)) {
            throw new IllegalStateException("Refused unmanaged save/server/world/connection/run");
        }
        if(!withinWindow(0,0) || !withinWindow(WINDOW,0) || withinWindow(WINDOW+1,0) || withinWindow(-1,0))
            throw new AssertionError("Fault window boundary self-check failed");
        int targetId=-1;
        String fingerprint=null;
        if(mode==Mode.OBSERVATION || mode==Mode.COMPUTED_STOP || mode==Mode.COMPUTED_PAUSE_RESUME) {
            if(plan==null || !plan.fingerprint().equals(state.context().orElseThrow().planFingerprint()))
                throw new IllegalStateException("Refused fault plan fingerprint");
            try { targetId=SingleLaneFixture.targetId(plan,new GridPos(11,0,0)); }
            catch(IllegalArgumentException missing) { throw new IllegalStateException("Refused fault target coordinate",missing); }
            fingerprint=plan.fingerprint();
        }
        if(mode==Mode.SUPPORT_AVAILABILITY) {
            targetId=armed.identity().targetId(); fingerprint=plan.fingerprint();
        }
        var next=new Scope(client,ownedServer,client.world,client.player,client.world.getRegistryKey().getValue().toString(),
                handler,handler.getConnection(),runId,save,client.player.age,mode,fingerprint,targetId,live);
        responses.clear();
        omissions.clear(); omissionLatched=false; barrier=null; transitioning=false; fresh.clear();
        stateStage=StateStage.INACTIVE; stateIdentity=null; n1=-1; n2=-1; ordinal=0;
        stateEvents.clear(); incomingStates.clear(); importEvent=null;
        support=armed;
        if(armed!=null) { stateAge=armed.identity().armedAge(); stateEligible=armed.identity().armedEligible(); }
        scope=next; // Publish only the fully bound scope, after validation and per-run evidence reset.
    }

    static SupportAvailabilitySnapshot armSupportAvailability(MinecraftClient client,MinecraftServer server,Path save,
            UUID runId,BuildPlan plan,BuildEngine.Settings settings,ScenarioObserver.Watch watch) {
        requireThread(client);
        requireArmable(scope!=null,failure,support,runId);
        var status=MapArtClient.status(); var engine=status.engine();
        var run=engine.lifecycle().context().orElseThrow();
        int id=SingleLaneFixture.targetId(plan,new GridPos(11,0,5));
        var target=plan.targets().stream().filter(t -> t.id()==id).findFirst().orElseThrow();
        var at=new GridPos(run.origin().x()+11,run.origin().y(),run.origin().z()+5);
        if(!run.runId().equals(runId) || !run.planFingerprint().equals(plan.fingerprint())
                || !run.settingsFingerprint().equals(settings.fingerprint()) || !settings.equals(new BuildEngine.Settings(2,5,false,1))
                || !at.equals(new GridPos(11,65,5)) || !target.block().equals(new BuildPlan.BlockSpec("minecraft:stone",Map.of()))
                || engine.lifecycle().phase()!=Phase.RUNNING || engine.eligibleTicks()!=0 || engine.pendingTargetIds().contains(id)
                || status.controlOwner()!=BuildEngine.ControlOwner.NONE || !allReleased(keys(client))
                || watch==null || !watch.copy().isEmpty() || client.world==null || client.player==null
                || client.getNetworkHandler()==null || !loaded(client.world,at) || !unbuilt(client,at))
            throw new IllegalStateException("Refused support initial plan/target/settings/Watch/controls/air");
        var initial=watch.sample(client,"SUPPORT_ARM");
        var observation=initial.observations().stream().filter(o -> o.targetId()==id).toList();
        if(!initial.originalWorld() || observation.size()!=1 || !observation.getFirst().runId().equals(runId)
                || observation.getFirst().tick()!=initial.tick() || observation.getFirst().evidence()!=PlacementObservation.Evidence.OBSERVED
                || !observation.getFirst().observedState().equals(Optional.of(new BuildPlan.BlockSpec("minecraft:air",Map.of()))))
            throw new IllegalStateException("Refused support Watch/current OBSERVED air");
        var identity=new SupportArmIdentity(UUID.randomUUID(),run,save.toAbsolutePath().normalize().toString(),
                System.identityHashCode(server),System.identityHashCode(client.world),System.identityHashCode(client.player),
                System.identityHashCode(client.getNetworkHandler()),System.identityHashCode(client.getNetworkHandler().getConnection()),
                id,at,target.block(),client.player.age,engine.eligibleTicks());
        var armed=new SupportAvailabilitySnapshot(identity,SupportState.ARMED,false,Optional.empty(),false,0,
                Optional.empty(),Optional.empty(),Optional.empty());
        arm(client,server,save,runId,Mode.SUPPORT_AVAILABILITY,plan,new SupportLive(plan,settings,watch),armed);
        return support;
    }

    private static void requireSupportBarrier(SupportAvailabilitySnapshot retained,UUID runId) {
        if(retained!=null && retained.state()==SupportState.FAILED && !retained.cleanupAcknowledged()
                && retained.identity().context().runId().equals(runId))
            throw new IllegalStateException(retained.firstCause().orElseThrow());
    }

    private static boolean supportActive(Mode mode,SupportAvailabilitySnapshot retained,UUID runId) {
        requireSupportBarrier(retained,runId);
        return mode==Mode.SUPPORT_AVAILABILITY;
    }

    /** Public-producer RETURN only: actual observations/support and the native placement action remain untouched. */
    public static Map<Integer,BuildEngine.PlacementAvailability> gateSupportAvailability(MinecraftClient client,BuildPlan plan,
            RunLifecycle.Context run,BuildEngine.Settings settings,Map<Integer,BuildEngine.PlacementAvailability> original) {
        var owned=scope;
        if(!supportActive(owned==null?null:owned.mode(),support,run.runId())) return original;
        requireThread(client);
        requireSupportArguments(client,plan,run,settings,owned); // Foreign callbacks must not invalidate the current owner.
        check(client);
        requireSupportBarrier(support,run.runId());
        try {
            var result=filterSupportMap(original,plan,owned.targetId());
            if(support.captures()>=WINDOW*10) throw new IllegalStateException("Support capture event bound exceeded");
            var capture=new SupportCapture(run,owned.targetId(),original.get(owned.targetId()),result.get(owned.targetId()),
                    SupportPose.of(pose(client)),MapArtClient.status().engine().eligibleTicks());
            support=new SupportAvailabilitySnapshot(support.identity(),SupportState.ARMED,false,Optional.empty(),false,
                    support.captures()+1,support.firstReady().isEmpty() && capture.original()==BuildEngine.PlacementAvailability.READY
                        ? Optional.of(capture) : support.firstReady(),Optional.of(capture),Optional.empty());
            return result;
        } catch(RuntimeException | Error error) {
            fail("Support availability: "+error.getMessage());
            requireSupportBarrier(support,run.runId());
            throw error;
        }
    }

    private static Map<Integer,BuildEngine.PlacementAvailability> filterSupportMap(Map<Integer,BuildEngine.PlacementAvailability> original,
            BuildPlan plan,int targetId) {
        var ids=plan.targets().stream().map(BuildPlan.Target::id).collect(java.util.stream.Collectors.toSet());
        if(original==null || !ids.contains(targetId) || !original.keySet().equals(ids) || original.values().stream().anyMatch(Objects::isNull))
            throw new IllegalStateException("Malformed original availability target map");
        if(original.get(targetId)!=BuildEngine.PlacementAvailability.READY) return original;
        var result=new LinkedHashMap<>(original);
        result.put(targetId,BuildEngine.PlacementAvailability.NO_VALID_HIT);
        return Collections.unmodifiableMap(result);
    }

    private static void requireSupportArguments(MinecraftClient client,BuildPlan plan,RunLifecycle.Context run,
            BuildEngine.Settings settings,Scope owned) {
        if(client!=owned.client() || !owned.runId().equals(run.runId()) || !support.identity().context().equals(run)
                || !owned.support().plan().equals(plan) || !owned.support().settings().equals(settings))
            throw new IllegalStateException("Refused foreign support availability arguments");
    }

    private static boolean loaded(ClientWorld world,GridPos target) {
        return world.getChunkManager().getChunk(Math.floorDiv(target.x(),16),Math.floorDiv(target.z(),16),ChunkStatus.FULL,false)!=null;
    }

    private static boolean unbuilt(MinecraftClient client,GridPos target) {
        var pos=new BlockPos(target.x(),target.y(),target.z());
        return client.world.getBlockState(pos).isAir() && !((PendingPredictionView)((ClientWorldPendingUpdateAccessor)client.world)
                .mapart$getPendingUpdateManager()).mapart$hasPendingPrediction(pos);
    }

    private static List<Boolean> keys(MinecraftClient client) {
        var o=client.options;
        return List.of(o.forwardKey.isPressed(),o.backKey.isPressed(),o.leftKey.isPressed(),o.rightKey.isPressed(),
                o.jumpKey.isPressed(),o.sneakKey.isPressed(),o.sprintKey.isPressed(),o.useKey.isPressed(),o.attackKey.isPressed());
    }

    private static boolean allReleased(List<Boolean> keys) { return keys.size()==9 && keys.stream().noneMatch(Boolean::booleanValue); }

    /** Called atomically after waitTick; only an actual retained latest END can consume this one-use gate. */
    static SupportAvailabilitySnapshot releaseSupportAvailability(MinecraftClient client,MinecraftServer server,Path save,
            UUID runId,ScenarioObserver.Sample heldEnd) {
        requireThread(client);
        var owned=scope;
        if(owned==null || owned.mode()!=Mode.SUPPORT_AVAILABILITY || !owned.runId().equals(runId)
                || server!=owned.server() || save==null || !save.toAbsolutePath().normalize().equals(owned.save()))
            throw new IllegalStateException("Refused inactive/foreign support release");
        requireSupportArguments(client,owned.support().plan(),MapArtClient.status().engine().lifecycle().context().orElseThrow(),
                owned.support().settings(),owned);
        var samples=owned.support().watch().copy();
        if(!supportHold(support,heldEnd,samples,MapArtClient.status(),pose(client),keys(client)))
            throw new IllegalStateException("Refused premature/stale/nonretained support release");
        check(client);
        requireSupportBarrier(support,runId);
        support=finishSupport(support,SupportState.RELEASED,Optional.of(heldEnd.line()));
        scope=null; // Publish consumed immutable evidence before dropping the sole live native/Watch owner.
        return support;
    }

    static Optional<ScenarioObserver.Sample> supportHold(MinecraftClient client,UUID runId) {
        requireThread(client);
        if(scope==null || scope.mode()!=Mode.SUPPORT_AVAILABILITY || !scope.runId().equals(runId)) return Optional.empty();
        check(client);
        requireSupportBarrier(support,runId);
        var samples=scope.support().watch().copy();
        var last=samples.stream().filter(s -> s.phase().equals("END")).reduce((a,b) -> b).orElse(null);
        return supportHold(support,last,samples,MapArtClient.status(),pose(client),keys(client)) ? Optional.of(last) : Optional.empty();
    }

    private static boolean supportHold(SupportAvailabilitySnapshot gate,ScenarioObserver.Sample held,
            List<ScenarioObserver.Sample> retained,FabricBuildAdapter.Status current,Pose currentPose,List<Boolean> currentKeys) {
        if(gate==null || gate.state()!=SupportState.ARMED || gate.firstReady().isEmpty() || gate.latest().isEmpty()
                || held==null || !held.phase().equals("END") || !held.originalWorld() || held.paused() || held.pose()==null
                || retained.stream().filter(s -> s.phase().equals("END")).reduce((a,b) -> b).orElse(null)!=held
                || !held.status().equals(current) || !held.pose().equals(currentPose) || !held.keys().equals(currentKeys)
                || !allReleased(held.keys()) || !allReleased(currentKeys)
                || held.worldIdentity()!=gate.identity().world() || held.status().controlOwner()!=BuildEngine.ControlOwner.NATIVE
                || held.status().failure().isPresent() || held.status().engine().lifecycle().phase()!=Phase.RUNNING
                || !held.status().engine().lifecycle().context().orElseThrow().equals(gate.identity().context())) return false;
        var t=held.status().engine().traversal().orElse(null);
        if(t==null || t.substage()!=com.mapartrunner.core.run.ForwardTraversal.Substage.SHIFT
                || t.entry().stage()!=MovementPolicy.Stage.WALK || t.entry().supportHoldTicks()<=0
                || !t.entry().lane().direction().name().equals("SOUTH")
                || Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(held.pose().yaw()))>1.5) return false;
        var origin=gate.identity().context().origin(); var target=gate.identity().target();
        var expected=t.entry().lane().expectedFloor().targetsByPosition().get(new GridPos(target.x()-origin.x(),
                target.y()-origin.y(),target.z()-origin.z()));
        if(expected==null || expected.id()!=gate.identity().targetId() || !expected.block().equals(gate.identity().expected())
                || target.y()-origin.y()!=t.entry().lane().start().y()
                || target.z()-origin.z()!=(int)Math.floor(held.pose().z()-origin.z())+1
                || !ScenarioRunner.southFootprint(held.pose().x()-origin.x(),t.entry().lane().start().x()).contains(target.x()-origin.x())
                || held.status().engine().pendingTargetIds().contains(expected.id())) return false;
        var observations=held.observations().stream().filter(o -> o.targetId()==expected.id()).toList();
        var ready=gate.latest().orElseThrow();
        return observations.size()==1 && observations.getFirst().runId().equals(gate.identity().context().runId())
                && observations.getFirst().tick()==held.tick() && observations.getFirst().evidence()==PlacementObservation.Evidence.OBSERVED
                && observations.getFirst().observedState().equals(Optional.of(new BuildPlan.BlockSpec("minecraft:air",Map.of())))
                && ready.original()==BuildEngine.PlacementAvailability.READY && ready.returned()==BuildEngine.PlacementAvailability.NO_VALID_HIT
                && ready.context().equals(gate.identity().context()) && ready.targetId()==expected.id()
                && ready.eligibleBefore()+1==held.status().engine().eligibleTicks() && ready.pose().equals(SupportPose.of(held.pose()));
    }

    private static SupportAvailabilitySnapshot finishSupport(SupportAvailabilitySnapshot gate,SupportState state,Optional<String> held) {
        return new SupportAvailabilitySnapshot(gate.identity(),state,gate.cleanupAcknowledged(),gate.firstCause(),gate.ownsFailure(),
                gate.captures(),gate.firstReady(),gate.latest(),held);
    }

    private static SupportAvailabilitySnapshot failedSupport(SupportAvailabilitySnapshot gate,String detail,boolean ownsFailure) {
        if(gate.state()==SupportState.FAILED) return gate;
        return new SupportAvailabilitySnapshot(gate.identity(),SupportState.FAILED,false,Optional.of(detail),ownsFailure,
                gate.captures(),gate.firstReady(),gate.latest(),gate.heldEnd());
    }

    static void cancelSupportAvailabilityForClose(MinecraftClient client,MinecraftServer server,Path save,UUID runId,
            SupportArmIdentity issued) {
        requireThread(client);
        var gate=support; var status=MapArtClient.status(); var run=status.engine().lifecycle().context().orElse(null);
        requireSupportClose(gate,issued,runId,run,status.engine().lifecycle().phase(),status.controlOwner(),keys(client),
                scope==null?null:scope.runId(),scope!=null && scope.mode()==Mode.SUPPORT_AVAILABILITY);
        if(server==null || server!=client.getServer() || System.identityHashCode(server)!=issued.server()
                || save==null || !save.toAbsolutePath().normalize().toString().equals(issued.save())
                || !server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().toString().equals(issued.save())
                || client.world==null || System.identityHashCode(client.world)!=issued.world()
                || client.player==null || System.identityHashCode(client.player)!=issued.player() || client.player.getEntityWorld()!=client.world
                || client.getNetworkHandler()==null || System.identityHashCode(client.getNetworkHandler())!=issued.handler()
                || client.getNetworkHandler().getWorld()!=client.world
                || System.identityHashCode(client.getNetworkHandler().getConnection())!=issued.connection()
                || !client.getNetworkHandler().getConnection().isOpen()
                || !client.world.getRegistryKey().getValue().toString().equals(issued.context().dimension()))
            throw new IllegalStateException("Refused support close identity/native terminal/released authority");
        if(gate.state()==SupportState.FAILED) {
            support=acknowledgeSupport(gate,issued);
            failure=retireSupportFailure(gate,failure);
        } else if(gate.state()==SupportState.ARMED) support=finishSupport(gate,SupportState.CANCELLED,gate.heldEnd());
        scope=null;
    }

    private static void requireSupportClose(SupportAvailabilitySnapshot gate,SupportArmIdentity issued,UUID requested,
            RunLifecycle.Context current,Phase phase,BuildEngine.ControlOwner owner,List<Boolean> keys,UUID activeRun,boolean activeSupport) {
        if(gate==null || issued!=gate.identity() || !issued.context().runId().equals(requested) || !issued.context().equals(current)
                || activeRun!=null && (!activeSupport || !activeRun.equals(requested))
                || phase!=Phase.TERMINAL || owner!=BuildEngine.ControlOwner.NONE || !allReleased(keys))
            throw new IllegalStateException("Refused support close identity/run/terminal/released state");
    }

    private static String retireSupportFailure(SupportAvailabilitySnapshot gate,String shared) {
        return gate.ownsFailure() && shared==gate.firstCause().orElseThrow() ? null : shared;
    }

    private static SupportAvailabilitySnapshot acknowledgeSupport(SupportAvailabilitySnapshot gate,SupportArmIdentity issued) {
        if(issued!=gate.identity() || gate.state()!=SupportState.FAILED) throw new IllegalStateException("Refused failure acknowledgment identity/state");
        if(gate.cleanupAcknowledged()) return gate;
        return new SupportAvailabilitySnapshot(issued,SupportState.FAILED,true,gate.firstCause(),gate.ownsFailure(),gate.captures(),
                gate.firstReady(),gate.latest(),gate.heldEnd());
    }

    /** Synthetic immutable facts exercise these same policies; owned native authority still requires the real world. */
    static void verifySupportGuards(BuildPlan plan,ScenarioObserver.Sample held) {
        supportRequire(java.util.Arrays.stream(SupportPose.class.getDeclaredFields()).allMatch(f -> f.getType().isPrimitive()),
                "Terminal support pose retains native objects");
        var run=held.status().engine().lifecycle().context().orElseThrow(); var id=run.runId();
        int target=SingleLaneFixture.targetId(plan,new GridPos(11,0,5));
        var stone=new BuildPlan.BlockSpec("minecraft:stone",Map.of());
        var identity=new SupportArmIdentity(UUID.randomUUID(),run,"synthetic-save",1,held.worldIdentity(),2,3,4,target,
                new GridPos(11,65,5),stone,held.pose().age(),0);
        var capture=new SupportCapture(run,target,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,
                SupportPose.of(held.pose()),held.status().engine().eligibleTicks()-1);
        var armed=new SupportAvailabilitySnapshot(identity,SupportState.ARMED,false,Optional.empty(),false,1,
                Optional.of(capture),Optional.of(capture),Optional.empty());
        var original=new LinkedHashMap<Integer,BuildEngine.PlacementAvailability>();
        for(var value:plan.targets()) original.put(value.id(),BuildEngine.PlacementAvailability.READY);
        var originalCopy=new LinkedHashMap<>(original);
        var filtered=filterSupportMap(original,plan,target);
        supportRequire(filtered!=original && original.equals(originalCopy) && filtered.size()==original.size()
                && filtered.keySet().equals(original.keySet()) && filtered.get(target)==BuildEngine.PlacementAvailability.NO_VALID_HIT
                && filtered.entrySet().stream().allMatch(e -> e.getKey()==target || e.getValue()==original.get(e.getKey())),"Support one-cell map filter");
        refusedSupport(() -> filtered.put(target,BuildEngine.PlacementAvailability.READY));
        for(var value:BuildEngine.PlacementAvailability.values()) if(value!=BuildEngine.PlacementAvailability.READY) {
            var unavailable=new LinkedHashMap<>(original); unavailable.put(target,value);
            supportRequire(filterSupportMap(unavailable,plan,target)==unavailable,"Non-READY map identity");
        }
        refusedSupport(() -> filterSupportMap(original,plan,999));
        var missing=new LinkedHashMap<>(original); missing.remove(target);
        refusedSupport(() -> filterSupportMap(missing,plan,target));
        var malformed=new LinkedHashMap<>(original); malformed.put(target,null);
        refusedSupport(() -> filterSupportMap(malformed,plan,target));
        for(var mode:Mode.values()) if(mode!=Mode.SUPPORT_AVAILABILITY)
            supportRequire(!supportActive(mode,null,id),"Old-mode support passthrough");
        supportRequire(!supportActive(null,null,id),"Never-armed passthrough");
        supportRequire(supportHold(armed,held,List.of(held),held.status(),held.pose(),held.keys()),"Exact retained support hold");
        var equal=new ScenarioObserver.Sample(held.tick(),held.phase(),held.worldIdentity(),held.originalWorld(),held.pose(),held.keys(),held.paused(),held.status(),held.observations());
        supportRequire(equal.equals(held) && equal!=held && !supportHold(armed,equal,List.of(held),held.status(),held.pose(),held.keys()),"Equal reconstruction authorized release");
        supportRequire(!supportHold(armed,held,List.of(held,equal),held.status(),held.pose(),held.keys()),"Stale END authorized release");
        var test=new ScenarioObserver.Sample(held.tick(),"TEST",held.worldIdentity(),true,held.pose(),held.keys(),false,held.status(),held.observations());
        supportRequire(!supportHold(armed,test,List.of(test),test.status(),test.pose(),test.keys()),"TEST poll authorized release");
        for(int key=0;key<9;key++) {
            var pressed=new ArrayList<>(held.keys()); pressed.set(key,true);
            var keyed=new ScenarioObserver.Sample(held.tick(),held.phase(),held.worldIdentity(),true,held.pose(),List.copyOf(pressed),false,held.status(),held.observations());
            supportRequire(!supportHold(armed,keyed,List.of(keyed),keyed.status(),keyed.pose(),keyed.keys()),"Pressed key authorized release: "+key);
        }
        for(var bad:List.of(new SupportCapture(run,target,BuildEngine.PlacementAvailability.OUT_OF_REACH,BuildEngine.PlacementAvailability.OUT_OF_REACH,capture.pose(),capture.eligibleBefore()),
                new SupportCapture(run,target,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,capture.pose(),capture.eligibleBefore()-1))) {
            var gate=new SupportAvailabilitySnapshot(identity,SupportState.ARMED,false,Optional.empty(),false,2,Optional.of(capture),Optional.of(bad),Optional.empty());
            supportRequire(!supportHold(gate,held,List.of(held),held.status(),held.pose(),held.keys()),"NonREADY/stale capture authorized release");
        }
        var p=held.pose(); var laterPose=new Pose(p.x(),p.y(),p.z(),p.velocity(),p.eyes(),p.age()+1,p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),
                p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable());
        supportRequire(!supportHold(armed,held,List.of(held),held.status(),laterPose,held.keys()),"Intervening native age authorized stale END");
        var noReady=new SupportAvailabilitySnapshot(identity,SupportState.ARMED,false,Optional.empty(),false,1,Optional.empty(),Optional.of(capture),Optional.empty());
        supportRequire(!supportHold(noReady,held,List.of(held),held.status(),held.pose(),held.keys()),"No original READY authorized release");
        for(String entry:List.of("snapshot/check","stateSnapshot/check","withhold/check","availability/check","clear")) {
            var failed=failedSupport(armed,"first cause: "+entry,true);
            supportRequire(failed.state()==SupportState.FAILED && !failed.cleanupAcknowledged()
                    && failedSupport(failed,"secondary",false)==failed,"First support cause changed");
            refusedSupport(() -> supportActive(null,failed,id));
            supportRequire(!supportActive(null,failed,UUID.randomUUID()),"Failed A poisoned unrelated B availability");
            for(var mode:Mode.values()) refusedSupport(() -> requireArmable(false,null,failed,UUID.randomUUID()));
            refusedSupport(() -> requireSupportBarrier(failed,id));
            supportRequire(!supportHold(failed,held,List.of(held),held.status(),held.pose(),held.keys()),"FAILED released as healthy");
            var reconstructed=new SupportArmIdentity(identity.arm(),identity.context(),identity.save(),identity.server(),identity.world(),identity.player(),
                    identity.handler(),identity.connection(),identity.targetId(),identity.target(),identity.expected(),identity.armedAge(),identity.armedEligible());
            supportRequire(reconstructed.equals(identity) && reconstructed!=identity,"Identity reconstruction fixture");
            refusedSupport(() -> acknowledgeSupport(failed,reconstructed));
            requireSupportClose(failed,identity,id,run,Phase.TERMINAL,BuildEngine.ControlOwner.NONE,held.keys(),null,false);
            refusedSupport(() -> requireSupportClose(failed,reconstructed,id,run,Phase.TERMINAL,BuildEngine.ControlOwner.NONE,held.keys(),null,false));
            refusedSupport(() -> requireSupportClose(failed,identity,UUID.randomUUID(),run,Phase.TERMINAL,BuildEngine.ControlOwner.NONE,held.keys(),null,false));
            refusedSupport(() -> requireSupportClose(failed,identity,id,run,Phase.RUNNING,BuildEngine.ControlOwner.NONE,held.keys(),null,false));
            refusedSupport(() -> requireSupportClose(failed,identity,id,run,Phase.TERMINAL,BuildEngine.ControlOwner.NATIVE,held.keys(),null,false));
            refusedSupport(() -> requireSupportClose(failed,identity,id,run,Phase.TERMINAL,BuildEngine.ControlOwner.NONE,held.keys(),UUID.randomUUID(),true));
            refusedSupport(() -> requireSupportClose(failed,identity,id,run,Phase.TERMINAL,BuildEngine.ControlOwner.NONE,held.keys(),id,false));
            var acknowledged=acknowledgeSupport(failed,identity);
            supportRequire(acknowledged.state()==SupportState.FAILED && acknowledged.cleanupAcknowledged()
                    && acknowledged.firstCause().equals(failed.firstCause()) && acknowledged.latest().equals(failed.latest())
                    && acknowledgeSupport(acknowledged,identity)==acknowledged && !supportActive(null,acknowledged,id),"Failure acknowledgment hid cause/evidence");
            refusedSupport(() -> requireArmable(false,null,acknowledged,id));
            requireArmable(false,null,acknowledged,UUID.randomUUID());
            refusedSupport(() -> requireArmable(false,"another mode failure",acknowledged,UUID.randomUUID()));
            supportRequire(retireSupportFailure(failed,failed.firstCause().orElseThrow())==null
                    && retireSupportFailure(failed,"another mode failure").equals("another mode failure")
                    && retireSupportFailure(failedSupport(armed,"unowned",false),"unowned").equals("unowned"),"Support cleanup retired unrelated failure");
        }
        for(var state:List.of(SupportState.RELEASED,SupportState.CANCELLED)) {
            var terminal=finishSupport(armed,state,Optional.of(held.line()));
            supportRequire(!supportActive(null,terminal,id) && !supportHold(terminal,held,List.of(held),held.status(),held.pose(),held.keys()),"Consumed support re-released");
            refusedSupport(() -> requireArmable(false,null,terminal,id));
        }
        System.out.println("PredictionFaults support same-helper synthetic guards passed; native shared-entry/close authority remains separate");
    }

    private static void supportRequire(boolean condition,String detail) { if(!condition) throw new AssertionError(detail); }
    private static void refusedSupport(Runnable action) {
        try { action.run(); } catch(IllegalStateException | IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Synthetic support policy accepted refused input");
    }

    /** Called at the original incoming INVOKE, after vanilla main-thread dispatch; never queues or replays a response. */
    public static boolean withhold(ClientPlayNetworkHandler handler, ClientWorld world, int sequence) {
        var owned=scope;
        if(owned==null) return false;
        var client=MinecraftClient.getInstance();
        if(stateMode(owned)) {
            if(stateReady(client,handler,world)) beforeNormalResponse(client,sequence);
            return false;
        }
        requireThread(client);
        if(handler!=owned.handler() || world!=owned.world()) return false;
        check(client);
        if(scope==null || owned.mode()!=Mode.RESPONSE) return false;
        if(responses.size()>=WINDOW) {
            fail("Response event bound exceeded");
            return false;
        }
        responses.add(new Response(owned.runId(),owned.save().toString(),System.identityHashCode(owned.server()),
                System.identityHashCode(world),System.identityHashCode(handler),System.identityHashCode(owned.connection()),
                sequence,client.player.age,MapArtClient.status().engine().eligibleTicks()));
        return true;
    }

    /** The original capture/list are the only evidence; the latch survives the ledger's terminal cleanup. */
    public static List<PlacementObservation> omit(CapturedEvidence captured, List<PlacementObservation> original) {
        var owned=scope;
        if(owned==null || owned.mode()!=Mode.OBSERVATION) return original;
        var client=MinecraftClient.getInstance();
        requireThread(client);
        check(client);
        if(scope==null) return original;
        if(original.size()!=60 || !original.stream().map(PlacementObservation::targetId).sorted().toList()
                .equals(java.util.stream.IntStream.range(0,60).boxed().toList())
                || original.stream().anyMatch(o -> !o.runId().equals(owned.runId()))) {
            fail("Observation conversion lost original60 unique current-run IDs");
            throw new AssertionError(failure);
        }
        var engine=MapArtClient.status().engine();
        if(!omissionLatched && !engine.pendingTargetIds().contains(owned.targetId())) return original;
        omissionLatched=true;
        var removed=original.stream().filter(o -> o.targetId()==owned.targetId()).findFirst().orElseThrow();
        var support=captured.supportObservation().cells().get(new GridPos(11,0,0));
        if(support==null || removed.evidence()!=support.evidence() || !removed.observedState().equals(support.state())
                || omissions.size()>=WINDOW*10) {
            fail("Observation/support mismatch or conversion bound exceeded");
            throw new AssertionError(failure);
        }
        var returned=new ArrayList<>(original);
        returned.remove(removed);
        if(returned.size()!=59 || returned.stream().anyMatch(o -> o.targetId()==owned.targetId())) {
            fail("Observation conversion did not omit only"+owned.targetId());
            throw new AssertionError(failure);
        }
        omissions.add(new Omission(omissions.size()+1,client.player.age,engine.eligibleTicks(),engine.lifecycle().phase().name(),
                engine.lifecycle().activity().map(Enum::name).orElse("NONE"),removed,support,
                returned.stream().map(PlacementObservation::targetId).toList()));
        return List.copyOf(returned);
    }

    private static boolean computed(Scope owned) {
        return owned.mode()==Mode.COMPUTED_STOP || owned.mode()==Mode.COMPUTED_PAUSE_RESUME;
    }

    /** Runs only after the real engine STORE; consumption precedes lifecycle reentry and never changes the decision. */
    public static void afterDecision(BuildEngine.TickDecision decision) {
        var owned=scope;
        if(owned==null || !computed(owned)) return;
        var client=MinecraftClient.getInstance();
        requireThread(client); check(client);
        if(scope==null) return;
        if(barrier!=null) {
            demand(decision.tick()>barrier.decision().tick() && fresh.size()<WINDOW,"Fresh computed decision identity/bound failed");
            fresh.add(decision);
            return;
        }
        if(decision.runId().filter(owned.runId()::equals).isEmpty() || decision.snapshot().lifecycle().phase()!=Phase.RUNNING
                || decision.owner()!=BuildEngine.ControlOwner.NATIVE || decision.placement().filter(p -> p.targetId()==owned.targetId()).isEmpty()
                || decision.movement().filter(m -> m.forward() || m.back() || m.strafe()!=MovementPolicy.Strafe.NONE).isEmpty()) return;
        var before=barrierState(client);
        demand(before.status().engine().equals(decision.snapshot()) && before.status().controlOwner()==BuildEngine.ControlOwner.NATIVE
                && before.keys().subList(0,4).stream().anyMatch(Boolean::booleanValue) && before.pose().selectedSlot()==0
                && before.pose().reserved()==7 && before.pose().usable()==64 && before.air() && !before.pending(),"Computed opportunity lacks actual prior native controls/initial target/stock");
        barrier=new Barrier(decision,System.identityHashCode(decision),before,Optional.empty(),before);
        transitioning=true; // Only this consumed mode may see its own PAUSED transition; scope stays owned through cleanup.
        try {
            if(owned.mode()==Mode.COMPUTED_STOP) {
                MapArtClient.stop();
                var after=barrierState(client);
                barrier=new Barrier(decision,barrier.decisionIdentity(),before,Optional.empty(),after);
                demand(after.status().engine().lifecycle().outcome().orElseThrow()==com.mapartrunner.core.run.RunLifecycle.Outcome.CANCELLED,"Computed stop did not cancel");
                released(after);
            } else {
                MapArtClient.pause();
                var paused=barrierState(client);
                barrier=new Barrier(decision,barrier.decisionIdentity(),before,Optional.of(paused),paused);
                var original=before.status().engine(); var retained=paused.status().engine();
                demand(retained.lifecycle().phase()==Phase.PAUSED && retained.lifecycle().context().equals(original.lifecycle().context())
                        && retained.laneEntry().equals(original.laneEntry()) && retained.unfinishedTargetIds().equals(original.unfinishedTargetIds())
                        && retained.eligibleTicks()==original.eligibleTicks(),"Computed pause lost run/context/lane/unfinished data");
                released(paused);
                demand(paused.yaw().equals(before.yaw()) && paused.pose().selectedSlot()==0 && paused.pose().reserved()==7
                        && paused.pose().usable()==64 && paused.air() && !paused.pending(),"Pause changed native effects");
                MapArtClient.resume();
                var after=barrierState(client);
                barrier=new Barrier(decision,barrier.decisionIdentity(),before,Optional.of(paused),after);
                demand(after.status().engine().lifecycle().phase()==Phase.RUNNING
                        && after.status().engine().lifecycle().context().equals(original.lifecycle().context())
                        && after.status().engine().laneEntry().equals(original.laneEntry())
                        && after.status().engine().unfinishedTargetIds().equals(original.unfinishedTargetIds()),"Synchronous resume lost same run/context/lane/unfinished");
                released(after);
            }
            demand(barrier.after().yaw().equals(before.yaw()) && barrier.after().pose().selectedSlot()==0
                    && barrier.after().pose().reserved()==7 && barrier.after().pose().usable()==64
                    && barrier.after().air() && !barrier.after().pending(),"Lifecycle hook changed native effects");
            check(client);
        } finally { transitioning=false; }
    }

    private static BarrierState barrierState(MinecraftClient c) {
        var p=c.player; var o=c.options;
        var keys=List.of(o.forwardKey.isPressed(),o.backKey.isPressed(),o.leftKey.isPressed(),o.rightKey.isPressed(),
                o.jumpKey.isPressed(),o.sneakKey.isPressed(),o.sprintKey.isPressed(),o.useKey.isPressed(),o.attackKey.isPressed());
        var pose=pose(c);
        var yaw=new Yaw(p.getYaw(),p.headYaw,p.bodyYaw,p.lastYaw,p.lastHeadYaw,p.lastBodyYaw);
        boolean pending=((PendingPredictionView)((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager())
                .mapart$hasPendingPrediction(new BlockPos(11,65,0));
        return new BarrierState(pose,yaw,keys,MapArtClient.status(),c.world.getBlockState(new BlockPos(11,65,0)).isAir(),pending);
    }

    private static Pose pose(MinecraftClient c) {
        var p=c.player;
        return new Pose(p.getX(),p.getY(),p.getZ(),p.getVelocity(),p.getEyePos(),p.age,c.world.getTime(),p.getYaw(),p.headYaw,p.bodyYaw,
                p.getAbilities().flying,p.isSprinting(),p.getInventory().getSelectedSlot(),p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount());
    }

    private static void released(BarrierState state) {
        demand(state.status().controlOwner()==BuildEngine.ControlOwner.NONE && state.keys().stream().noneMatch(Boolean::booleanValue)
                && !state.pose().sprinting(),"Computed lifecycle did not synchronously release all controls");
    }

    private static void demand(boolean condition,String detail) {
        if(!condition) { fail(detail); throw new AssertionError(detail); }
    }

    static Snapshot snapshot() {
        var client=MinecraftClient.getInstance();
        requireThread(client);
        check(client);
        return resetSnapshot();
    }

    /** Reset refusal checks must not invoke check(), which invalidates stale diagnostic scopes. */
    static Snapshot resetSnapshot() {
        requireThread(MinecraftClient.getInstance());
        return new Snapshot(scope!=null,Optional.ofNullable(failure),List.copyOf(responses),omissionLatched,List.copyOf(omissions),Optional.ofNullable(barrier),List.copyOf(fresh),Optional.ofNullable(support));
    }

    static void validateResponsesForReset(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID failedRunId) {
        requireThread(client);
        var owned=scope;
        if(owned==null || owned.mode()!=Mode.RESPONSE || failure!=null)
            throw new IllegalStateException("Reset release requires an active healthy RESPONSE scope");
        var status=MapArtClient.status(); var state=status.engine().lifecycle(); var o=client.options;
        if(client!=owned.client() || ownedServer!=owned.server() || client.getServer()!=owned.server()
                || ownedSave==null || !ownedSave.toAbsolutePath().normalize().equals(owned.save())
                || !owned.server().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(owned.save())
                || client.world!=owned.world() || client.player!=owned.player()
                || client.player.getEntityWorld()!=owned.world() || client.getNetworkHandler()!=owned.handler()
                || owned.handler().getWorld()!=owned.world() || owned.handler().getConnection()!=owned.connection()
                || !owned.connection().isOpen() || !owned.world().getRegistryKey().getValue().toString().equals(owned.dimension())
                || state.phase()!=Phase.TERMINAL || state.outcome().orElse(null)!=RunLifecycle.Outcome.FAILED
                || state.context().isEmpty() || !owned.runId().equals(failedRunId)
                || !state.context().orElseThrow().runId().equals(failedRunId)
                || !state.context().orElseThrow().dimension().equals(owned.dimension())
                || status.controlOwner()!=BuildEngine.ControlOwner.NONE
                || List.of(o.forwardKey,o.backKey,o.leftKey,o.rightKey,o.jumpKey,o.sneakKey,o.sprintKey,o.useKey,o.attackKey)
                    .stream().anyMatch(k -> k.isPressed())
                || client.isPaused() || client.currentScreen!=null || !withinWindow(client.player.age,owned.age())
                || responses.isEmpty() || responses.size()>WINDOW
                || responses.stream().anyMatch(r -> !r.runId().equals(owned.runId()) || !r.save().equals(owned.save().toString())
                    || !withinWindow(r.playerAge(),owned.age()) || !withinWindow(client.player.age,r.playerAge())))
            throw new IllegalStateException("Refused reset RESPONSE binding/run/controls/window");
    }

    static List<Response> releaseResponsesForReset(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID failedRunId) {
        validateResponsesForReset(client,ownedServer,ownedSave,failedRunId);
        var owned=scope; var captured=List.copyOf(responses);
        scope=null; // Consume before native reentry: reconciliation may partially mutate then throw; never retry.
        for(var record:captured) owned.world().handlePlayerActionResponse(record.sequence());
        return captured;
    }

    static void clear() {
        requireThread(MinecraftClient.getInstance());
        if(scope!=null && scope.mode()==Mode.SUPPORT_AVAILABILITY) {
            fail("Unexpected clear of active support availability");
            throw new IllegalStateException(support.firstCause().orElseThrow());
        }
        if(support!=null && support.state()==SupportState.FAILED && !support.cleanupAcknowledged())
            throw new IllegalStateException(support.firstCause().orElseThrow());
        scope=null; // Invalidate before disposing native owners; immutable response evidence survives cleanup.
        stateStage=StateStage.INACTIVE;
    }

    private static void check(MinecraftClient client) {
        var owned=scope;
        try { checkBound(client); }
        catch(RuntimeException | Error error) {
            if(owned==null || owned.mode()!=Mode.SUPPORT_AVAILABILITY) throw error;
            fail("Owned support check: "+error.getMessage());
        }
    }

    private static void checkBound(MinecraftClient client) {
        var owned=scope;
        if(owned==null) return;
        var state=MapArtClient.status().engine().lifecycle();
        if(client.getServer()!=owned.server() || client.world!=owned.world() || client.getNetworkHandler()!=owned.handler()
                || owned.handler().getConnection()!=owned.connection() || !owned.connection().isOpen()
                || client.player==null || client.player.getEntityWorld()!=owned.world()
                || !owned.server().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(owned.save())
                || state.context().isEmpty() || !state.context().orElseThrow().runId().equals(owned.runId())
                || (owned.planFingerprint()!=null && !state.context().orElseThrow().planFingerprint().equals(owned.planFingerprint()))
                || (state.phase()!=Phase.RUNNING && state.phase()!=Phase.TERMINAL
                    && !(transitioning && owned.mode()==Mode.COMPUTED_PAUSE_RESUME && state.phase()==Phase.PAUSED))
                || (computed(owned) && barrier==null && state.phase()!=Phase.RUNNING)
                || client.isPaused() || client.currentScreen!=null) fail("Owned fault scope lost");
        else if(!withinWindow(client.player.age,owned.age())) fail("Owned fault window exceeded "+WINDOW+" physics ticks");
        else if(stateMode(owned) && (stateIdentity==null || !state.context().orElseThrow().equals(stateIdentity.context())
                || owned.handler().getWorld()!=owned.world()
                || !owned.world().getRegistryKey().getValue().toString().equals(stateIdentity.context().dimension())
                || !targetLoaded(owned.world()))) fail("Owned state plan/target/chunk lost");
        else if(stateMode(owned)) {
            long eligible=MapArtClient.status().engine().eligibleTicks();
            if(client.player.age<stateAge || eligible<stateEligible) fail("State diagnostic clock moved backwards");
            else { stateAge=client.player.age; stateEligible=eligible; }
        }
        else if(owned.mode()==Mode.SUPPORT_AVAILABILITY) {
            var gate=support; var id=gate.identity(); var engine=MapArtClient.status().engine();
            if(client!=owned.client() || client.player!=owned.player() || owned.handler().getWorld()!=owned.world()
                    || !state.context().orElseThrow().equals(id.context()) || state.phase()!=Phase.RUNNING
                    || !owned.world().getRegistryKey().getValue().toString().equals(id.context().dimension())
                    || !loaded(owned.world(),id.target()) || !unbuilt(client,id.target()) || engine.pendingTargetIds().contains(id.targetId()))
                fail("Owned support binding/context/loaded unbuilt target lost");
            else if(client.player.age<stateAge || engine.eligibleTicks()<stateEligible)
                fail("Support diagnostic clock moved backwards");
            else { stateAge=client.player.age; stateEligible=engine.eligibleTicks(); }
        }
    }

    private static boolean withinWindow(int age,int initialAge) {
        long elapsed=(long)age-initialAge;
        return elapsed>=0 && elapsed<=WINDOW;
    }

    private static void fail(String detail) {
        if(scope!=null && scope.mode()==Mode.SUPPORT_AVAILABILITY) {
            support=failedSupport(support,detail,failure==null); // First cause/barrier precede disposal on EVERY shared failure route.
            if(failure==null) failure=support.firstCause().orElseThrow();
            scope=null;
            stateStage=StateStage.INACTIVE;
            return;
        }
        if(failure==null) failure=detail;
        scope=null;
        stateStage=StateStage.INACTIVE;
    }

    private static boolean stateMode(Scope owned) { return owned.mode()==Mode.STATE || owned.mode()==Mode.REPEATED_STATE; }

    static void armState(MinecraftClient client,MinecraftServer server,Path save,UUID runId,BuildPlan plan,int targetId,boolean repeated) {
        requireThread(client);
        requireArmable(scope!=null,failure,support,runId);
        var context=MapArtClient.status().engine().lifecycle().context().orElseThrow();
        var target=plan.targets().stream().filter(t -> t.id()==targetId).findFirst().orElseThrow();
        var relative=target.relativePos(); var origin=context.origin();
        var pos=new BlockPos(Math.addExact(origin.x(),relative.x()),Math.addExact(origin.y(),relative.y()),Math.addExact(origin.z(),relative.z()));
        if(!plan.fingerprint().equals(context.planFingerprint()) || !relative.equals(new GridPos(0,0,0)) || !pos.equals(new BlockPos(0,65,0))
                || client.world==null || client.world.getChunkManager().getChunk(0,0,ChunkStatus.FULL,false)==null
                || !client.world.getBlockState(pos).isAir()
                || ((PendingPredictionView)((ClientWorldPendingUpdateAccessor)client.world).mapart$getPendingUpdateManager()).mapart$hasPendingPrediction(pos))
            throw new IllegalStateException("Refused state plan/target/initial chunk/air/prediction");
        arm(client,server,save,runId,repeated?Mode.REPEATED_STATE:Mode.STATE,null);
        stateIdentity=new StateIdentity(context,targetId,new GridPos(pos.getX(),pos.getY(),pos.getZ()),scope.save().toString(),
                System.identityHashCode(server),System.identityHashCode(client.world),System.identityHashCode(scope.handler()),
                System.identityHashCode(scope.connection()),client.player.age);
        stateAge=client.player.age; stateEligible=MapArtClient.status().engine().eligibleTicks();
        stateStage=repeated?StateStage.FIRST_PREDICTION:StateStage.MAIN;
    }

    static String snowState(int layers) { return Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS,layers).toString(); }

    private static BlockPos statePos() {
        var p=stateIdentity.target(); return new BlockPos(p.x(),p.y(),p.z());
    }

    private static boolean targetLoaded(ClientWorld world) {
        var p=stateIdentity.target();
        return world.getChunkManager().getChunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16),ChunkStatus.FULL,false)!=null;
    }

    private static boolean stateDemand(boolean condition,String detail) {
        if(!condition) fail(detail);
        return condition;
    }

    private static boolean stateReady(MinecraftClient client,ClientPlayNetworkHandler handler,ClientWorld world) {
        if(scope==null || !stateMode(scope)) return false;
        if(!stateDemand(client.isOnThread(),"State hook outside client thread")) return false;
        if(!stateDemand(handler==scope.handler() && world==scope.world(),"State hook binding changed")) return false;
        check(client);
        return scope!=null;
    }

    private static NativeState nativeState(MinecraftClient client) {
        var pos=statePos(); var world=client.world;
        var manager=((ClientWorldPendingUpdateAccessor)world).mapart$getPendingUpdateManager();
        var view=(PendingPredictionView)manager;
        var before=world.getBlockState(pos); boolean pending=view.mapart$hasPendingPrediction(pos);
        if(!stateDemand(pending==view.mapart$hasPendingPrediction(pos) && pending==view.mapart$hasPendingPrediction(pos)
                && before.equals(world.getBlockState(pos)),"Read-only pending query changed state")) return null;
        return new NativeState(before.toString(),pending,manager.getSequence(),client.player.age,
                MapArtClient.status().engine().eligibleTicks(),client.player.getInventory().getSelectedSlot(),client.player.getInventory().getStack(1).getCount());
    }

    private static boolean eventRoom() { return stateDemand(ordinal<WINDOW*10,"State event bound exceeded"); }

    private static NativeState recordState(MinecraftClient client,String boundary,int sequence) {
        if(!eventRoom()) return null;
        var sample=nativeState(client);
        if(sample!=null) stateEvents.add(new StateEvent(++ordinal,boundary,boundary.endsWith("NATIVE")?sample.managerSequence():sequence,sample));
        return sample;
    }

    static void recordFirstNativePrediction(boolean accepted) {
        var client=MinecraftClient.getInstance();
        if(!stateReady(client,client.getNetworkHandler(),client.world)) return;
        if(!stateDemand(stateStage==StateStage.FIRST_PREDICTION && accepted,"First native interaction rejected/stage invalid")) return;
        var first=recordState(client,"FIRST_NATIVE",-1);
        if(first==null || !stateDemand(first.pending() && first.state().equals(snowState(1)) && first.selected()==1 && first.usable()==1,
                "First native snow prediction/inventory missing")) return;
        n1=first.managerSequence(); stateStage=StateStage.FIRST_RESPONSE;
    }

    private static void beforeNormalResponse(MinecraftClient client,int sequence) {
        var before=recordState(client,"PRE",sequence);
        if(before==null || scope.mode()!=Mode.REPEATED_STATE) return;
        if(stateStage==StateStage.FIRST_RESPONSE && sequence>=n1) {
            if(!stateDemand(sequence==n1,"Response covered first sequence before its exact PRE")) return;
            stateStage=StateStage.REPEAT_CONSUMED; // Consume before native interaction; invalidation cannot leave a live trigger.
            if(!stateDemand(before.pending() && before.state().equals(snowState(1)) && before.selected()==1 && before.usable()==1
                    && client.interactionManager!=null && client.player.getInventory().getStack(1).isOf(Items.SNOW),"Repeat PRE lost first snow/item")) return;
            var pos=statePos();
            var hit=new BlockHitResult(new Vec3d(pos.getX()+.5,pos.getY()+.125,pos.getZ()+.5),Direction.UP,pos,false);
            var result=client.interactionManager.interactBlock(client.player,Hand.MAIN_HAND,hit);
            if(!stateDemand(result.isAccepted(),"Second genuine native interaction rejected")) return;
            var second=recordState(client,"SECOND_NATIVE",sequence);
            if(second==null || !stateDemand(second.pending() && second.state().equals(snowState(2)) && second.managerSequence()>n1
                    && second.usable()==0 && second.selected()==1,"Second native prediction/sequence/inventory missing")) return;
            n2=second.managerSequence(); stateStage=StateStage.OLDER_POST;
        }
    }

    public static void afterNormalResponse(ClientPlayNetworkHandler handler,ClientWorld world,int sequence) {
        if(scope==null || !stateMode(scope)) return;
        var client=MinecraftClient.getInstance();
        if(!stateReady(client,handler,world)) return;
        var after=recordState(client,"POST",sequence);
        if(after==null || scope.mode()!=Mode.REPEATED_STATE) return;
        if(stateStage==StateStage.OLDER_POST) {
            if(stateDemand(sequence==n1 && after.pending() && after.state().equals(snowState(2)),"Older POST lost latest prediction"))
                stateStage=StateStage.COVERING_POST;
        } else if(stateStage==StateStage.COVERING_POST && sequence>=n2) {
            if(stateDemand(!after.pending() && after.state().equals(Blocks.AIR.getDefaultState().toString()),"Covering POST lost original air baseline"))
                stateStage=StateStage.COMPLETE;
        }
    }

    public static boolean withholdState(ClientPlayNetworkHandler handler,ClientWorld world,BlockPos pos,BlockState state,int flags,StateRoute route) {
        if(scope==null || !stateMode(scope)) return false;
        var client=MinecraftClient.getInstance();
        if(!stateReady(client,handler,world) || !pos.equals(statePos())) return false;
        if(!eventRoom()) return false;
        incomingStates.add(new IncomingState(++ordinal,route,new GridPos(pos.getX(),pos.getY(),pos.getZ()),state.toString(),flags,
                client.player.age,MapArtClient.status().engine().eligibleTicks()));
        return true;
    }

    public static void beforeChunkImport(ClientPlayNetworkHandler handler,int x,int z) {
        if(scope==null || !stateMode(scope)) return;
        var client=MinecraftClient.getInstance();
        if(!stateReady(client,handler,client.world)) return;
        var p=stateIdentity.target();
        if(x!=Math.floorDiv(p.x(),16) || z!=Math.floorDiv(p.z(),16)) return;
        if(!eventRoom()) return;
        importEvent=new ImportEvent(++ordinal,x,z,targetLoaded(client.world),stateIdentity,client.player.age,MapArtClient.status().engine().eligibleTicks());
        fail("TARGET_CHUNK_RELOAD");
    }

    static StateSnapshot stateSnapshot() {
        var client=MinecraftClient.getInstance(); requireThread(client); check(client);
        return new StateSnapshot(scope!=null && stateMode(scope),Optional.ofNullable(failure),stateIdentity,stateStage.name(),n1,n2,
                List.copyOf(stateEvents),List.copyOf(incomingStates),Optional.ofNullable(importEvent));
    }

    private static void requireThread(MinecraftClient client) {
        if(!client.isOnThread()) throw new IllegalStateException("Fault accessed outside client thread");
    }
}
