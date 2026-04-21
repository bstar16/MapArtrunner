package com.example.mapart.plan.sweep;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.sweep.air.AirPlacementEngine;
import com.example.mapart.plan.sweep.air.AirPlacementOutcome;
import com.example.mapart.plan.sweep.air.AirPlacementRequest;
import com.example.mapart.plan.sweep.flight.ElytraFlightController;
import com.example.mapart.plan.sweep.flight.ElytraFlightControllerSettings;
import com.example.mapart.plan.sweep.flight.FlightRecoveryHandler;
import com.example.mapart.plan.sweep.flight.LaneEntryPlanner;
import com.example.mapart.plan.sweep.flight.TurnPlanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SingleLaneSweepDebugRunner {
    private static final LanePlannerSettings LANE_SETTINGS = new LanePlannerSettings(2, 2, 1.0);
    private static final double ELYTRA_HORIZONTAL_SPEED = 1.20;
    private static final double ELYTRA_VERTICAL_SPEED = 0.40;
    private static final double ELYTRA_VELOCITY_BLEND = 0.25;

    private final AirPlacementEngine airPlacementEngine = new AirPlacementEngine();
    private final LanePlanner lanePlanner = new LanePlanner();

    private SweepPassController activeController;
    private BuildSession activeSession;
    private BuildLane activeLane;
    private SweepPassResult lastResult;

    public Optional<String> start(BuildSession session, int laneIndex) {
        Objects.requireNonNull(session, "session");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting debug elytra sweep mode.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }

        if (activeController != null && !isTerminal(activeController.state())) {
            return Optional.of("Single-lane debug sweep is already active.");
        }

        List<BuildLane> lanes = planLanes(plan);
        Optional<BuildLane> lane = lanes.stream().filter(candidate -> candidate.laneIndex() == laneIndex).findFirst();
        if (lane.isEmpty()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        BuildPlaneModel model = new BuildPlaneModel(lanes, plan.placements(), index -> false);
        Map<Integer, Placement> placementMap = new HashMap<>();
        for (int i = 0; i < plan.placements().size(); i++) {
            placementMap.put(i, plan.placements().get(i));
        }

        BuildLane selectedLane = lane.get();
        ElytraFlightController flightController = new ElytraFlightController(
                selectedLane,
                ElytraFlightControllerSettings.defaults(),
                new LaneEntryPlanner(),
                new TurnPlanner(),
                new FlightRecoveryHandler(),
                session.getOrigin()
        );

        activeController = new SweepPassController(
                0,
                model,
                selectedLane,
                new SweepPlacementController(SweepPlacementControllerSettings.defaults()),
                candidate -> attemptPlacement(session.getOrigin(), candidate, placementMap),
                SweepPassControllerSettings.defaults(),
                flightController
        );
        activeSession = session;
        activeLane = selectedLane;
        lastResult = null;
        return Optional.empty();
    }

    public void tick(MinecraftClient client) {
        if (client == null || client.player == null || activeController == null || activeSession == null || activeLane == null) {
            return;
        }

        if (isTerminal(activeController.state())) {
            lastResult = activeController.result();
            deactivate(client);
            return;
        }

        Vec3d worldPlayerPos = client.player.getEntityPos();
        Vec3d relativePlayerPos = toRelative(worldPlayerPos, activeSession.getOrigin());

        activeController.tick(SweepPassController.PassTickInput.withWorldAndRelative(
                worldPlayerPos,
                relativePlayerPos,
                client.player.isGliding()
        ));
        applyFlightControls(client);

        if (isTerminal(activeController.state())) {
            lastResult = activeController.result();
            deactivate(client);
        }
    }

    public Optional<String> stop() {
        if (activeController == null) {
            return Optional.of("Single-lane debug sweep is not active.");
        }
        if (!isTerminal(activeController.state())) {
            activeController.interrupt();
        }
        lastResult = activeController.result();
        MinecraftClient client = MinecraftClient.getInstance();
        deactivate(client);
        return Optional.empty();
    }

    public Optional<SweepPassResult> activeResult() {
        if (activeController == null) {
            return Optional.empty();
        }
        return Optional.of(activeController.result());
    }

    public Optional<SweepPassResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    private PlacementAttemptResult attemptPlacement(BlockPos origin,
                                                    SweepPlacementCandidate candidate,
                                                    Map<Integer, Placement> placementMap) {
        MinecraftClient client = MinecraftClient.getInstance();
        Placement placement = placementMap.get(candidate.placementIndex());
        if (placement == null) {
            return PlacementAttemptResult.failed("placement index missing");
        }

        Item requiredItem = placement.block().asItem();
        BlockPos targetPos = origin.add(candidate.relativePos());
        AirPlacementOutcome outcome = airPlacementEngine.place(
                client,
                new AirPlacementRequest(targetPos, requiredItem, 5.25, Hand.MAIN_HAND, true)
        );

        if (outcome == AirPlacementOutcome.SUCCESS) {
            return PlacementAttemptResult.placed();
        }
        if (outcome == AirPlacementOutcome.OUT_OF_RANGE) {
            return PlacementAttemptResult.skipped("target out of reach");
        }
        return PlacementAttemptResult.failed(outcome.name());
    }

    private List<BuildLane> planLanes(BuildPlan plan) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Placement placement : plan.placements()) {
            BlockPos pos = placement.relativePos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        return lanePlanner.planLanes(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), LANE_SETTINGS);
    }

    private static boolean isTerminal(SweepPassState state) {
        return state == SweepPassState.COMPLETE || state == SweepPassState.FAILED || state == SweepPassState.INTERRUPTED;
    }

    private static Vec3d toRelative(Vec3d world, BlockPos origin) {
        return new Vec3d(world.x - origin.getX(), world.y - origin.getY(), world.z - origin.getZ());
    }

    private void applyFlightControls(MinecraftClient client) {
        if (activeController == null || client.player == null || client.options == null) {
            return;
        }
        var command = activeController.currentFlightCommand().orElse(ElytraFlightController.FlightControlCommand.idle());
        client.player.setYaw(command.yaw());
        client.player.setPitch(command.pitch());
        client.player.setSprinting(command.sprinting());
        setKey(client.options.forwardKey, command.forwardPressed());
        setKey(client.options.backKey, command.backPressed());
        setKey(client.options.leftKey, command.leftPressed());
        setKey(client.options.rightKey, command.rightPressed());
        setKey(client.options.jumpKey, command.jumpPressed());
        setKey(client.options.sneakKey, command.sneakPressed());
        applyElytraVelocityControl(client, command);
    }

    private static void applyElytraVelocityControl(MinecraftClient client, ElytraFlightController.FlightControlCommand command) {
        if (client.player == null || !client.player.isGliding()) {
            return;
        }

        double yawRad = Math.toRadians(command.yaw());
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        Vec3d right = new Vec3d(Math.cos(yawRad), 0.0, Math.sin(yawRad));

        double forwardIntent = (command.forwardPressed() ? 1.0 : 0.0) - (command.backPressed() ? 1.0 : 0.0);
        double strafeIntent = (command.rightPressed() ? 1.0 : 0.0) - (command.leftPressed() ? 1.0 : 0.0);
        Vec3d horizontalIntent = forward.multiply(forwardIntent).add(right.multiply(strafeIntent));
        if (horizontalIntent.lengthSquared() > 0.0001) {
            horizontalIntent = horizontalIntent.normalize().multiply(ELYTRA_HORIZONTAL_SPEED);
        }

        double verticalIntent = (command.jumpPressed() ? 1.0 : 0.0) - (command.sneakPressed() ? 1.0 : 0.0);
        double targetY = verticalIntent * ELYTRA_VERTICAL_SPEED;

        Vec3d current = client.player.getVelocity();
        Vec3d target = new Vec3d(horizontalIntent.x, targetY, horizontalIntent.z);
        Vec3d blended = current.multiply(1.0 - ELYTRA_VELOCITY_BLEND).add(target.multiply(ELYTRA_VELOCITY_BLEND));

        client.player.setVelocity(blended);
        client.player.velocityDirty = true;
    }

    private static void clearFlightControls(MinecraftClient client) {
        if (client.options == null || client.player == null) {
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
        if (client != null) {
            clearFlightControls(client);
        }
        activeController = null;
        activeSession = null;
        activeLane = null;
    }
}
