package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.runtime.MapArtRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GroundedSingleLaneDebugRunner {
    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();

    private GroundedLaneWalker activeWalker;
    private GroundedSweepLane activeLane;
    private GroundedSchematicBounds activeBounds;
    private GroundedLaneWalkResult lastResult;

    public Optional<String> start(BuildSession session, int laneIndex) {
        Objects.requireNonNull(session, "session");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting debug grounded sweep mode.");
        }

        if (activeWalker != null && !activeWalker.isTerminal()) {
            return Optional.of("Grounded single-lane debug sweep is already active.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }

        GroundedSweepSettings settings = groundedSettings();
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (laneIndex < 0 || laneIndex >= lanes.size()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        GroundedSweepLane selectedLane = lanes.get(laneIndex);
        activeWalker = new GroundedLaneWalker(selectedLane, bounds, settings);
        activeLane = selectedLane;
        activeBounds = bounds;
        lastResult = null;
        return Optional.empty();
    }

    public void tick(MinecraftClient client) {
        if (client == null || client.player == null || activeWalker == null) {
            displacementAlert.stop();
            return;
        }

        Vec3d playerPos = client.player.getEntityPos();
        activeWalker.tick(playerPos);
        applyMovementControls(client, activeWalker.currentCommand(playerPos));

        boolean shouldAlert = !activeWalker.isTerminal() && activeWalker.isBelowBuildArea(playerPos);
        displacementAlert.tick(client, shouldAlert);

        if (activeWalker.isTerminal()) {
            lastResult = activeWalker.result();
            deactivate(client);
        }
    }

    public Optional<String> stop() {
        if (activeWalker == null) {
            return Optional.of("Grounded single-lane debug sweep is not active.");
        }
        if (!activeWalker.isTerminal()) {
            activeWalker.interrupt();
        }
        lastResult = activeWalker.result();
        deactivate(MinecraftClient.getInstance());
        return Optional.empty();
    }

    public Optional<GroundedLaneWalkResult> activeResult() {
        if (activeWalker == null) {
            return Optional.empty();
        }
        return Optional.of(activeWalker.result());
    }

    public Optional<GroundedLaneWalkResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    public Optional<GroundedSweepLane> activeLane() {
        return Optional.ofNullable(activeLane);
    }

    public Optional<GroundedSchematicBounds> activeBounds() {
        return Optional.ofNullable(activeBounds);
    }

    private GroundedSweepSettings groundedSettings() {
        var mapSettings = MapArtRuntime.settingsStore().current();
        return new GroundedSweepSettings(
                false,
                mapSettings.sweepHalfWidth(),
                mapSettings.sweepTotalWidth(),
                mapSettings.sweepTotalWidth(),
                1,
                1,
                mapSettings.groundedSweepConstantSprint(),
                1.0
        );
    }

    private static void applyMovementControls(MinecraftClient client, GroundedLaneWalker.GroundedControlCommand command) {
        if (client.player == null || client.options == null) {
            return;
        }
        client.player.setYaw(command.yaw());
        client.player.setSprinting(command.sprinting());
        setKey(client.options.forwardKey, command.forwardPressed());
        setKey(client.options.backKey, command.backPressed());
        setKey(client.options.leftKey, command.leftPressed());
        setKey(client.options.rightKey, command.rightPressed());
        setKey(client.options.jumpKey, command.jumpPressed());
        setKey(client.options.sneakKey, command.sneakPressed());
    }

    private static void clearMovementControls(MinecraftClient client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        setKey(client.options.forwardKey, false);
        setKey(client.options.backKey, false);
        setKey(client.options.leftKey, false);
        setKey(client.options.rightKey, false);
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);
        client.player.setSprinting(false);
    }

    private static void setKey(KeyBinding key, boolean pressed) {
        if (key != null) {
            key.setPressed(pressed);
        }
    }

    private void deactivate(MinecraftClient client) {
        clearMovementControls(client);
        displacementAlert.stop();
        activeWalker = null;
        activeLane = null;
        activeBounds = null;
    }
}
