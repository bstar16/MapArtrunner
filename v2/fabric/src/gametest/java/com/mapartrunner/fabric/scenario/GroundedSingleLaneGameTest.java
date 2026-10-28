package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Fresh managed worlds; observers never drive production or call gametest contexts. */
public final class GroundedSingleLaneGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try { SingleLaneFixture.verifyCoordinateLookup(); }
        catch(AssertionError error) {
            try { context.takeScreenshot(TestScreenshotOptions.of("coordinate-lookup-failure").disableCounterPrefix()); }
            catch(RuntimeException | Error capture) { error.addSuppressed(capture); }
            throw error;
        }
        var watch = new Watch();
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
        boolean autoJump = context.computeOnClient(client -> client.options.getAutoJump().getValue());
        context.runOnClient(client -> client.options.getAutoJump().setValue(false));
        try {
            SingleLaneScenario disconnected = null;
            for (var kind : SingleLaneScenario.Kind.values()) {
                var scenario = new SingleLaneScenario(context, watch, kind);
                scenario.run(disconnected);
                disconnected = kind == SingleLaneScenario.Kind.DISCONNECT ? scenario : null;
            }
        } finally {
            context.runOnClient(client -> {
                watch.clear();
                client.options.getAutoJump().setValue(autoJump);
            });
        }
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

        void arm(MinecraftClient client, BuildPlan plan) {
            clear();
            this.plan = plan;
            this.run = MapArtClient.status().engine().lifecycle().context().orElseThrow();
            this.world = client.world;
            this.handler = client.getNetworkHandler();
        }

        void clear() {
            plan = null; run = null; world = null; handler = null;
            samples.clear(); failure = null; tick = 0;
        }

        void closeWorld() { world = null; handler = null; }

        void record(MinecraftClient client, String phase) {
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
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            return List.copyOf(samples);
        }
    }
}
