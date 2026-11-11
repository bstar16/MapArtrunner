package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import com.mapartrunner.fabric.scenario.TraversalCapture.*;

/** One client-thread observer shared by managed scenarios; inactive between armed cases. */
final class ScenarioObserver {
    private static Watch watch;

    private ScenarioObserver() { }

    static Watch install(MinecraftClient host) {
        if (!host.isOnThread()) throw new IllegalStateException("Scenario observer installation requires the client thread");
        if (watch != null) return watch;
        watch = new Watch();
        ClientTickEvents.START_CLIENT_TICK.register(client -> watch.record(client, "START"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> watch.record(client, "END"));
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> watch.record(client, "WORLD_CHANGE"));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var connection = handler.getConnection();
            boolean onClient = client.isOnThread();
            client.execute(() -> {
                if (watch.handler == handler && handler.getConnection() == connection) {
                    watch.record(client, "DISCONNECT:eventOnClient=" + onClient);
                }
            });
        });
        return watch;
    }

    record Pose(double x, double y, double z, Vec3d velocity, Vec3d eyes, int age, long worldTime,
                float yaw, float headYaw, float bodyYaw, boolean flying, boolean sprinting,
                int selectedSlot, int reserved, int usable) { }
    record Sample(long tick, String phase, int worldIdentity, boolean originalWorld, Pose pose, List<Boolean> keys,
                  boolean paused, FabricBuildAdapter.Status status, List<PlacementObservation> observations) {
        String line() {
            return "client tick=" + tick + "; phase=" + phase + "; world=" + worldIdentity + "; originalWorld=" + originalWorld
                    + "; pose=" + pose + "; keys=" + keys + "; paused=" + paused + "; status=" + status
                    + "; observations=" + observations;
        }
    }

    /** All mutable observer state is client-thread-owned, armed once and cleared after case closure. */
    static final class Watch {
        private final List<Sample> samples = new ArrayList<>();
        private BuildPlan plan;
        private RunLifecycle.Context run;
        private ClientWorld world;
        private ClientPlayNetworkHandler handler;
        private Throwable failure;
        private long tick;
        // Compact arm owns these until seal-before-clear; terminal capture retains release evidence.
        private TraversalCapture traversalCapture;
        private Object originalPlayer;
        private Object originalConnection;
        private boolean traversalWriting;
        private boolean releaseEndRequested;
        private boolean frozen;

        record Retained(List<Sample> samples, Throwable failure) {
            Retained { samples = List.copyOf(samples); }
        }

        // Inspect settlement retains the original binding and failure until archive-before-clear/arm.
        void freeze() { frozen = true; }

        Retained retained() { return new Retained(samples, failure); }

        void armTraversal(MinecraftClient client, BuildPlan plan, TraversalCapture capture) {
            requireClientThread(client);
            clear();
            capture.validatePlan(plan);
            var context=MapArtClient.status().engine().lifecycle().context().orElseThrow();
            if(!capture.runId().equals(context.runId()) || !plan.fingerprint().equals(context.planFingerprint()))
                throw capture.fail(new IllegalStateException("Capture run/plan differs from observer run"));
            this.plan=plan; this.run=context; this.world=client.world; this.handler=client.getNetworkHandler();
            originalPlayer=client.player; originalConnection=handler==null?null:handler.getConnection();
            traversalCapture=capture; traversalWriting=true;
            try {
                recordTraversal(client,"INITIAL");
                if(failure!=null) throw capture.fail(failure);
                fullCheckpoint(client,CheckpointKind.INITIAL,"INITIAL",capture.nextOrdinal()-1);
            }
            catch(RuntimeException | Error error) { traversalWriting=false; failure=error; capture.fail(error); throw error; }
        }

        void requestReleaseEnd() {
            requireClientThread(MinecraftClient.getInstance());
            if(traversalCapture==null || !traversalWriting || releaseEndRequested
                    || !traversalCapture.hasCheckpoint(CheckpointKind.TERMINAL)
                    || traversalCapture.hasCheckpoint(CheckpointKind.RELEASE_END))
                throw new IllegalStateException("Release END request requires an active terminal compact trace");
            releaseEndRequested=true;
        }

        TraversalCapture.Sealed sealTraversal() {
            requireClientThread(MinecraftClient.getInstance());
            if(traversalCapture==null) throw new IllegalStateException("No compact trace");
            traversalWriting=false; // Disarm before flush/close or any archive/screenshot callback.
            return traversalCapture.seal();
        }

        void arm(MinecraftClient client, BuildPlan plan) {
            clear();
            this.plan = plan;
            this.run = MapArtClient.status().engine().lifecycle().context().orElseThrow();
            this.world = client.world;
            this.handler = client.getNetworkHandler();
        }

        void clear() {
            if(traversalCapture!=null) traversalCapture.requireSealedForClear();
            traversalCapture=null; originalPlayer=null; originalConnection=null;
            traversalWriting=false; releaseEndRequested=false;
            plan = null; run = null; world = null; handler = null;
            samples.clear(); failure = null; tick = 0;
            frozen = false;
        }

        void closeWorld() {
            if(traversalCapture!=null) traversalCapture.requireSealedForClear();
            world = null; handler = null;
        }

        void record(MinecraftClient client, String phase) {
            if (frozen) return;
            if(traversalCapture!=null) { recordTraversal(client,phase); return; }
            if (plan == null || failure != null) return;
            try {
                if (phase.equals("START")) tick++;
                if (samples.size() >= 6000) throw new AssertionError("Client observer exceeded 6000 samples");
                samples.add(sample(client, phase));
            } catch (RuntimeException | Error error) {
                failure = error; // The gametest thread, not a lifecycle callback, reports this failure.
            }
        }

        Sample sample(MinecraftClient client, String phase) {
            if(traversalCapture!=null) throw new IllegalStateException("Compact trace has no baseline Sample");
            var p = client.player;
            Pose pose = p == null || p.getEntityWorld() != client.world ? null : new Pose(
                    p.getX(), p.getY(), p.getZ(), p.getVelocity(), p.getEyePos(), p.age, client.world.getTime(),
                    p.getYaw(), p.headYaw, p.bodyYaw, p.getAbilities().flying, p.isSprinting(),
                    p.getInventory().getSelectedSlot(), p.getInventory().getStack(0).getCount(),
                    p.getInventory().getStack(1).getCount());
            var o = client.options;
            var keys = List.of(o.forwardKey.isPressed(), o.backKey.isPressed(), o.leftKey.isPressed(),
                    o.rightKey.isPressed(), o.jumpKey.isPressed(), o.sneakKey.isPressed(), o.sprintKey.isPressed(),
                    o.useKey.isPressed(), o.attackKey.isPressed());
            var observations = plan != null && world != null && client.world == world ? WorldEvidenceReader.placementObservations(
                    WorldEvidenceReader.capture(client, plan, run, world, handler, tick)) : List.<PlacementObservation>of();
            return new Sample(tick, phase, System.identityHashCode(client.world), client.world != null && client.world == world,
                    pose, keys, client.isPaused(), MapArtClient.status(), observations);
        }

        List<Sample> copy() {
            if(traversalCapture!=null) throw new IllegalStateException("Compact trace has no baseline sample list");
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            return List.copyOf(samples);
        }

        private static void requireClientThread(MinecraftClient client) {
            if(!client.isOnThread()) throw new IllegalStateException("Compact observer requires client thread");
        }

        private void recordTraversal(MinecraftClient client,String phase) {
            if(!traversalWriting || failure!=null) return;
            try {
                requireClientThread(client);
                if(phase.equals("START")) tick=Math.incrementExact(tick);
                var status=MapArtClient.status(); var engine=status.engine(); var lifecycle=engine.lifecycle();
                if(lifecycle.context().isEmpty() || !lifecycle.context().orElseThrow().equals(run))
                    throw new IllegalStateException("Compact observer encountered a different run binding");
                if(engine.effectResults().size()>1) throw new IllegalStateException("Compact effect event exceeds production one-issuance stream");
                var p=client.player; var currentHandler=client.getNetworkHandler();
                var connection=currentHandler==null?null:currentHandler.getConnection();
                var identities=new Identities(System.identityHashCode(world),System.identityHashCode(originalPlayer),
                        System.identityHashCode(handler),System.identityHashCode(originalConnection),System.identityHashCode(client.world),
                        System.identityHashCode(p),System.identityHashCode(currentHandler),System.identityHashCode(connection),
                        world!=null && client.world==world,originalPlayer!=null && p==originalPlayer,
                        handler!=null && currentHandler==handler,originalConnection!=null && connection==originalConnection);
                TraversalCapture.Pose pose=null;
                if(p!=null && client.world!=null && p.getEntityWorld()==client.world) {
                    var v=p.getVelocity(); var eyes=p.getEyePos();
                    pose=new TraversalCapture.Pose(p.getX(),p.getY(),p.getZ(),v.x,v.y,v.z,eyes.x,eyes.y,eyes.z,p.age,
                            client.world.getTime(),p.getYaw(),p.headYaw,p.bodyYaw,p.getAbilities().flying,p.isSprinting(),
                            p.getInventory().getSelectedSlot(),p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount(),
                            p.getBlockInteractionRange(),new Physics(p.isOnGround(),p.hasNoGravity(),p.hasNoDrag(),p.isJumping(),p.hasVehicle(),
                            p.isInFluid(),p.isTouchingWater(),p.isSubmergedInWater(),p.isInLava(),p.isClimbing(),p.isGliding(),p.isSwimming(),
                            p.isSneaking(),p.isUsingItem(),!p.getStatusEffects().isEmpty(),p.fallDistance,p.getFinalGravity()));
                }
                var o=client.options;
                int keys=(o.forwardKey.isPressed()?1:0)|(o.backKey.isPressed()?2:0)|(o.leftKey.isPressed()?4:0)
                        |(o.rightKey.isPressed()?8:0)|(o.jumpKey.isPressed()?16:0)|(o.sneakKey.isPressed()?32:0)
                        |(o.sprintKey.isPressed()?64:0)|(o.useKey.isPressed()?128:0)|(o.attackKey.isPressed()?256:0);
                Traversal traversal=null;
                if(engine.traversal().isPresent()) {
                    var t=engine.traversal().orElseThrow(); var entry=t.entry(); var lane=entry.lane();
                    Integer entered=t.substage()==com.mapartrunner.core.run.ForwardTraversal.Substage.LANE
                            && (t.pass()==com.mapartrunner.core.run.ForwardTraversal.Pass.FORWARD
                            || t.pass()==com.mapartrunner.core.run.ForwardTraversal.Pass.REVERSE)?t.laneIndex():null;
                    traversal=new Traversal(t.laneIndex(),entered,t.pass().name(),t.substage().name(),lane.id(),
                            lane.start().x(),lane.start().y(),lane.start().z(),lane.end().x(),lane.end().y(),lane.end().z(),
                            lane.direction().name(),entry.stage().name(),entry.stageElapsedTicks(),entry.entryElapsedTicks(),
                            entry.consecutiveYawAlignedTicks(),entry.supportHoldTicks(),entry.yawHoldTicks(),t.transitionTicks(),
                            t.walkNoProgressTicks(),entry.constantSprint());
                }
                TargetFact first=null,second=null;
                if(!traversalCapture.faultTargets().isEmpty()) {
                    for(var observation:observations(client)) if(traversalCapture.faultTargets().contains(observation.targetId())) {
                        if(first==null) first=TargetFact.from(observation); else second=TargetFact.from(observation);
                    }
                }
                long ordinal=traversalCapture.nextOrdinal();
                Integer sequence=client.world instanceof ClientWorldPendingUpdateAccessor accessor
                        ?accessor.mapart$getPendingUpdateManager().getSequence():null;
                traversalCapture.append(new MotionFrame(ordinal,tick,phase,run.runId(),identities,pose,keys,client.isPaused(),
                        status.controlOwner(),lifecycle.phase(),lifecycle.activity().orElse(null),lifecycle.outcome().orElse(null),
                        engine.diagnostic().map(com.mapartrunner.core.run.BuildEngine.Diagnostic::reason).orElse(null),
                        engine.eligibleTicks(),engine.verifyTicks(),traversal,engine.pendingTargetIds().size(),engine.unfinishedTargetIds().size(),
                        engine.effectResults().isEmpty()?null:engine.effectResults().getFirst(),sequence,first,second));
                if(phase.equals("END")) {
                    if(lifecycle.activity().orElse(null)==RunLifecycle.Activity.VERIFY && !traversalCapture.hasCheckpoint(CheckpointKind.FIRST_VERIFY))
                        fullCheckpoint(client,CheckpointKind.FIRST_VERIFY,phase,ordinal);
                    if(lifecycle.phase()==RunLifecycle.Phase.TERMINAL && !traversalCapture.hasCheckpoint(CheckpointKind.TERMINAL))
                        fullCheckpoint(client,CheckpointKind.TERMINAL,phase,ordinal);
                    if(lifecycle.phase()==RunLifecycle.Phase.TERMINAL && lifecycle.outcome().orElse(null)!=RunLifecycle.Outcome.SUCCESS
                            && !traversalCapture.hasCheckpoint(CheckpointKind.FAILURE))
                        fullCheckpoint(client,CheckpointKind.FAILURE,phase,ordinal);
                    if(releaseEndRequested) {
                        releaseEndRequested=false;
                        fullCheckpoint(client,CheckpointKind.RELEASE_END,phase,ordinal);
                        traversalWriting=false;
                    }
                }
            } catch(RuntimeException | Error error) {
                traversalWriting=false; failure=error;
                // Latch the callback's first cause before any later seal/cleanup operation.
                traversalCapture.fail(error);
            }
        }

        private List<PlacementObservation> observations(MinecraftClient client) {
            return WorldEvidenceReader.placementObservations(WorldEvidenceReader.capture(client,plan,run,world,handler,tick));
        }

        private void fullCheckpoint(MinecraftClient client,CheckpointKind kind,String phase,long ordinal) {
            traversalCapture.checkpoint(kind,new FullScan(run.runId(),tick,phase,ordinal,observations(client)));
        }
    }
}
