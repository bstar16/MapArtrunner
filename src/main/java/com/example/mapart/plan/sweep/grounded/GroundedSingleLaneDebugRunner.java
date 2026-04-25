package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GroundedSingleLaneDebugRunner {
    private final GroundedSweepLanePlanner planner = new GroundedSweepLanePlanner();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();

    private GroundedLaneWalker activeWalker;
    private GroundedSchematicBounds activeBounds;
    private GroundedLaneWalkResult lastResult;
    private GroundedSweepLane activeLane;

    public Optional<String> start(BuildSession session, int laneIndex, boolean constantSprint) {
        Objects.requireNonNull(session, "session");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting grounded lane debug mode.");
        }
        if (activeWalker != null && !isTerminal(activeWalker.state())) {
            return Optional.of("Grounded single-lane debug walk is already active.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, session.getOrigin());
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);
        if (laneIndex < 0 || laneIndex >= lanes.size()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        activeBounds = bounds;
        activeLane = lanes.get(laneIndex);
        activeWalker = new GroundedLaneWalker(activeLane, activeBounds, constantSprint);
        lastResult = null;
        return Optional.empty();
    }

    public void tick(MinecraftClient client) {
        if (client == null || client.player == null || activeWalker == null || activeBounds == null) {
            return;
        }

        if (isTerminal(activeWalker.state())) {
            lastResult = activeWalker.result();
            deactivate(client);
            return;
        }

        Vec3d playerPos = client.player.getEntityPos();
        activeWalker.tick(playerPos);
        applyControls(client, activeWalker.currentCommand());

        boolean activeGroundedBuild = !isTerminal(activeWalker.state());
        boolean displacedBelowBuildArea = playerPos.y < activeBounds.minY();
        displacementAlert.tick(client, activeGroundedBuild, displacedBelowBuildArea);

        if (isTerminal(activeWalker.state())) {
            lastResult = activeWalker.result();
            deactivate(client);
        }
    }

    public Optional<String> stop() {
        if (activeWalker == null) {
            return Optional.of("Grounded single-lane debug walk is not active.");
        }

        if (!isTerminal(activeWalker.state())) {
            activeWalker.interrupt();
        }
        lastResult = activeWalker.result();
        deactivate(MinecraftClient.getInstance());
        return Optional.empty();
    }

    public Optional<GroundedLaneWalkResult> activeResult() {
        return activeWalker == null ? Optional.empty() : Optional.of(activeWalker.result());
    }

    public Optional<GroundedLaneWalkResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    public Optional<GroundedSweepLane> activeLane() {
        return Optional.ofNullable(activeLane);
    }

    private static void applyControls(MinecraftClient client, GroundedLaneWalkCommand command) {
        if (client.options == null || client.player == null) {
            return;
        }
        client.player.setYaw(command.yaw());
        client.player.setSprinting(command.sprinting());
        setKey(client.options.forwardKey, command.forwardPressed());
        setKey(client.options.backKey, command.backPressed());
        setKey(client.options.leftKey, command.leftPressed());
        setKey(client.options.rightKey, command.rightPressed());
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);
    }

    private static void clearControls(MinecraftClient client) {
        if (client == null || client.options == null || client.player == null) {
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

    private static boolean isTerminal(GroundedLaneWalkState state) {
        return state == GroundedLaneWalkState.COMPLETE
                || state == GroundedLaneWalkState.FAILED
                || state == GroundedLaneWalkState.INTERRUPTED;
    }

    private void deactivate(MinecraftClient client) {
        clearControls(client);
        activeWalker = null;
        activeBounds = null;
        activeLane = null;
    }
}
