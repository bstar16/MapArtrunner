package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GroundedSingleLaneDebugRunner {
    private static final int MAX_PLACEMENT_ATTEMPTS_PER_TICK = 3;

    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final PlacementExecutor placementExecutor = new PlacementExecutor();
    private final BaritoneFacade baritoneFacade;

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private GroundedSweepPlacementExecutor lanePlacementExecutor;
    private Map<Integer, Placement> placementByIndex = Map.of();
    private List<GroundedSweepPlacementExecutor.PlacementTarget> lanePlacementTargets = List.of();
    private Set<Integer> resolvedPlacements = Set.of();
    private List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> currentLeftovers = List.of();
    private long tickCounter;
    private int successCount;
    private int missedCount;
    private int failedCount;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;
    private DebugStatus lastStatus = new DebugStatus(false, null, GroundedLaneWalker.GroundedLaneWalkState.IDLE, false, Optional.empty(), 0, 0, 0, List.of());

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
        lanePlacementExecutor = new GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(settings));
        placementByIndex = buildPlacementMap(plan.placements());
        lanePlacementTargets = laneTargetsFor(plan.placements(), session.getOrigin(), activeLane, settings.sweepHalfWidth());
        resolvedPlacements = new HashSet<>();
        currentLeftovers = List.of();
        tickCounter = 0L;
        successCount = 0;
        missedCount = 0;
        failedCount = 0;
        awaitingStartApproach = true;
        startApproachIssued = false;
        displacementAlert.reset();
        lastStatus = new DebugStatus(true, activeLane.laneIndex(), GroundedLaneWalker.GroundedLaneWalkState.IDLE, true, Optional.empty(), 0, 0, 0, List.of());
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
            handleTerminalState(client);
            return;
        }

        laneWalker.tick(client.player.getEntityPos());
        tickCounter++;
        tickPlacement(client);
        applyLaneControls(client);
        displacementAlert.tick(client, true, client.player.getY() < activeBounds.minY());

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            handleTerminalState(client);
        }
    }

    public Optional<String> stop() {
        if (!isActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                clearControls(client);
            }
            return Optional.empty();
        }

        awaitingStartApproach = false;
        laneWalker.interrupt();
        baritoneFacade.cancel();
        handleTerminalState(MinecraftClient.getInstance());
        return Optional.empty();
    }

    public DebugStatus status() {
        if (isActive()) {
            return new DebugStatus(
                    true,
                    activeLane.laneIndex(),
                    laneWalker.state(),
                    awaitingStartApproach,
                    laneWalker.failureReason(),
                    successCount,
                    missedCount,
                    failedCount,
                    currentLeftovers
            );
        }
        return lastStatus;
    }

    private void tickStartApproach(MinecraftClient client, boolean constantSprint) {
        BlockPos standingStart = approachTargetForLaneStart(activeLane, activeBounds);
        if (!isNearLaneStart(client.player.getEntityPos(), standingStart)) {
            issueStartApproachIfNeeded();
            return;
        }

        baritoneFacade.cancel();
        laneWalker.start(activeLane, activeBounds, constantSprint);
        awaitingStartApproach = false;
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.ACTIVE,
                false,
                Optional.empty(),
                successCount,
                missedCount,
                failedCount,
                currentLeftovers
        );
        applyLaneControls(client);
    }

    void issueStartApproachIfNeeded() {
        if (!startApproachIssued && activeLane != null && activeBounds != null) {
            baritoneFacade.goTo(approachTargetForLaneStart(activeLane, activeBounds));
            startApproachIssued = true;
        }
    }

    void finalizeTerminalStateForTests(GroundedLaneWalker.GroundedLaneWalkState terminalState, Optional<String> failureReason) {
        captureLastStatus(terminalState, failureReason);
        clearActiveRunState();
    }

    private void handleTerminalState(MinecraftClient client) {
        captureLastStatus(laneWalker.state(), laneWalker.failureReason());
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private void captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState state, Optional<String> failureReason) {
        Integer laneIndex = activeLane == null ? null : activeLane.laneIndex();
        lastStatus = new DebugStatus(
                false,
                laneIndex,
                state,
                false,
                failureReason == null ? Optional.empty() : failureReason,
                successCount,
                missedCount,
                failedCount,
                currentLeftovers
        );
    }

    private void clearActiveRunState() {
        activeSession = null;
        activeBounds = null;
        activeLane = null;
        lanePlacementExecutor = null;
        placementByIndex = Map.of();
        lanePlacementTargets = List.of();
        resolvedPlacements = Set.of();
        currentLeftovers = List.of();
        awaitingStartApproach = false;
        startApproachIssued = false;
        displacementAlert.reset();
    }

    private void tickPlacement(MinecraftClient client) {
        if (client == null || activeLane == null || activeBounds == null || activeSession == null || lanePlacementExecutor == null) {
            return;
        }

        int currentProgressCoordinate = progressCoordinateFromPlayer(activeLane.direction(), client.player.getEntityPos());
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = unresolvedLaneTargets();
        GroundedSweepPlacementExecutor.SweepSelection selection = lanePlacementExecutor.select(
                activeLane,
                activeBounds,
                currentProgressCoordinate,
                tickCounter,
                pending
        );
        currentLeftovers = selection.leftovers();

        int attempts = 0;
        for (GroundedSweepPlacementExecutor.SweepCandidate candidate : selection.rankedCandidates()) {
            if (attempts >= MAX_PLACEMENT_ATTEMPTS_PER_TICK) {
                break;
            }
            resolvePlacementAttempt(client, candidate.placementIndex(), candidate.worldPos());
            attempts++;
        }
    }

    private void resolvePlacementAttempt(MinecraftClient client, int placementIndex, BlockPos worldPos) {
        Placement placement = placementByIndex.get(placementIndex);
        if (placement == null || activeSession == null) {
            lanePlacementExecutor.recordPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.FAILED, tickCounter);
            failedCount++;
            return;
        }

        PlacementResult placementResult = placementExecutor.execute(client, activeSession, placement, worldPos);
        if (placementResult.status() == PlacementResult.Status.PLACED
                || placementResult.status() == PlacementResult.Status.ALREADY_CORRECT) {
            lanePlacementExecutor.recordPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, tickCounter);
            resolvedPlacements.add(placementIndex);
            successCount++;
            return;
        }

        if (placementResult.status() == PlacementResult.Status.RETRY
                || placementResult.status() == PlacementResult.Status.MOVE_REQUIRED) {
            lanePlacementExecutor.recordPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.MISSED, tickCounter);
            missedCount++;
            return;
        }

        lanePlacementExecutor.recordPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.FAILED, tickCounter);
        failedCount++;
    }

    private List<GroundedSweepPlacementExecutor.PlacementTarget> unresolvedLaneTargets() {
        if (lanePlacementTargets.isEmpty()) {
            return List.of();
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> unresolved = new ArrayList<>(lanePlacementTargets.size());
        for (GroundedSweepPlacementExecutor.PlacementTarget target : lanePlacementTargets) {
            if (!resolvedPlacements.contains(target.placementIndex())) {
                unresolved.add(target);
            }
        }
        return unresolved;
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> laneTargetsFor(List<Placement> placements,
                                                                                BlockPos origin,
                                                                                GroundedSweepLane lane,
                                                                                int corridorHalfWidth) {
        List<GroundedSweepPlacementExecutor.PlacementTarget> targets = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            BlockPos worldPos = origin.add(placement.relativePos());
            if (withinLaneStripe(worldPos, lane, corridorHalfWidth)) {
                targets.add(new GroundedSweepPlacementExecutor.PlacementTarget(index, worldPos));
            }
        }
        return List.copyOf(targets);
    }

    private static boolean withinLaneStripe(BlockPos worldPos, GroundedSweepLane lane, int corridorHalfWidth) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        if (worldPos.getX() < corridor.minX() || worldPos.getX() > corridor.maxX()
                || worldPos.getZ() < corridor.minZ() || worldPos.getZ() > corridor.maxZ()) {
            return false;
        }
        int lateralCoordinate = lane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
        return Math.abs(lateralCoordinate - lane.centerlineCoordinate()) <= corridorHalfWidth;
    }

    private static Map<Integer, Placement> buildPlacementMap(List<Placement> placements) {
        Map<Integer, Placement> map = new HashMap<>();
        for (int i = 0; i < placements.size(); i++) {
            map.put(i, placements.get(i));
        }
        return Map.copyOf(map);
    }

    private static int progressCoordinateFromPlayer(GroundedLaneDirection direction, Vec3d playerPos) {
        return direction.alongX() ? BlockPos.ofFloored(playerPos).getX() : BlockPos.ofFloored(playerPos).getZ();
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

    static BlockPos approachTargetForLaneStart(GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        return new BlockPos(lane.startPoint().getX(), bounds.minY() + 1, lane.startPoint().getZ());
    }

    static boolean isNearLaneStart(Vec3d playerPosition, BlockPos standingStartTarget) {
        Vec3d standingCenter = new Vec3d(standingStartTarget.getX() + 0.5, standingStartTarget.getY(), standingStartTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter) <= 2.25;
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
            Optional<String> failureReason,
            int successCount,
            int missedCount,
            int failedCount,
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers
    ) {
        public DebugStatus {
            failureReason = failureReason == null ? Optional.empty() : failureReason;
            leftovers = List.copyOf(leftovers);
        }
    }
}
