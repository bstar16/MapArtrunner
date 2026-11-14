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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
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
        ServerTickEvents.END_SERVER_TICK.register(server -> watch.recordServer(server));
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

    // Native references are authority. Hashes in existing Sample/output remain diagnostics only.
    record ClientBinding(Object world, Object player, Object handler, Object connection, UUID playerId,
                         String dimension, boolean onThread, boolean connectionOpen, boolean handlerWorldMatches) {
        @Override public String toString() {
            return "ClientBinding[world="+System.identityHashCode(world)+", player="+System.identityHashCode(player)
                    +", handler="+System.identityHashCode(handler)+", connection="+System.identityHashCode(connection)
                    +", UUID="+playerId+", dimension="+dimension+", onClientThread="+onThread
                    +", connectionOpen="+connectionOpen+", handlerWorldMatches="+handlerWorldMatches+"]";
        }
        boolean matches(ClientBinding other) {
            return other!=null && onThread && other.onThread && world!=null && player!=null && handler!=null && connection!=null
                    && connectionOpen && other.connectionOpen && handlerWorldMatches && other.handlerWorldMatches
                    && world==other.world && player==other.player && handler==other.handler && connection==other.connection
                    && playerId.equals(other.playerId) && dimension.equals(other.dimension);
        }
    }
    record ServerBinding(Object server, Object world, Object player, UUID playerId, String dimension,
                         boolean onThread, boolean soleMember) {
        @Override public String toString() {
            return "ServerBinding[server="+System.identityHashCode(server)+", world="+System.identityHashCode(world)
                    +", player="+System.identityHashCode(player)+", UUID="+playerId+", dimension="+dimension
                    +", onServerThread="+onThread+", soleMember="+soleMember+"]";
        }
        boolean matches(ServerBinding other) {
            return other!=null && onThread && other.onThread && soleMember && other.soleMember
                    && server!=null && world!=null && player!=null && server==other.server && world==other.world
                    && player==other.player && playerId.equals(other.playerId) && dimension.equals(other.dimension);
        }
    }
    record Binding(UUID generation, RunLifecycle.Context context, ClientBinding client, ServerBinding server,
                   ScenarioHost.Inspect inspect, ScenarioHost.Scope scope) {
        @Override public String toString() {
            return "Binding[generation="+generation+", context="+context+", client="+client+", server="+server
                    +", inspectHost="+System.identityHashCode(inspect)+", scope="+System.identityHashCode(scope)
                    +", operation="+(scope==null?null:scope.operation())+", kind="+(scope==null?null:scope.kind())
                    +", monotonicDeadlineNanos="+(scope==null?null:scope.deadlineNanos())+"]";
        }
        Binding {
            Objects.requireNonNull(generation); Objects.requireNonNull(context);
            Objects.requireNonNull(client); Objects.requireNonNull(server);
            if((inspect==null)!=(scope==null)) throw new IllegalArgumentException("Incomplete inspect observation scope");
            if(!client.matches(client) || !server.matches(server) || !client.playerId.equals(server.playerId)
                    || !context.dimension().equals(client.dimension) || !context.dimension().equals(server.dimension))
                throw new IllegalArgumentException("Invalid owning-thread observation binding");
        }
        boolean currentScope() { return inspect==null || inspect.observationScopeValid(scope); }
    }
    record EndPublication(Binding binding, Sample sample, double reach) {
        @Override public String toString() {
            return "EndPublication[generation="+binding.generation+", run="+binding.context.runId()
                    +", clientFrame="+sample.tick+", phase="+sample.phase+", actualClientReach="+reach+"]";
        }
    }
    record ServerReading(ServerBinding binding, double x, double y, double z, double reach,
                         int age, long worldTime, int serverTick) { }
    record ResumeMarker(Binding binding, ScenarioRunner.ClientTransition transition) { }
    record ResumeLower(ResumeMarker marker, long callback, ServerReading reading) { }
    record ServerRead(Binding binding, List<EndPublication> publications, long callback, ResumeMarker resume) {
        ServerRead { publications=List.copyOf(publications); }
    }
    record ServerWitness(EndPublication publication, long callback, ServerReading reading) { }

    static ClientBinding clientBinding(MinecraftClient client) {
        var p=client.player; var handler=client.getNetworkHandler();
        var connection=handler==null?null:handler.getConnection();
        return new ClientBinding(client.world,p,handler,connection,
                p==null?null:p.getUuid(),client.world==null?null:client.world.getRegistryKey().getValue().toString(),
                client.isOnThread() && p!=null && p.getEntityWorld()==client.world,
                connection!=null && connection.isOpen(),handler!=null && handler.getWorld()==client.world);
    }

    static ServerBinding serverBinding(MinecraftServer server, ServerWorld world, ServerPlayerEntity player) {
        return new ServerBinding(server,world,player,player==null?null:player.getUuid(),
                world==null?null:world.getRegistryKey().getValue().toString(),
                server.isOnThread() && world!=null && server.getOverworld()==world && player!=null && player.getEntityWorld()==world,
                server.getPlayerManager().getPlayerList().size()==1 && server.getPlayerManager().getPlayerList().getFirst()==player);
    }

    /** Client owns samples/compact capture; the short gate linearizes paired publication, server commit and freeze. */
    static final class Watch {
        private final Object gate=new Object();
        private final List<Sample> samples = new ArrayList<>();
        private final List<ServerWitness> witnesses=new ArrayList<>();
        private Binding binding;
        private final List<EndPublication> pending=new ArrayList<>();
        private ServerRead inFlight;
        private ResumeMarker resume;
        private ResumeLower lower;
        private boolean resumePending;
        private long lastPublishedFrame=-1;
        private long serverCallbacks;
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
        private volatile boolean frozen;

        record Retained(List<Sample> samples, Throwable failure, Binding binding, List<ServerWitness> witnesses,
                        ResumeMarker resume, ResumeLower lower) {
            Retained { samples = List.copyOf(samples); witnesses=List.copyOf(witnesses); }
        }

        // Inspect settlement retains the original binding and failure until archive-before-clear/arm.
        void freeze() { synchronized(gate) { frozen = true; pending.clear(); inFlight=null; resumePending=false; } }

        Retained retained() { synchronized(gate) { return new Retained(samples, failure,binding,witnesses,resume,lower); } }

        void armResume(Binding expected,ScenarioRunner.ClientTransition transition,ClientBinding facts) {
            synchronized(gate) {
                if(expected==null || binding!=expected || frozen || failure!=null || resume!=null
                        || !expected.currentScope() || !expected.client.matches(facts))
                    throw new IllegalStateException("Resume capture lost exact active native binding/scope");
                var before=transition.before().sample(); var after=transition.after().sample();
                if(!before.originalWorld || !after.originalWorld || after.pose==null || !Double.isFinite(after.pose.z)
                        || before.tick!=after.tick || before.status.engine().lifecycle().phase()!=RunLifecycle.Phase.PAUSED
                        || after.status.engine().lifecycle().phase()!=RunLifecycle.Phase.RUNNING
                        || !after.status.engine().lifecycle().context().filter(expected.context::equals).isPresent()
                        || !ScenarioRunner.retainedProgress(before.status.engine(),after.status.engine()))
                    throw new IllegalStateException("Resume capture changed atomic transition/context/progress");
                // Arm after R's validation, inside its client callback; an existing claim cannot inherit this marker.
                resume=new ResumeMarker(expected,transition); resumePending=true;
            }
        }

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

        void arm(MinecraftClient client, BuildPlan plan, Binding binding) {
            requireClientThread(client);
            arm(client,plan);
            if(!run.equals(binding.context) || !plan.fingerprint().equals(run.planFingerprint())
                    || !binding.client.matches(clientBinding(client))) throw new IllegalStateException("Observer arm lost native run binding");
            bind(binding);
        }

        // Package-local scalar seam: tests exercise the same generation/scope/commit policy without native SDK objects.
        void bind(Binding fresh) {
            synchronized(gate) {
                if(binding!=null || frozen || !fresh.currentScope()) throw new IllegalStateException("Observer binding unavailable");
                binding=fresh;
            }
        }

        void clear() {
            if(traversalCapture!=null) traversalCapture.requireSealedForClear();
            synchronized(gate) {
                binding=null; pending.clear(); inFlight=null; resumePending=false; // Invalidate authority before histories; reopen last.
                resume=null; lower=null;
                witnesses.clear(); serverCallbacks=0; lastPublishedFrame=-1;
                traversalCapture=null; originalPlayer=null; originalConnection=null;
                traversalWriting=false; releaseEndRequested=false;
                plan = null; run = null; world = null; handler = null;
                samples.clear(); failure = null; tick = 0;
                frozen = false;
            }
        }

        void closeWorld() {
            if(traversalCapture!=null) traversalCapture.requireSealedForClear();
            synchronized(gate) {
                if(binding!=null) { frozen=true; pending.clear(); inFlight=null; resumePending=false; } // Disarm before automatic native close.
            }
            world = null; handler = null;
        }

        void record(MinecraftClient client, String phase) {
            if (frozen) return;
            if(traversalCapture!=null) { recordTraversal(client,phase); return; }
            Binding armed;
            synchronized(gate) {
                if (plan == null || failure != null || frozen) return;
                armed=binding;
                if(armed!=null && !armed.currentScope()) return;
                if (phase.equals("START")) tick++;
            }
            try {
                var sample=sample(client, phase);
                var facts=armed!=null && phase.equals("END")?clientBinding(client):null;
                double reach=facts==null?0:client.player.getBlockInteractionRange();
                append(armed,sample,facts,reach);
            } catch (RuntimeException | Error error) {
                fail(armed,error); // The worker, not a lifecycle callback, reports this failure.
            }
        }

        void append(Binding armed, Sample sample, ClientBinding facts, double reach) {
            synchronized(gate) {
                if(frozen || binding!=armed || failure!=null || armed!=null && !armed.currentScope()) return;
                if(samples.size()>=6000) { latch(new AssertionError("Client observer exceeded 6000 samples")); return; }
                samples.add(sample);
                if(armed==null || !sample.phase.equals("END")) return;
                if(!armed.client.matches(facts) || !sample.originalWorld || sample.pose==null
                        || !sample.status.engine().lifecycle().context().filter(armed.context::equals).isPresent()
                        || !Double.isFinite(reach) || reach<=0) {
                    latch(new IllegalStateException("Native client END lost exact observation binding/reach")); return;
                }
                if(sample.tick<=lastPublishedFrame) {
                    latch(new IllegalStateException("Duplicate/reordered client END publication")); return;
                }
                lastPublishedFrame=sample.tick;
                pending.add(new EndPublication(armed,sample,reach)); // Every actual END waits for its next claim, regardless of stage.
            }
        }

        ServerRead beginServerRead() {
            synchronized(gate) {
                if(frozen || failure!=null || binding==null) return null;
                if(!binding.currentScope()) { pending.clear(); inFlight=null; resumePending=false; return null; }
                long callback=++serverCallbacks; // Physical callback ordinal, including idle callbacks without a scalar read.
                if(inFlight!=null || pending.isEmpty() && !resumePending) return null;
                // Detach under the gate. ENDs published during the outside-gate read belong to the next callback.
                inFlight=new ServerRead(binding,pending,callback,resumePending?resume:null);
                resumePending=false; // One fresh claim owns L, including when no real END is waiting; never retry it.
                pending.clear();
                return inFlight;
            }
        }

        void recordServer(MinecraftServer server) {
            var read=beginServerRead();
            if(read==null) return;
            try {
                var expected=read.binding.server;
                // All mutable reads below are server-owned. Never consult the client or Runner.requireServer here.
                if(!server.isOnThread()) throw new IllegalStateException("Server END requires owning thread");
                var world=server.getOverworld();
                var player=server.getPlayerManager().getPlayer(expected.playerId);
                var facts=serverBinding(server,world,player);
                if(!expected.matches(facts)) throw new IllegalStateException("Native server END lost exact world/player/UUID/membership binding");
                var reading=new ServerReading(facts,player.getX(),player.getY(),player.getZ(),player.getBlockInteractionRange(),
                        player.age,world.getTime(),server.getTicks());
                commitServer(read,reading);
            } catch(RuntimeException | Error error) { failRead(read,error); }
        }

        void commitServer(ServerRead read, ServerReading reading) {
            synchronized(gate) {
                if(!consume(read)) return;
                if(read.publications.isEmpty() && read.resume==null || read.publications.stream().anyMatch(p -> p.binding!=binding)
                        || read.resume!=null && (read.resume!=resume || read.resume.binding!=binding || lower!=null)) {
                    latch(new IllegalStateException("Server claim lost exact publication binding")); return;
                }
                if(!binding.server.matches(reading.binding) || !Double.isFinite(reading.reach) || reading.reach<=0
                        || read.resume!=null && (!Double.isFinite(reading.x) || !Double.isFinite(reading.y) || !Double.isFinite(reading.z))) {
                    latch(new IllegalStateException("Native server END lost exact binding/reach")); return;
                }
                if(read.publications.size()>6000-witnesses.size()) { latch(new AssertionError("Server observer exceeded 6000 witnesses")); return; }
                if(read.resume!=null) lower=new ResumeLower(read.resume,read.callback,reading);
                for(var publication:read.publications) witnesses.add(new ServerWitness(publication,read.callback,reading));
            }
        }

        private boolean consume(ServerRead read) {
            if(read==null || inFlight!=read) return false;
            inFlight=null; // Exact one-use claim retires even when its original scope became unavailable.
            if(frozen || failure!=null || binding!=read.binding) return false;
            if(!binding.currentScope()) { pending.clear(); resumePending=false; return false; }
            return true;
        }
        void failRead(ServerRead read, Throwable error) { synchronized(gate) { if(consume(read)) latch(error); } }
        private void fail(Binding armed, Throwable error) {
            synchronized(gate) { if(!frozen && binding==armed && (armed==null || armed.currentScope())) latch(error); }
        }
        private void latch(Throwable error) {
            if(failure==null) failure=error;
            pending.clear(); inFlight=null; resumePending=false; // Retain accepted R/L and first failure until archive-before-clear.
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
            synchronized(gate) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                return List.copyOf(samples);
            }
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
