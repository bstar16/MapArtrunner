package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
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
    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final BaritoneFacade baritoneFacade;

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;

    public GroundedSingleLaneDebugRunner(BaritoneFacade baritoneFacade) {
        this.baritoneFacade = Objects.requireNonNull(baritoneFacade, "baritoneFacade");
    }

    public Optional<String> start(BuildSession session, int laneIndex, GroundedSweepSettings settings) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(settings, "settings");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting debug grounded sweep mode.");
        }
        if (activeLane != null || laneWalker.state() == GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            return Optional.of("Grounded single-lane debug run is already active.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (laneIndex < 0 || laneIndex >= lanes.size()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        activeSession = session;
        activeBounds = bounds;
        activeLane = lanes.get(laneIndex);
        awaitingStartApproach = true;
        startApproachIssued = false;
        displacementAlert.reset();
        return Optional.empty();
    }

    public void tick(MinecraftClient client, boolean constantSprint) {
        if (client == null || client.player == null || activeLane == null || activeBounds == null || activeSession == null) {
            return;
        }

        if (awaitingStartApproach) {
            tickStartApproach(client, constantSprint);
            return;
        }

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            clearControls(client);
            return;
        }

        laneWalker.tick(client.player.getEntityPos());
        applyLaneControls(client);
        displacementAlert.tick(client, true, client.player.getY() < activeBounds.minY());

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            clearControls(client);
        }
    }

    public Optional<String> stop() {
        if (!isActive()) {
            return Optional.of("Grounded single-lane debug run is not active.");
        }

        awaitingStartApproach = false;
        laneWalker.interrupt();
        baritoneFacade.cancel();
        activeSession = null;
        activeBounds = null;
        activeLane = null;
        startApproachIssued = false;
        displacementAlert.reset();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            clearControls(client);
        }
        return Optional.empty();
    }

    public DebugStatus status() {
        if (!isActive()) {
            return new DebugStatus(false, null, GroundedLaneWalker.GroundedLaneWalkState.IDLE, false, Optional.empty());
        }

        return new DebugStatus(
                true,
                activeLane.laneIndex(),
                laneWalker.state(),
                awaitingStartApproach,
                laneWalker.failureReason()
        );
    }

    private void tickStartApproach(MinecraftClient client, boolean constantSprint) {
        if (!startApproachIssued) {
            baritoneFacade.goTo(activeLane.startPoint());
            startApproachIssued = true;
        }

        if (!isNearLaneStart(client.player.getEntityPos(), activeLane)) {
            return;
        }

        baritoneFacade.cancel();
        laneWalker.start(activeLane, activeBounds, constantSprint);
        awaitingStartApproach = false;
        applyLaneControls(client);
    }

    private void applyLaneControls(MinecraftClient client) {
        if (client.options == null || client.player == null) {
            return;
        }

        GroundedLaneWalker.GroundedLaneWalkCommand command = laneWalker.currentCommand().orElse(GroundedLaneWalker.GroundedLaneWalkCommand.idle());
        client.player.setYaw(command.yaw());
        setKey(client.options.forwardKey, command.forwardPressed());
        setKey(client.options.backKey, command.backPressed());
        setKey(client.options.leftKey, command.leftPressed());
        setKey(client.options.rightKey, command.rightPressed());
        setKey(client.options.jumpKey, command.jumpPressed());
        setKey(client.options.sneakKey, command.sneakPressed());
        client.player.setSprinting(command.sprinting());
    }

    private static boolean isNearLaneStart(Vec3d playerPosition, GroundedSweepLane lane) {
        Vec3d startCenter = new Vec3d(lane.startPoint().getX() + 0.5, playerPosition.y, lane.startPoint().getZ() + 0.5);
        return playerPosition.squaredDistanceTo(startCenter) <= 2.25;
    }

    private static void clearControls(MinecraftClient client) {
        if (client.player == null || client.options == null) {
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

    public boolean isActive() {
        return activeLane != null;
    }

    public record DebugStatus(
            boolean active,
            Integer laneIndex,
            GroundedLaneWalker.GroundedLaneWalkState walkState,
            boolean awaitingStartApproach,
            Optional<String> failureReason
    ) {
    }
}
