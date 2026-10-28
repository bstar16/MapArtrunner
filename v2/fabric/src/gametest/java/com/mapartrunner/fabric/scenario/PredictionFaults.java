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
import com.mapartrunner.fabric.scenario.GroundedSingleLaneGameTest.Pose;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.movement.MovementPolicy.SupportSample;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader.CapturedEvidence;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
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
    private enum Mode { RESPONSE, OBSERVATION, COMPUTED_STOP, COMPUTED_PAUSE_RESUME, STATE, REPEATED_STATE }
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
                    boolean omissionLatched, List<Omission> omissions, Optional<Barrier> barrier, List<BuildEngine.TickDecision> fresh) { }
    record Yaw(float yaw, float head, float body, float last, float lastHead, float lastBody) { }
    record BarrierState(Pose pose, Yaw yaw, List<Boolean> keys, FabricBuildAdapter.Status status, boolean air, boolean pending) { }
    record Barrier(BuildEngine.TickDecision decision, int decisionIdentity, BarrierState before,
                   Optional<BarrierState> paused, BarrierState after) { }
    private record Scope(MinecraftServer server, ClientWorld world, ClientPlayNetworkHandler handler,
                         ClientConnection connection, UUID runId, Path save, int age, Mode mode) { }
    record StateIdentity(RunLifecycle.Context context, int targetId, GridPos target, String save,
                         int server, int world, int handler, int connection, int armedAge) { }
    record NativeState(String state, boolean pending, int managerSequence, int age, long eligible, int selected, int usable) { }
    record StateEvent(long ordinal, String boundary, int sequence, NativeState sample) { }
    record IncomingState(long ordinal, StateRoute route, GridPos position, String state, int flags, int age, long eligible) { }
    record ImportEvent(long ordinal, int chunkX, int chunkZ, boolean loaded, StateIdentity identity, int age, long eligible) { }
    record StateSnapshot(boolean active, Optional<String> failure, StateIdentity identity, String stage, int n1, int n2,
                         List<StateEvent> events, List<IncomingState> incoming, Optional<ImportEvent> imported) { }

    static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId) {
        arm(client,ownedServer,ownedSave,runId,Mode.RESPONSE);
    }

    static void armObservation(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId) {
        arm(client,ownedServer,ownedSave,runId,Mode.OBSERVATION);
    }

    static void armComputed(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, boolean resume) {
        arm(client,ownedServer,ownedSave,runId,resume?Mode.COMPUTED_PAUSE_RESUME:Mode.COMPUTED_STOP);
    }

    private static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId, Mode mode) {
        requireThread(client);
        if(scope!=null || failure!=null) throw new IllegalStateException("Fault already active or failed");
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
        responses.clear();
        omissions.clear(); omissionLatched=false; barrier=null; transitioning=false; fresh.clear();
        stateStage=StateStage.INACTIVE; stateIdentity=null; n1=-1; n2=-1; ordinal=0;
        stateEvents.clear(); incomingStates.clear(); importEvent=null;
        scope=new Scope(ownedServer,client.world,handler,handler.getConnection(),runId,save,client.player.age,mode);
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
        if(!omissionLatched && !engine.pendingTargetIds().contains(55)) return original;
        omissionLatched=true;
        var removed=original.stream().filter(o -> o.targetId()==55).findFirst().orElseThrow();
        var support=captured.supportObservation().cells().get(new GridPos(11,0,0));
        if(support==null || removed.evidence()!=support.evidence() || !removed.observedState().equals(support.state())
                || omissions.size()>=WINDOW*10) {
            fail("Observation/support mismatch or conversion bound exceeded");
            throw new AssertionError(failure);
        }
        var returned=new ArrayList<>(original);
        returned.remove(removed);
        if(returned.size()!=59 || returned.stream().anyMatch(o -> o.targetId()==55)) {
            fail("Observation conversion did not omit only55");
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
                || decision.owner()!=BuildEngine.ControlOwner.NATIVE || decision.placement().filter(p -> p.targetId()==55).isEmpty()
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
        var pose=new Pose(p.getX(),p.getY(),p.getZ(),p.getVelocity(),p.getEyePos(),p.age,c.world.getTime(),p.getYaw(),p.headYaw,p.bodyYaw,
                p.getAbilities().flying,p.isSprinting(),p.getInventory().getSelectedSlot(),p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount());
        var yaw=new Yaw(p.getYaw(),p.headYaw,p.bodyYaw,p.lastYaw,p.lastHeadYaw,p.lastBodyYaw);
        boolean pending=((PendingPredictionView)((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager())
                .mapart$hasPendingPrediction(new BlockPos(11,65,0));
        return new BarrierState(pose,yaw,keys,MapArtClient.status(),c.world.getBlockState(new BlockPos(11,65,0)).isAir(),pending);
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
        return new Snapshot(scope!=null,Optional.ofNullable(failure),List.copyOf(responses),omissionLatched,List.copyOf(omissions),Optional.ofNullable(barrier),List.copyOf(fresh));
    }

    static void clear() {
        requireThread(MinecraftClient.getInstance());
        stateStage=StateStage.INACTIVE;
        scope=null; // Invalidate before disposing native owners; immutable response evidence survives cleanup.
    }

    private static void check(MinecraftClient client) {
        var owned=scope;
        if(owned==null) return;
        var state=MapArtClient.status().engine().lifecycle();
        if(client.getServer()!=owned.server() || client.world!=owned.world() || client.getNetworkHandler()!=owned.handler()
                || owned.handler().getConnection()!=owned.connection() || !owned.connection().isOpen()
                || client.player==null || client.player.getEntityWorld()!=owned.world()
                || !owned.server().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(owned.save())
                || state.context().isEmpty() || !state.context().orElseThrow().runId().equals(owned.runId())
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
    }

    private static boolean withinWindow(int age,int initialAge) {
        long elapsed=(long)age-initialAge;
        return elapsed>=0 && elapsed<=WINDOW;
    }

    private static void fail(String detail) {
        if(failure==null) failure=detail;
        stateStage=StateStage.INACTIVE;
        scope=null;
    }

    private static boolean stateMode(Scope owned) { return owned.mode()==Mode.STATE || owned.mode()==Mode.REPEATED_STATE; }

    static void armState(MinecraftClient client,MinecraftServer server,Path save,UUID runId,BuildPlan plan,int targetId,boolean repeated) {
        requireThread(client);
        if(scope!=null || failure!=null) throw new IllegalStateException("Fault already active or failed");
        var context=MapArtClient.status().engine().lifecycle().context().orElseThrow();
        var target=plan.targets().stream().filter(t -> t.id()==targetId).findFirst().orElseThrow();
        var relative=target.relativePos(); var origin=context.origin();
        var pos=new BlockPos(Math.addExact(origin.x(),relative.x()),Math.addExact(origin.y(),relative.y()),Math.addExact(origin.z(),relative.z()));
        if(!plan.fingerprint().equals(context.planFingerprint()) || targetId!=0 || !pos.equals(new BlockPos(0,65,0))
                || client.world==null || client.world.getChunkManager().getChunk(0,0,ChunkStatus.FULL,false)==null
                || !client.world.getBlockState(pos).isAir()
                || ((PendingPredictionView)((ClientWorldPendingUpdateAccessor)client.world).mapart$getPendingUpdateManager()).mapart$hasPendingPrediction(pos))
            throw new IllegalStateException("Refused state plan/target/initial chunk/air/prediction");
        arm(client,server,save,runId,repeated?Mode.REPEATED_STATE:Mode.STATE);
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
