package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.BuildEngine.*;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.core.run.RunLifecycle.Phase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One END-client-tick capture/core/effects boundary; lifecycle callbacks never advance core. */
public final class FabricBuildAdapter {
    private final Access access;
    private final ControlArbiter arbiter;
    private final BuildEngine engine = new BuildEngine();
    // Start creates this binding; pause retains run data but revokes effects. Terminal cleanup
    // revokes before release, stops core, then clears these references; diagnostics survive.
    private BuildPlan plan;
    private Settings settings;
    private RunLifecycle.Context context;
    private ClientState bound;
    private ControlArbiter.Binding binding;
    private ControlArbiter.Lease lease;
    private Envelope applying;
    private PlacementEffectResult feedback;
    private Failure failure;
    private long sampleTick = -1;
    private boolean ticking;

    public FabricBuildAdapter(MinecraftClient client) {
        this(new MinecraftAccess(Objects.requireNonNull(client, "client")), ControlArbiter.forClient(client));
    }

    FabricBuildAdapter(Access access, ControlArbiter arbiter) {
        this.access = Objects.requireNonNull(access, "access");
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
    }

    public UUID start(BuildPlan plan, GridPos origin, Settings settings) {
        access.requireClientThread();
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(settings, "settings");
        if (active()) throw new IllegalStateException("Stop the current run before starting another");
        var state = access.state();
        if (!usable(state) || state.player() == null || suspended(state)) {
            throw new IllegalStateException("Start requires a connected world/player without a screen or simulation pause");
        }
        var run = new RunLifecycle.Context(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                state.dimension(), plan.fingerprint(), origin, settings.fingerprint());
        engine.start(plan, run, settings);
        failure = null;
        this.plan = plan; this.settings = settings; context = run; bound = state;
        try {
            if (active()) binding = arbiter.bindRun(run.runId());
            else terminate("start", null, null);
        } catch (RuntimeException | Error error) {
            if (context == run) terminate("start", "Failed to establish run authority", error);
            throw error;
        }
        return run.runId();
    }

    public void pause() {
        access.requireClientThread();
        if (phase() != Phase.RUNNING) return;
        var run = context;
        try {
            revoke();
            engine.pause();
        } catch (RuntimeException | Error error) {
            if (context == run) terminate("pause", "Failed to release controls for pause", error);
            throw error;
        }
    }

    public void resume() {
        access.requireClientThread();
        if (phase() != Phase.PAUSED) throw new IllegalStateException("Resume requires PAUSED");
        var run = context;
        ClientState state;
        try {
            state = access.state();
        } catch (RuntimeException | Error error) {
            if (context == run) terminate("resume", "Failed to inspect resume binding", error);
            throw error;
        }
        if (!matches(state)) {
            terminate("resume", "Changed world/session requires a fresh run", null);
            throw new IllegalStateException("Changed world/session requires a fresh run");
        }
        if (suspended(state)) throw new IllegalStateException("Close the screen and unpause before resuming");
        try {
            engine.resume(run);
            binding = arbiter.bindRun(run.runId());
        } catch (RuntimeException | Error error) {
            if (context == run) terminate("resume", "Failed to resume run authority", error);
            throw error;
        }
    }

    public void stop() {
        access.requireClientThread();
        if (context != null) terminate("stop", null, null);
    }

    public Status status() {
        access.requireClientThread();
        return new Status(engine.snapshot(), arbiter.owner(), Optional.ofNullable(failure));
    }

    public void onWorldChanged(ClientWorld world) { worldChanged(world); }

    void worldChanged(Object world) {
        access.requireClientThread();
        if (context != null && world != bound.world()) terminate("world-change", "Client world was replaced", null);
    }

    public void onDisconnect(ClientPlayNetworkHandler handler, ClientConnection connection) {
        disconnected(handler, connection);
    }

    void disconnected(Object handler, Object connection) {
        access.requireClientThread();
        if (context != null && handler == bound.handler() && connection == bound.connection()) {
            terminate("disconnect", "Bound connection disconnected", null);
        }
    }

    public void onScreenOpened() {
        // shortcut: all screens require manual resume; integrate preserved chat/config/refill screens before their parity gates.
        pause();
    }

    public void onClientStopping() {
        access.requireClientThread();
        if (context != null) terminate("shutdown", "Client is stopping", null);
    }

    public void tick() {
        access.requireClientThread();
        if (ticking) throw new IllegalStateException("Reentrant build callback");
        if (!active()) return;
        ticking = true;
        var run = context;
        var capturedBinding = binding; // Capture before any observation; never refresh this invocation.
        var capturedBound = bound;
        var capturedPlan = plan;
        var capturedSettings = settings;
        Envelope envelope = null;
        String stage = "snapshot";
        try {
            long tick = sampleTick = Math.incrementExact(sampleTick);
            if (!checkLive()) return;
            var previous = feedback == null ? List.<PlacementEffectResult>of() : List.of(feedback);
            feedback = null;
            var facts = access.capture(capturedPlan, run, capturedSettings, capturedBound, tick);
            if (context != run || !checkLive()) return;
            stage = "core";
            var decision = engine.tick(new TickInput(run.runId(), tick, Optional.of(run), facts.player(),
                    facts.support(), facts.observations(), facts.availability(),
                    binding == capturedBinding ? previous : List.of()));
            if (context != run || decision.runId().isEmpty()) return;
            if (decision.snapshot().lifecycle().phase() == Phase.TERMINAL) {
                terminate("terminal", null, null);
                return;
            }
            envelope = new Envelope(capturedBinding, run, capturedBound, capturedPlan, capturedSettings, decision);
            applying = envelope;
            if (!current(envelope, false)) { releaseIfInactive(envelope); return; }
            stage = "ownership";
            if (decision.owner() == ControlOwner.NONE) releaseLease();
            else {
                var acquisition = arbiter.acquire(capturedBinding, decision.owner());
                if (acquisition.status() == ControlArbiter.AcquireStatus.UNAVAILABLE) {
                    terminate(stage, "Requested control owner is unavailable: " + decision.owner(), null);
                    return;
                }
                if (acquisition.status() == ControlArbiter.AcquireStatus.STALE) return;
                lease = acquisition.lease().orElseThrow();
            }
            if (!current(envelope, true)) { releaseIfInactive(envelope); return; }
            stage = "yaw";
            if (decision.movement().isPresent()) {
                access.alignYaw(capturedBound, (float) decision.movement().orElseThrow().desiredYaw());
            }
            if (!current(envelope, true)) { releaseIfInactive(envelope); return; }
            stage = "placement";
            if (decision.placement().isPresent()) {
                var claimed = envelope;
                var result = access.place(capturedPlan, run, capturedSettings, decision.placement().orElseThrow(),
                        arbiter, capturedBinding, () -> current(claimed, true));
                if (!current(envelope, true)) { releaseIfInactive(envelope); return; }
                feedback = result.effect();
            }
            stage = "controls";
            if (decision.owner() == ControlOwner.NATIVE) {
                var mask = decision.movement().map(ControlArbiter.ControlMask::fromMovement)
                        .orElseGet(ControlArbiter.ControlMask::none);
                arbiter.apply(lease, mask);
            }
        } catch (RuntimeException | Error error) {
            // An old callback cannot cancel a new run started reentrantly during capture/effects.
            if (context == run) terminate(stage, "Adapter failed during " + stage, error);
            throw error;
        } finally {
            if (applying == envelope) applying = null;
            ticking = false;
        }
    }

    private boolean current(Envelope envelope, boolean ownerEstablished) {
        if (applying != envelope || context != envelope.run() || plan != envelope.plan() || settings != envelope.settings()
                || bound != envelope.bound() || phase() != Phase.RUNNING || envelope.binding() == null
                || !arbiter.isCurrent(envelope.binding()) || envelope.decision().tick() != sampleTick
                || !envelope.decision().runId().orElseThrow().equals(context.runId())) return false;
        var state = access.state();
        return matches(state) && state.player() == bound.player() && !suspended(state)
                && (!ownerEstablished || arbiter.owner() == envelope.decision().owner());
    }

    private boolean checkLive() {
        var state = access.state();
        if (!matches(state)) {
            terminate("binding", "Current world/session/player binding was lost", null);
            return false;
        }
        if (suspended(state)) pause();
        return true;
    }

    private void releaseIfInactive(Envelope envelope) {
        if (context != envelope.run()) return;
        if (checkLive() && binding == envelope.binding()) releaseLease();
    }

    private boolean matches(ClientState state) {
        return usable(state) && state.world() == bound.world() && state.handler() == bound.handler()
                && state.connection() == bound.connection() && state.dimension().equals(bound.dimension())
                && (state.player() == null || state.player() == bound.player());
    }

    private static boolean usable(ClientState state) {
        return state.connected() && state.world() != null && state.handler() != null && state.connection() != null;
    }

    private static boolean suspended(ClientState state) { return state.simulationPaused() || state.screenOpen(); }
    private Phase phase() { return engine.snapshot().lifecycle().phase(); }
    private boolean active() { return phase() == Phase.RUNNING || phase() == Phase.PAUSED; }

    private void releaseLease() {
        var previous = lease;
        lease = null;
        if (previous != null) arbiter.release(previous);
    }

    private void revoke() {
        applying = null; binding = null; feedback = null;
        try { arbiter.invalidate(); } finally { lease = null; }
    }

    private void terminate(String stage, String detail, Throwable primary) {
        var run = context;
        Throwable error = primary;
        try { revoke(); } catch (RuntimeException | Error cleanup) { error = retain(error, cleanup); }
        try { if (active()) engine.stop(); } catch (RuntimeException | Error cleanup) { error = retain(error, cleanup); }
        finally { plan = null; settings = null; context = null; bound = null; }
        if (failure == null && run != null && (detail != null || error != null)) {
            failure = new Failure(run.runId(), sampleTick, stage, detail == null ? "Failed during " + stage : detail,
                    error == null ? Optional.empty() : Optional.of(trace(error)));
        }
        if (error instanceof Error fatal) throw fatal;
        if (error != null) throw (RuntimeException) error;
    }

    private static Throwable retain(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) {
            for (var prior : first.getSuppressed()) if (prior == next) return first;
            first.addSuppressed(next);
        }
        return first;
    }

    private static String trace(Throwable error) {
        var text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    public record Status(BuildEngine.Snapshot engine, ControlOwner controlOwner, Optional<Failure> failure) { }
    public record Failure(UUID runId, long tick, String stage, String detail, Optional<String> trace) { }

    private record Envelope(ControlArbiter.Binding binding, RunLifecycle.Context run, ClientState bound,
                            BuildPlan plan, Settings settings, TickDecision decision) { }

    interface Access {
        void requireClientThread();
        ClientState state();
        Facts capture(BuildPlan plan, RunLifecycle.Context run, Settings settings, ClientState bound, long tick);
        void alignYaw(ClientState bound, float yaw);
        FabricPlacementEffects.Result place(BuildPlan plan, RunLifecycle.Context run, Settings settings,
                PlacementIntent intent, ControlArbiter arbiter, ControlArbiter.Binding binding, BooleanSupplier currentAction);
    }

    record ClientState(Object world, Object handler, Object connection, Object player, String dimension,
                       boolean connected, boolean simulationPaused, boolean screenOpen) { }

    record Facts(Optional<MovementPolicy.PlayerObservation> player, MovementPolicy.SupportObservation support,
                 List<PlacementObservation> observations, Map<Integer, PlacementAvailability> availability) {
        Facts { observations = List.copyOf(observations); availability = Map.copyOf(availability); }
    }

    private static final class MinecraftAccess implements Access {
        private final MinecraftClient client;
        private MinecraftAccess(MinecraftClient client) { this.client = client; }
        @Override public void requireClientThread() {
            if (!client.isOnThread()) throw new IllegalStateException("Build lifecycle/effects require the client thread");
        }
        @Override public ClientState state() {
            var world = client.world;
            var handler = client.getNetworkHandler();
            var connection = handler == null ? null : handler.getConnection();
            var player = client.player;
            boolean connected = world != null && handler != null && handler.getWorld() == world
                    && connection.isOpen() && (player == null || player.getEntityWorld() == world);
            return new ClientState(world, handler, connection, player, world == null ? "" : world.getRegistryKey().getValue().toString(),
                    connected, client.isPaused(), client.currentScreen != null);
        }
        @Override public Facts capture(BuildPlan plan, RunLifecycle.Context run, Settings settings, ClientState bound, long tick) {
            var captured = WorldEvidenceReader.capture(client, plan, run, (ClientWorld) bound.world(),
                    (ClientPlayNetworkHandler) bound.handler(), tick);
            var player = client.player;
            var pose = player == null ? Optional.<MovementPolicy.PlayerObservation>empty()
                    : Optional.of(new MovementPolicy.PlayerObservation(player.getX() - run.origin().x(),
                            player.getY() - run.origin().y(), player.getZ() - run.origin().z(), player.getYaw()));
            return new Facts(pose, captured.supportObservation(), WorldEvidenceReader.placementObservations(captured),
                    FabricPlacementEffects.availability(client, plan, run, settings));
        }
        @Override public void alignYaw(ClientState bound, float yaw) {
            var player = (ClientPlayerEntity) bound.player();
            // Pinned Yarn 1.21.11 uses last*, not prev*: keep all three yaw interpolants aligned.
            player.setYaw(yaw); player.setHeadYaw(yaw); player.setBodyYaw(yaw);
            player.lastYaw = yaw; player.lastHeadYaw = yaw; player.lastBodyYaw = yaw;
        }
        @Override public FabricPlacementEffects.Result place(BuildPlan plan, RunLifecycle.Context run, Settings settings,
                PlacementIntent intent, ControlArbiter arbiter, ControlArbiter.Binding binding, BooleanSupplier currentAction) {
            return FabricPlacementEffects.apply(client, plan, run, settings, intent, arbiter, binding, currentAction);
        }
    }
}
